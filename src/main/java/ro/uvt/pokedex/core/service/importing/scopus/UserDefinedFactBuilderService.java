package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.UserDefinedWizardOnboardingContract;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserDefinedFactBuilderService {

    private static final Logger log = LoggerFactory.getLogger(UserDefinedFactBuilderService.class);
    private static final String SOURCE_USER_PUBLICATION_WIZARD = "USER_PUBLICATION_WIZARD";
    private static final String DEFAULT_REVIEW_REASON = "wizard-submission";

    private final ScopusImportEventRepository importEventRepository;
    private final UserDefinedPublicationFactRepository publicationFactRepository;
    private final UserDefinedForumFactRepository forumFactRepository;
    private final ObjectMapper objectMapper;

    public ImportProcessingResult buildFactsFromImportEvents(String batchId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusImportEvent> events = isBlank(batchId)
                ? new ArrayList<>(importEventRepository.findAll())
                : new ArrayList<>(importEventRepository.findByBatchId(batchId));
        events.sort(Comparator
                .comparing(ScopusImportEvent::getEntityType, Comparator.nullsLast(Enum::compareTo))
                .thenComparing(ScopusImportEvent::getSource, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getSourceRecordId, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScopusImportEvent::getPayloadHash, Comparator.nullsLast(String::compareTo)));

        for (ScopusImportEvent event : events) {
            if (!isUserDefinedPublicationEvent(event)) {
                continue;
            }
            result.markProcessed();
            try {
                JsonNode payload = objectMapper.readTree(event.getPayload());
                upsertPublicationFact(event, payload, result);
                upsertForumFact(event, payload, result);
            } catch (Exception ex) {
                result.markError(sample(event, ex.getMessage()));
                log.error("User-defined fact-builder event failed: sourceRecordId={}, reason={}",
                        event == null ? null : event.getSourceRecordId(), ex.getMessage(), ex);
            }
        }
        return result;
    }

    private void upsertPublicationFact(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String sourceRecordId = normalize(event.getSourceRecordId());
        if (sourceRecordId == null) {
            result.markSkipped(sample(event, "missing sourceRecordId"));
            return;
        }

        UserDefinedPublicationFact fact = publicationFactRepository.findBySourceRecordId(sourceRecordId).orElse(null);
        boolean created = fact == null;
        if (created) {
            fact = new UserDefinedPublicationFact();
        } else if (samePayloadHash(fact.getLastPayloadHash(), event.getPayloadHash())) {
            result.markSkipped(sample(event, "publication payload unchanged"));
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setSource(UserDefinedWizardOnboardingContract.SOURCE);
        fact.setSourceRecordId(sourceRecordId);
        fact.setSourceEventId(event.getId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
        fact.setForumSourceRecordId(text(payload, "source_id"));
        fact.setEid(text(payload, "eid"));
        fact.setDoi(text(payload, "doi"));
        fact.setTitle(text(payload, "title"));
        fact.setSubtype(text(payload, "subtype"));
        fact.setSubtypeDescription(text(payload, "subtypeDescription"));
        fact.setCreator(text(payload, "creator"));
        fact.setAuthorCount(intValue(payload, "author_count"));
        fact.setAuthorIds(splitSemicolon(text(payload, "author_ids")));
        fact.setAuthorAffiliationSourceIds(splitSemicolon(text(payload, "author_afids")));
        fact.setCorrespondingAuthors(splitSemicolon(text(payload, "correspondingAuthors")));
        fact.setAffiliationIds(splitSemicolon(text(payload, "afid")));
        fact.setVolume(text(payload, "volume"));
        fact.setIssueIdentifier(text(payload, "issueIdentifier"));
        fact.setCoverDate(text(payload, "coverDate"));
        fact.setCoverDisplayDate(text(payload, "coverDisplayDate"));
        fact.setDescription(text(payload, "description"));
        fact.setCitedByCount(intValue(payload, "citedby_count"));
        fact.setOpenAccess(boolValue(payload, "openaccess"));
        fact.setFreetoread(text(payload, "freetoread"));
        fact.setFreetoreadLabel(text(payload, "freetoreadLabel"));
        fact.setFundingId(text(payload, "fund_acr"));
        fact.setArticleNumber(text(payload, "article_number"));
        fact.setPageRange(text(payload, "pageRange"));
        fact.setApproved(boolValue(payload, "approved"));
        applyReviewFields(fact, payload, now);
        fact.setLastPayloadHash(event.getPayloadHash());
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        publicationFactRepository.save(fact);
        markImportOrUpdate(result, created);
    }

    private void upsertForumFact(ScopusImportEvent event, JsonNode payload, ImportProcessingResult result) {
        String forumSourceRecordId = normalize(text(payload, "source_id"));
        if (forumSourceRecordId == null || !forumSourceRecordId.startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX)) {
            return;
        }

        UserDefinedForumFact fact = forumFactRepository.findBySourceRecordId(forumSourceRecordId).orElse(null);
        boolean created = fact == null;
        if (created) {
            fact = new UserDefinedForumFact();
        } else if (samePayloadHash(fact.getLastPayloadHash(), event.getPayloadHash())) {
            return;
        }

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setSource(UserDefinedWizardOnboardingContract.SOURCE);
        fact.setSourceRecordId(forumSourceRecordId);
        fact.setSourceEventId(event.getId());
        fact.setSourceBatchId(event.getBatchId());
        fact.setSourceCorrelationId(event.getCorrelationId());
        fact.setPublicationName(text(payload, "publicationName"));
        fact.setIssn(normalizeIssnOrBlank(text(payload, "issn")));
        fact.setEIssn(normalizeIssnOrBlank(text(payload, "eIssn")));
        fact.setAggregationType(text(payload, "aggregationType"));
        fact.setApproved(boolValue(payload, "approved"));
        applyReviewFields(fact, payload, now);
        fact.setLastPayloadHash(event.getPayloadHash());
        fact.setLastMaterializedAt(now);
        fact.setUpdatedAt(now);
        forumFactRepository.save(fact);
        markImportOrUpdate(result, created);
    }

    private void applyReviewFields(UserDefinedPublicationFact fact, JsonNode payload, Instant now) {
        Instant submittedAt = parseInstant(text(payload, "wizardSubmittedAt")).orElse(now);
        fact.setWizardSubmittedAt(submittedAt);
        fact.setWizardSubmitterEmail(text(payload, "wizardSubmitterEmail"));
        fact.setWizardSubmitterResearcherId(text(payload, "wizardSubmitterResearcherId"));
        fact.setReviewState(firstNonBlank(text(payload, "reviewState"), UserDefinedWizardOnboardingContract.REVIEW_STATE));
        fact.setReviewReason(firstNonBlank(text(payload, "reviewReason"), DEFAULT_REVIEW_REASON));
        fact.setReviewStateUpdatedAt(parseInstant(text(payload, "reviewStateUpdatedAt")).orElse(submittedAt));
        fact.setReviewStateUpdatedBy(firstNonBlank(text(payload, "reviewStateUpdatedBy"), fact.getWizardSubmitterEmail()));
        fact.setModerationFlow(firstNonBlank(text(payload, "moderationFlow"), UserDefinedWizardOnboardingContract.MODERATION_FLOW));
    }

    private void applyReviewFields(UserDefinedForumFact fact, JsonNode payload, Instant now) {
        Instant submittedAt = parseInstant(text(payload, "wizardSubmittedAt")).orElse(now);
        fact.setWizardSubmittedAt(submittedAt);
        fact.setWizardSubmitterEmail(text(payload, "wizardSubmitterEmail"));
        fact.setWizardSubmitterResearcherId(text(payload, "wizardSubmitterResearcherId"));
        fact.setReviewState(firstNonBlank(text(payload, "reviewState"), UserDefinedWizardOnboardingContract.REVIEW_STATE));
        fact.setReviewReason(firstNonBlank(text(payload, "reviewReason"), DEFAULT_REVIEW_REASON));
        fact.setReviewStateUpdatedAt(parseInstant(text(payload, "reviewStateUpdatedAt")).orElse(submittedAt));
        fact.setReviewStateUpdatedBy(firstNonBlank(text(payload, "reviewStateUpdatedBy"), fact.getWizardSubmitterEmail()));
        fact.setModerationFlow(firstNonBlank(text(payload, "moderationFlow"), UserDefinedWizardOnboardingContract.MODERATION_FLOW));
    }

    private boolean isUserDefinedPublicationEvent(ScopusImportEvent event) {
        if (event == null || event.getEntityType() != ScopusImportEntityType.PUBLICATION || isBlank(event.getPayload())) {
            return false;
        }
        String source = normalize(event.getSource());
        return UserDefinedWizardOnboardingContract.SOURCE.equals(source)
                || SOURCE_USER_PUBLICATION_WIZARD.equals(source);
    }

    private void markImportOrUpdate(ImportProcessingResult result, boolean created) {
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private boolean samePayloadHash(String first, String second) {
        return normalize(first) != null && normalize(first).equals(normalize(second));
    }

    private String sample(ScopusImportEvent event, String message) {
        if (event == null) {
            return "null-event " + message;
        }
        return event.getEntityType() + ":" + event.getSource() + ":" + event.getSourceRecordId() + " " + message;
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        String value = node.get(field).asText("");
        return value == null ? "" : value.trim();
    }

    private Integer intValue(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean boolValue(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        if (valueNode.isBoolean()) {
            return valueNode.booleanValue();
        }
        if (valueNode.isNumber()) {
            return valueNode.intValue() != 0;
        }
        String text = valueNode.asText("");
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return null;
    }

    private List<String> splitSemicolon(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : value.split(";")) {
            String normalized = normalize(token);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private Optional<Instant> parseInstant(String value) {
        if (isBlank(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.trim()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String normalizeIssnOrBlank(String rawIssn) {
        String value = normalize(rawIssn);
        if (value == null) {
            return "";
        }
        String compact = value.replace("-", "").toUpperCase(Locale.ROOT);
        if (compact.length() != 8) {
            return "";
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return "";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
