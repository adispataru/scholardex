package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScholardexPublicationCanonicalizationService {

    static final String LINK_STATE_LINKED = "LINKED";
    static final String LINK_STATE_UNMATCHED = "UNMATCHED";
    private static final String LINK_REASON_SCOPUS_BRIDGE = "scopus-fact-bridge";
    private static final String LINK_REASON_AUTHORSHIP_BRIDGE = "publication-authorship-bridge";
    private static final String LINK_REASON_AUTHOR_FALLBACK = "canonical-author-fallback";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    private final ScholardexAuthorshipFactRepository scholardexAuthorshipFactRepository;

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

        ScholardexPublicationFact fact = loadExistingByEid(scopusFact.getEid());
        boolean created = fact.getId() == null;

        Instant now = Instant.now();
        AuthorBridgeResult authorBridgeResult = bridgeAuthorIds(scopusFact.getAuthors(), scopusFact.getSource());
        applyCanonicalPublicationFields(fact, scopusFact, authorBridgeResult, now);

        scholardexPublicationFactRepository.save(fact);
        upsertSourceLink(fact, LINK_REASON_SCOPUS_BRIDGE);
        upsertAuthorshipEdges(fact, authorBridgeResult, now);

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
        ScholardexSourceLink link = findSourceLink(ScholardexEntityType.PUBLICATION, fact.getSource(), fact.getSourceRecordId())
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

    public AuthorBridgeResult bridgeAuthorIds(List<String> sourceAuthorIds, String source) {
        if (sourceAuthorIds == null || sourceAuthorIds.isEmpty()) {
            return new AuthorBridgeResult(List.of(), List.of(), List.of());
        }
        String sourceToken = normalizeToken(source);
        Map<String, AuthorBridgeEntry> byCanonicalId = new LinkedHashMap<>();
        LinkedHashSet<String> pendingSourceIds = new LinkedHashSet<>();
        for (String rawAuthorId : sourceAuthorIds) {
            String sourceAuthorId = normalizeBlank(rawAuthorId);
            if (sourceAuthorId == null) {
                continue;
            }
            Optional<ScholardexSourceLink> resolved = resolveAuthorSourceLink(source, sourceAuthorId);
            if (resolved.isPresent() && !isBlank(resolved.get().getCanonicalEntityId())) {
                String canonicalAuthorId = resolved.get().getCanonicalEntityId();
                byCanonicalId.putIfAbsent(canonicalAuthorId, new AuthorBridgeEntry(canonicalAuthorId, sourceAuthorId, false));
                continue;
            }
            String fallbackAuthorId = buildCanonicalAuthorFallbackId(sourceToken, sourceAuthorId);
            byCanonicalId.putIfAbsent(fallbackAuthorId, new AuthorBridgeEntry(fallbackAuthorId, sourceAuthorId, true));
            pendingSourceIds.add(sourceAuthorId);
            upsertUnmatchedAuthorSourceLink(source, sourceAuthorId, fallbackAuthorId);
        }
        List<String> canonicalAuthorIds = byCanonicalId.keySet().stream().toList();
        List<AuthorBridgeEntry> entries = new ArrayList<>(byCanonicalId.values());
        return new AuthorBridgeResult(canonicalAuthorIds, new ArrayList<>(pendingSourceIds), entries);
    }

    private Optional<ScholardexSourceLink> resolveAuthorSourceLink(String source, String sourceAuthorId) {
        String normalizedSource = normalizeBlank(source);
        if (normalizedSource != null) {
            Optional<ScholardexSourceLink> direct = findSourceLink(ScholardexEntityType.AUTHOR, normalizedSource, sourceAuthorId);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return findSourceLink(ScholardexEntityType.AUTHOR, SOURCE_SCOPUS, sourceAuthorId);
    }

    private Optional<ScholardexSourceLink> findSourceLink(ScholardexEntityType entityType, String source, String sourceRecordId) {
        Optional<ScholardexSourceLink> link = scholardexSourceLinkRepository
                .findByEntityTypeAndSourceAndSourceRecordId(entityType, source, sourceRecordId);
        return link == null ? Optional.empty() : link;
    }

    private void upsertUnmatchedAuthorSourceLink(String source, String sourceAuthorId, String fallbackAuthorId) {
        if (isBlank(source) || isBlank(sourceAuthorId) || isBlank(fallbackAuthorId)) {
            return;
        }
        ScholardexSourceLink link = findSourceLink(ScholardexEntityType.AUTHOR, source, sourceAuthorId)
                .orElseGet(ScholardexSourceLink::new);
        Instant now = Instant.now();
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource(source);
        link.setSourceRecordId(sourceAuthorId);
        link.setCanonicalEntityId(fallbackAuthorId);
        link.setLinkState(LINK_STATE_UNMATCHED);
        link.setLinkReason(LINK_REASON_AUTHOR_FALLBACK);
        if (link.getLinkedAt() == null) {
            link.setLinkedAt(now);
        }
        link.setUpdatedAt(now);
        scholardexSourceLinkRepository.save(link);
    }

    private void applyCanonicalPublicationFields(
            ScholardexPublicationFact fact,
            ScopusPublicationFact scopusFact,
            AuthorBridgeResult authorBridgeResult,
            Instant now
    ) {
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
        fact.setAuthorIds(authorBridgeResult.canonicalAuthorIds());
        fact.setPendingAuthorSourceIds(authorBridgeResult.pendingSourceIds());
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
    }

    private void upsertAuthorshipEdges(ScholardexPublicationFact fact, AuthorBridgeResult bridge, Instant now) {
        if (fact == null || isBlank(fact.getId()) || bridge == null || bridge.entries().isEmpty()) {
            return;
        }
        for (AuthorBridgeEntry entry : bridge.entries()) {
            ScholardexAuthorshipFact edge = scholardexAuthorshipFactRepository
                    .findByPublicationIdAndAuthorIdAndSource(fact.getId(), entry.canonicalAuthorId(), fact.getSource())
                    .orElseGet(ScholardexAuthorshipFact::new);
            if (edge.getCreatedAt() == null) {
                edge.setCreatedAt(now);
            }
            edge.setPublicationId(fact.getId());
            edge.setAuthorId(entry.canonicalAuthorId());
            edge.setSource(fact.getSource());
            edge.setSourceRecordId(buildAuthorshipSourceRecordId(fact.getSourceRecordId(), entry.sourceAuthorId()));
            edge.setSourceEventId(fact.getSourceEventId());
            edge.setSourceBatchId(fact.getSourceBatchId());
            edge.setSourceCorrelationId(fact.getSourceCorrelationId());
            edge.setLinkState(entry.pendingResolution() ? LINK_STATE_UNMATCHED : LINK_STATE_LINKED);
            edge.setLinkReason(entry.pendingResolution() ? LINK_REASON_AUTHOR_FALLBACK : LINK_REASON_AUTHORSHIP_BRIDGE);
            edge.setUpdatedAt(now);
            scholardexAuthorshipFactRepository.save(edge);
            upsertAuthorshipSourceLink(edge, now);
        }
    }

    public void syncAuthorshipEdges(ScholardexPublicationFact fact, AuthorBridgeResult bridge) {
        upsertAuthorshipEdges(fact, bridge, Instant.now());
    }

    private void upsertAuthorshipSourceLink(ScholardexAuthorshipFact edge, Instant now) {
        if (edge == null || isBlank(edge.getSource()) || isBlank(edge.getSourceRecordId())) {
            return;
        }
        ScholardexSourceLink link = findSourceLink(ScholardexEntityType.AUTHORSHIP, edge.getSource(), edge.getSourceRecordId())
                .orElseGet(ScholardexSourceLink::new);
        link.setEntityType(ScholardexEntityType.AUTHORSHIP);
        link.setSource(edge.getSource());
        link.setSourceRecordId(edge.getSourceRecordId());
        link.setCanonicalEntityId(edge.getId());
        link.setLinkState(edge.getLinkState());
        link.setLinkReason(edge.getLinkReason());
        link.setSourceEventId(edge.getSourceEventId());
        link.setSourceBatchId(edge.getSourceBatchId());
        link.setSourceCorrelationId(edge.getSourceCorrelationId());
        if (link.getLinkedAt() == null) {
            link.setLinkedAt(now);
        }
        link.setUpdatedAt(now);
        scholardexSourceLinkRepository.save(link);
    }

    private String buildAuthorshipSourceRecordId(String publicationSourceRecordId, String sourceAuthorId) {
        return normalizeToken(publicationSourceRecordId) + "::author::" + normalizeToken(sourceAuthorId);
    }

    private String buildCanonicalAuthorFallbackId(String sourceToken, String sourceAuthorId) {
        String normalizedSource = isBlank(sourceToken) ? "unknown" : sourceToken;
        return "sauth_" + shortHash("source|" + normalizedSource + "|author|" + normalizeToken(sourceAuthorId));
    }

    private ScholardexPublicationFact loadExistingByEid(String eid) {
        Optional<ScholardexPublicationFact> existing = scholardexPublicationFactRepository.findByEid(eid);
        return existing.orElseGet(ScholardexPublicationFact::new);
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

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    public record AuthorBridgeResult(
            List<String> canonicalAuthorIds,
            List<String> pendingSourceIds,
            List<AuthorBridgeEntry> entries
    ) {}

    public record AuthorBridgeEntry(
            String canonicalAuthorId,
            String sourceAuthorId,
            boolean pendingResolution
    ) {}
}
