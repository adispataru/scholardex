package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationCanonicalizationService {

    static final String LINK_STATE_LINKED = "LINKED";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-fact-bridge";
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexSourceLinkRepository scholardexSourceLinkRepository;

    public ImportProcessingResult rebuildCanonicalPublicationFactsFromScopusFacts() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScopusPublicationFact> scopusFacts = new ArrayList<>(scopusPublicationFactRepository.findAll());
        scopusFacts.sort(Comparator.comparing(ScopusPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
        for (ScopusPublicationFact scopusFact : scopusFacts) {
            result.markProcessed();
            upsertFromScopusFact(scopusFact, result);
        }
        return result;
    }

    public void upsertFromScopusFact(ScopusPublicationFact scopusFact, ImportProcessingResult result) {
        if (scopusFact == null || isBlank(scopusFact.getEid())) {
            if (result != null) {
                result.markSkipped("missing scopus publication eid");
            }
            return;
        }

        Optional<ScholardexPublicationFact> existingByEid = scholardexPublicationFactRepository.findByEid(scopusFact.getEid());
        ScholardexPublicationFact fact = existingByEid.orElseGet(ScholardexPublicationFact::new);
        boolean created = fact.getId() == null;

        String doiNormalized = normalizeDoi(scopusFact.getDoi());
        String titleNormalized = normalizeTitle(scopusFact.getTitle());
        String canonicalId = buildCanonicalPublicationId(
                scopusFact.getEid(),
                fact.getWosId(),
                fact.getGoogleScholarId(),
                fact.getUserSourceId(),
                doiNormalized,
                titleNormalized,
                scopusFact.getCoverDate(),
                scopusFact.getCreator(),
                scopusFact.getForumId()
        );

        Instant now = Instant.now();
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(now);
        }
        fact.setId(canonicalId);
        fact.setDoi(scopusFact.getDoi());
        fact.setDoiNormalized(doiNormalized);
        fact.setTitle(scopusFact.getTitle());
        fact.setTitleNormalized(titleNormalized);
        fact.setEid(scopusFact.getEid());
        fact.setSubtype(scopusFact.getSubtype());
        fact.setSubtypeDescription(scopusFact.getSubtypeDescription());
        fact.setScopusSubtype(scopusFact.getScopusSubtype());
        fact.setScopusSubtypeDescription(scopusFact.getScopusSubtypeDescription());
        fact.setCreator(scopusFact.getCreator());
        fact.setAuthorCount(scopusFact.getAuthorCount());
        fact.setAuthorIds(scopusFact.getAuthors() == null ? List.of() : new ArrayList<>(scopusFact.getAuthors()));
        fact.setCorrespondingAuthors(scopusFact.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(scopusFact.getCorrespondingAuthors()));
        fact.setAffiliationIds(scopusFact.getAffiliations() == null ? List.of() : new ArrayList<>(scopusFact.getAffiliations()));
        fact.setForumId(scopusFact.getForumId());
        fact.setVolume(scopusFact.getVolume());
        fact.setIssueIdentifier(scopusFact.getIssueIdentifier());
        fact.setCoverDate(scopusFact.getCoverDate());
        fact.setCoverDisplayDate(scopusFact.getCoverDisplayDate());
        fact.setDescription(scopusFact.getDescription());
        fact.setCitedByCount(scopusFact.getCitedByCount());
        fact.setOpenAccess(scopusFact.getOpenAccess());
        fact.setFreetoread(scopusFact.getFreetoread());
        fact.setFreetoreadLabel(scopusFact.getFreetoreadLabel());
        fact.setFundingId(scopusFact.getFundingId());
        fact.setArticleNumber(scopusFact.getArticleNumber());
        fact.setPageRange(scopusFact.getPageRange());
        fact.setApproved(scopusFact.getApproved());
        fact.setSourceEventId(scopusFact.getSourceEventId());
        fact.setSource(scopusFact.getSource());
        fact.setSourceRecordId(scopusFact.getSourceRecordId());
        fact.setSourceBatchId(scopusFact.getSourceBatchId());
        fact.setSourceCorrelationId(scopusFact.getSourceCorrelationId());
        fact.setUpdatedAt(now);

        scholardexPublicationFactRepository.save(fact);
        upsertSourceLink(fact, LINK_REASON_SCOPUS_BRIDGE);

        if (result != null) {
            if (created) {
                result.markImported();
            } else {
                result.markUpdated();
            }
        }
    }

    private void upsertSourceLink(ScholardexPublicationFact fact, String reason) {
        if (fact == null || isBlank(fact.getSource()) || isBlank(fact.getSourceRecordId())) {
            return;
        }
        ScholardexSourceLink link = scholardexSourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(ScholardexEntityType.PUBLICATION, fact.getSource(), fact.getSourceRecordId())
                .orElseGet(ScholardexSourceLink::new);
        Instant now = Instant.now();
        link.setEntityType(ScholardexEntityType.PUBLICATION);
        link.setSource(fact.getSource());
        link.setSourceRecordId(fact.getSourceRecordId());
        link.setCanonicalEntityId(fact.getId());
        link.setLinkState(LINK_STATE_LINKED);
        link.setLinkReason(reason);
        link.setSourceEventId(fact.getSourceEventId());
        link.setSourceBatchId(fact.getSourceBatchId());
        link.setSourceCorrelationId(fact.getSourceCorrelationId());
        if (link.getLinkedAt() == null) {
            link.setLinkedAt(now);
        }
        link.setUpdatedAt(now);
        scholardexSourceLinkRepository.save(link);
    }

    public String buildCanonicalPublicationId(
            String eid,
            String wosId,
            String googleScholarId,
            String userSourceId,
            String doiNormalized,
            String titleNormalized,
            String coverDate,
            String creator,
            String forumId
    ) {
        String material;
        if (!isBlank(eid)) {
            material = "eid|" + normalizeToken(eid);
        } else if (!isBlank(wosId)) {
            material = "wos|" + normalizeToken(wosId);
        } else if (!isBlank(googleScholarId)) {
            material = "gs|" + normalizeToken(googleScholarId);
        } else if (!isBlank(userSourceId)) {
            material = "user|" + normalizeToken(userSourceId);
        } else if (!isBlank(doiNormalized)) {
            material = "doi|" + normalizeToken(doiNormalized);
        } else {
            material = "title|" + normalizeToken(titleNormalized)
                    + "|date|" + normalizeToken(coverDate)
                    + "|creator|" + normalizeToken(creator)
                    + "|forum|" + normalizeToken(forumId);
        }
        return "spub_" + shortHash(material);
    }

    public static String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String normalized = doi.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = DOI_URL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "" : normalized;
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
