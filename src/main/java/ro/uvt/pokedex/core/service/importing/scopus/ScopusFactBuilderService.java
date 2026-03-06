package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusFundingFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ScopusFactBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScopusFactBuilderService.class);

    private final ScopusImportEventRepository importEventRepository;
    private final ScopusPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumFactRepository forumFactRepository;
    private final ScopusAuthorFactRepository authorFactRepository;
    private final ScopusAffiliationFactRepository affiliationFactRepository;
    private final ScopusFundingFactRepository fundingFactRepository;
    private final ObjectMapper objectMapper;

    public ImportProcessingResult buildFactsFromImportEvents() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusImportEvent> events = new ArrayList<>(importEventRepository.findAll());
        events.sort(Comparator
                .comparing(ScopusImportEvent::getEntityType, Comparator.nullsLast(Enum::compareTo))
                .thenComparing(ScopusImportEvent::getSource, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getSourceRecordId, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getPayloadHash, Comparator.nullsLast(String::compareTo)));

        for (ScopusImportEvent event : events) {
            result.markProcessed();
            try {
                processEvent(event, result);
            } catch (Exception e) {
                result.markError(sample(event, e.getMessage()));
            }
        }

        log.info("Scopus fact-builder summary: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                result.getSkippedCount(), result.getErrorCount(), result.getErrorsSample());
        return result;
    }

    private void processEvent(ScopusImportEvent event, ImportProcessingResult result) throws Exception {
        if (event == null || event.getEntityType() == null || event.getPayload() == null) {
            result.markSkipped(sample(event, "missing event metadata"));
            return;
        }
        JsonNode payload = objectMapper.readTree(event.getPayload());
        if (event.getEntityType() == ScopusImportEntityType.PUBLICATION) {
            upsertPublicationAndDimensions(event, payload, result);
            return;
        }
        if (event.getEntityType() == ScopusImportEntityType.CITATION) {
            upsertCitation(event, payload, result);
            JsonNode citingItem = payload.path("citingItem");
            if (!citingItem.isMissingNode() && !citingItem.isNull()) {
                upsertPublicationAndDimensions(event, citingItem, result);
            }
            return;
        }
        result.markSkipped(sample(event, "entity type not supported in H17.4: " + event.getEntityType()));
    }

    private void upsertPublicationAndDimensions(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String eid = text(payload, "eid");
        if (isBlank(eid)) {
            result.markSkipped(sample(event, "publication payload missing eid"));
            return;
        }

        Optional<ScopusPublicationFact> existing = publicationFactRepository.findByEid(eid);
        ScopusPublicationFact fact = existing.orElseGet(ScopusPublicationFact::new);
        boolean created = existing.isEmpty();
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        String subtype = text(payload, "subtype");
        String subtypeDescription = text(payload, "subtypeDescription");
        String fundingKey = normalizeFundingKey(text(payload, "fund_acr"), text(payload, "fund_no"), text(payload, "fund_sponsor"));
        fact.setDoi(text(payload, "doi"));
        fact.setEid(eid);
        fact.setTitle(text(payload, "title"));
        fact.setSubtype(subtype);
        fact.setSubtypeDescription(subtypeDescription);
        fact.setScopusSubtype(subtype);
        fact.setScopusSubtypeDescription(subtypeDescription);
        fact.setCreator(text(payload, "creator"));
        fact.setAuthorCount(intValue(payload, "author_count"));
        fact.setAuthors(distinctNonBlank(splitSemicolon(text(payload, "author_ids"))));
        fact.setCorrespondingAuthors(distinctNonBlank(splitSemicolon(text(payload, "correspondingAuthors"))));
        fact.setAffiliations(distinctNonBlank(splitSemicolon(text(payload, "afid"))));
        fact.setForumId(text(payload, "source_id"));
        fact.setVolume(text(payload, "volume"));
        fact.setIssueIdentifier(text(payload, "issueIdentifier"));
        fact.setCoverDate(text(payload, "coverDate"));
        fact.setCoverDisplayDate(text(payload, "coverDisplayDate"));
        fact.setDescription(text(payload, "description"));
        fact.setCitedByCount(intValue(payload, "citedby_count"));
        fact.setOpenAccess(boolValue(payload, "openaccess"));
        fact.setFreetoread(text(payload, "freetoread"));
        fact.setFreetoreadLabel(text(payload, "freetoreadLabel"));
        fact.setFundingId(isBlank(fundingKey) || "||".equals(fundingKey) ? "" : fundingKey);
        fact.setArticleNumber(text(payload, "article_number"));
        fact.setPageRange(text(payload, "pageRange"));
        fact.setApproved(boolValue(payload, "approved"));
        applyLineage(fact, event);
        fact.setUpdatedAt(now);
        publicationFactRepository.save(fact);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }

        upsertForumFact(event, payload, result);
        upsertAuthorFacts(event, payload, result);
        upsertAffiliationFacts(event, payload, result);
        upsertFundingFact(event, payload, result);
    }

    private void upsertCitation(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String citedEid = text(payload, "citedEid");
        String citingEid = text(payload, "citingEid");
        if (isBlank(citedEid) || isBlank(citingEid)) {
            String sourceRecordId = event.getSourceRecordId();
            if (!isBlank(sourceRecordId) && sourceRecordId.contains("->")) {
                String[] parts = sourceRecordId.split("->", 2);
                citedEid = isBlank(citedEid) ? parts[0] : citedEid;
                citingEid = isBlank(citingEid) ? parts[1] : citingEid;
            }
        }
        if (isBlank(citedEid) || isBlank(citingEid)) {
            result.markSkipped(sample(event, "citation payload missing edge eids"));
            return;
        }

        Optional<ScopusCitationFact> existing = citationFactRepository.findByCitedEidAndCitingEid(citedEid, citingEid);
        ScopusCitationFact fact = existing.orElseGet(ScopusCitationFact::new);
        boolean created = existing.isEmpty();
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setCitedEid(citedEid);
        fact.setCitingEid(citingEid);
        applyLineage(fact, event);
        fact.setUpdatedAt(now);
        citationFactRepository.save(fact);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void upsertForumFact(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String sourceId = text(payload, "source_id");
        if (isBlank(sourceId)) {
            return;
        }
        Optional<ScopusForumFact> existing = forumFactRepository.findBySourceId(sourceId);
        ScopusForumFact fact = existing.orElseGet(ScopusForumFact::new);
        boolean created = existing.isEmpty();
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setSourceId(sourceId);
        fact.setPublicationName(text(payload, "publicationName"));
        fact.setIssn(normalizeIssn(text(payload, "issn")));
        fact.setEIssn(normalizeIssn(text(payload, "eIssn")));
        fact.setAggregationType(text(payload, "aggregationType"));
        applyLineage(fact, event);
        fact.setUpdatedAt(now);
        forumFactRepository.save(fact);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void upsertAuthorFacts(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        List<String> authorIds = splitSemicolon(text(payload, "author_ids"));
        List<String> authorNames = splitSemicolon(text(payload, "author_names"));
        List<String> authorAfids = splitSemicolon(text(payload, "author_afids"));
        int n = Math.min(authorIds.size(), Math.min(authorNames.size(), authorAfids.size()));
        for (int i = 0; i < n; i++) {
            String authorId = trim(authorIds.get(i));
            if (isBlank(authorId)) {
                continue;
            }
            Optional<ScopusAuthorFact> existing = authorFactRepository.findByAuthorId(authorId);
            ScopusAuthorFact fact = existing.orElseGet(ScopusAuthorFact::new);
            boolean created = existing.isEmpty();
            Instant now = Instant.now();
            if (fact.getCreatedAt() == null) {
                fact.setCreatedAt(now);
            }
            fact.setAuthorId(authorId);
            fact.setName(trim(authorNames.get(i)));
            fact.setAffiliationIds(distinctNonBlank(splitDash(authorAfids.get(i))));
            applyLineage(fact, event);
            fact.setUpdatedAt(now);
            authorFactRepository.save(fact);
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    private void upsertAffiliationFacts(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        List<String> afids = splitSemicolon(text(payload, "afid"));
        List<String> names = splitSemicolon(text(payload, "affilname"));
        List<String> cities = splitSemicolon(text(payload, "affiliation_city"));
        List<String> countries = splitSemicolon(text(payload, "affiliation_country"));

        for (int i = 0; i < afids.size(); i++) {
            String afid = trim(afids.get(i));
            if (isBlank(afid)) {
                continue;
            }
            Optional<ScopusAffiliationFact> existing = affiliationFactRepository.findByAfid(afid);
            ScopusAffiliationFact fact = existing.orElseGet(ScopusAffiliationFact::new);
            boolean created = existing.isEmpty();
            Instant now = Instant.now();
            if (fact.getCreatedAt() == null) {
                fact.setCreatedAt(now);
            }
            fact.setAfid(afid);
            fact.setName(arrayValue(names, i));
            fact.setCity(arrayValue(cities, i));
            fact.setCountry(arrayValue(countries, i));
            applyLineage(fact, event);
            fact.setUpdatedAt(now);
            affiliationFactRepository.save(fact);
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    private void upsertFundingFact(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String acronym = text(payload, "fund_acr");
        if (isBlank(acronym)) {
            return;
        }
        String number = text(payload, "fund_no");
        String sponsor = text(payload, "fund_sponsor");
        String fundingKey = normalizeFundingKey(acronym, number, sponsor);

        Optional<ScopusFundingFact> existing = fundingFactRepository.findByFundingKey(fundingKey);
        ScopusFundingFact fact = existing.orElseGet(ScopusFundingFact::new);
        boolean created = existing.isEmpty();
        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setAcronym(acronym);
        fact.setNumber(number);
        fact.setSponsor(sponsor);
        fact.setFundingKey(fundingKey);
        applyLineage(fact, event);
        fact.setUpdatedAt(now);
        fundingFactRepository.save(fact);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void applyLineage(ScopusPublicationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusCitationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusForumFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusAuthorFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusAffiliationFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private void applyLineage(ScopusFundingFact fact, ScopusImportEvent event) {
        fact.setSourceEventId(event.getId());
        fact.setSource(event.getSource());
        fact.setSourceRecordId(event.getSourceRecordId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
    }

    private String sample(ScopusImportEvent event, String message) {
        if (event == null) {
            return "null-event " + message;
        }
        return event.getEntityType() + ":" + event.getSource() + ":" + event.getSourceRecordId() + " " + message;
    }

    private List<String> splitSemicolon(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] raw = value.split(";");
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            out.add(trim(part));
        }
        return out;
    }

    private List<String> splitDash(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] raw = value.split("-");
        List<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            out.add(trim(part));
        }
        return out;
    }

    private List<String> distinctNonBlank(List<String> rawValues) {
        Set<String> out = new LinkedHashSet<>();
        if (rawValues == null) {
            return List.of();
        }
        for (String value : rawValues) {
            String normalized = trim(value);
            if (!isBlank(normalized)) {
                out.add(normalized);
            }
        }
        return new ArrayList<>(out);
    }

    private String arrayValue(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return "";
        }
        return trim(values.get(index));
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        return trim(v.asText(""));
    }

    private Integer intValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return 0;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return 0;
        }
        if (v.canConvertToInt()) {
            return v.asInt();
        }
        try {
            return Integer.parseInt(trim(v.asText("0")));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Boolean boolValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return Boolean.FALSE;
        }
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return Boolean.FALSE;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.asInt() != 0;
        }
        String raw = trim(v.asText("false")).toLowerCase(Locale.ROOT);
        return "1".equals(raw) || "true".equals(raw) || "yes".equals(raw);
    }

    private String normalizeIssn(String value) {
        String normalized = trim(value);
        if (isBlank(normalized)) {
            return "";
        }
        if (!normalized.contains("-") && normalized.length() == 8) {
            return normalized.substring(0, 4) + "-" + normalized.substring(4);
        }
        return normalized;
    }

    private String normalizeFundingKey(String acronym, String number, String sponsor) {
        return (trim(acronym) + "|" + trim(number) + "|" + trim(sponsor)).toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
