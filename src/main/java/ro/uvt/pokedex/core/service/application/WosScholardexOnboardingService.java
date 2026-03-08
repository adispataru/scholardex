package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.H19CanonicalMetrics;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
public class WosScholardexOnboardingService {

    private static final String SOURCE_WOS = "WOS";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String FORUM_DEFAULT_AGG = "JOURNAL";

    private static final String STATUS_OPEN = "OPEN";

    private static final String REASON_WOS_FORUM_ONBOARDING = "wos-forum-onboarding";
    private static final String REASON_WOS_PUBLICATION_LINK = "wos-publication-link";

    private static final String REASON_AMBIGUOUS_ISSN = "AMBIGUOUS_ISSN_MATCH";
    private static final String REASON_AMBIGUOUS_NAME_AGG = "AMBIGUOUS_NAME_AGG_MATCH";
    private static final String REASON_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final String REASON_INVALID_ISSN = "NORMALIZATION_INVALID_ISSN";

    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final WosJournalIdentityRepository wosJournalIdentityRepository;
    private final WosRankingViewRepository wosRankingViewRepository;
    private final ScopusForumFactRepository scopusForumFactRepository;
    private final ScholardexForumFactRepository scholardexForumFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    public ImportProcessingResult runWosOnboarding(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<WosJournalIdentity> journals = new ArrayList<>(wosJournalIdentityRepository.findAll());
        journals.sort(Comparator.comparing(WosJournalIdentity::getId, Comparator.nullsLast(String::compareTo)));

        Map<String, WosRankingView> rankingById = new LinkedHashMap<>();
        for (WosRankingView rankingView : wosRankingViewRepository.findAll()) {
            rankingById.put(rankingView.getId(), rankingView);
        }

        List<ScopusForumFact> scopusForums = new ArrayList<>(scopusForumFactRepository.findAll());
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(scholardexForumFactRepository.findAll());
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            canonicalById.put(canonicalForum.getId(), canonicalForum);
        }

        Instant now = Instant.now();
        for (WosJournalIdentity journal : journals) {
            result.markProcessed();
            upsertForumFromWos(journal, rankingById.get(journal.getId()), scopusForums, canonicalById, batchId, correlationId, now, result);
        }

        onboardPublicationWosLinks(batchId, correlationId, now, result);
        return result;
    }

    private void upsertForumFromWos(
            WosJournalIdentity journal,
            WosRankingView rankingView,
            List<ScopusForumFact> scopusForums,
            Map<String, ScholardexForumFact> canonicalById,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        String sourceRecordId = normalizeBlank(journal.getId());
        if (sourceRecordId == null) {
            result.markSkipped("wos-journal-missing-id");
            return;
        }

        LinkedHashSet<String> normalizedIssns = normalizedIssnSet(
                journal.getPrimaryIssn(),
                journal.getEIssn(),
                journal.getAliasIssns(),
                rankingView == null ? null : rankingView.getIssn(),
                rankingView == null ? null : rankingView.getEIssn(),
                rankingView == null ? null : rankingView.getAlternativeIssns()
        );
        String name = firstNonBlank(
                journal.getTitle(),
                rankingView == null ? null : rankingView.getName(),
                sourceRecordId
        );
        String aggregationType = FORUM_DEFAULT_AGG;
        String nameNormalized = normalizeName(name);
        String aggregationTypeNormalized = normalizeToken(aggregationType);
        String nameAggKey = nameNormalized + "|" + aggregationTypeNormalized;

        if (normalizedIssns.isEmpty() && hasAnyNonBlank(journal.getPrimaryIssn(), journal.getEIssn(), join(journal.getAliasIssns()))) {
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, REASON_INVALID_ISSN, List.of(), batchId, correlationId);
        }

        Optional<ScholardexSourceLink> existingLink = sourceLinkService
                .findByKey(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId);
        if (existingLink.isPresent()) {
            String canonicalId = normalizeBlank(existingLink.get().getCanonicalEntityId());
            if (canonicalId != null && canonicalById.containsKey(canonicalId)) {
                ScholardexForumFact target = canonicalById.get(canonicalId);
                mergeForum(target, sourceRecordId, normalizedIssns, name, nameNormalized, aggregationType, aggregationTypeNormalized, scopusForums, now, batchId, correlationId);
                scholardexForumFactRepository.save(target);
                upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId);
                result.markUpdated();
                return;
            }
        }

        List<ScholardexForumFact> candidates = findCanonicalCandidates(canonicalById.values(), normalizedIssns, nameAggKey);
        if (candidates.size() > 1) {
            String reason = normalizedIssns.isEmpty() ? REASON_AMBIGUOUS_NAME_AGG : REASON_AMBIGUOUS_ISSN;
            List<String> candidateIds = candidates.stream().map(ScholardexForumFact::getId).toList();
            upsertConflictSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, reason, batchId, correlationId);
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, reason, candidateIds, batchId, correlationId);
            result.markSkipped("wos-forum-ambiguous-candidates sourceRecordId=" + sourceRecordId);
            return;
        }

        ScholardexForumFact target = candidates.isEmpty() ? new ScholardexForumFact() : candidates.getFirst();
        boolean created = target.getId() == null;
        mergeForum(target, sourceRecordId, normalizedIssns, name, nameNormalized, aggregationType, aggregationTypeNormalized, scopusForums, now, batchId, correlationId);
        scholardexForumFactRepository.save(target);
        canonicalById.put(target.getId(), target);
        upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    private void mergeForum(
            ScholardexForumFact target,
            String wosForumId,
            LinkedHashSet<String> normalizedIssns,
            String wosName,
            String wosNameNormalized,
            String defaultAggregationType,
            String defaultAggregationTypeNormalized,
            List<ScopusForumFact> scopusForums,
            Instant now,
            String batchId,
            String correlationId
    ) {
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }

        List<ScopusForumFact> scopusCandidates = findScopusCandidates(scopusForums, normalizedIssns, wosNameNormalized, defaultAggregationTypeNormalized);
        ScopusForumFact scopusPreferred = scopusCandidates.size() == 1 ? scopusCandidates.getFirst() : null;

        LinkedHashSet<String> scopusIds = new LinkedHashSet<>(safeList(target.getScopusForumIds()));
        if (scopusPreferred != null && normalizeBlank(scopusPreferred.getSourceId()) != null) {
            scopusIds.add(scopusPreferred.getSourceId());
        }
        target.setScopusForumIds(new ArrayList<>(scopusIds));

        LinkedHashSet<String> wosIds = new LinkedHashSet<>(safeList(target.getWosForumIds()));
        wosIds.add(wosForumId);
        target.setWosForumIds(new ArrayList<>(wosIds));

        List<String> issnList = new ArrayList<>(normalizedIssns);
        String preferredIssn = issnList.isEmpty() ? null : issnList.getFirst();
        String preferredEIssn = issnList.size() > 1 ? issnList.get(1) : null;
        if (target.getIssn() == null) {
            target.setIssn(preferredIssn);
        }
        if (target.getEIssn() == null) {
            target.setEIssn(preferredEIssn);
        }
        if (scopusPreferred != null) {
            String scopusIssn = normalizeIssn(scopusPreferred.getIssn());
            String scopusEIssn = normalizeIssn(scopusPreferred.getEIssn());
            if (scopusIssn != null) {
                target.setIssn(scopusIssn);
            }
            if (scopusEIssn != null) {
                target.setEIssn(scopusEIssn);
            }
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>(safeList(target.getAliasIssns()));
        aliases.addAll(issnList);
        if (target.getIssn() != null) {
            aliases.remove(target.getIssn());
        }
        if (target.getEIssn() != null) {
            aliases.remove(target.getEIssn());
        }
        target.setAliasIssns(new ArrayList<>(aliases));

        String preferredName = scopusPreferred != null && normalizeBlank(scopusPreferred.getPublicationName()) != null
                ? scopusPreferred.getPublicationName()
                : firstNonBlank(target.getName(), wosName);
        target.setName(preferredName);
        target.setNameNormalized(normalizeName(preferredName));

        String preferredAgg = scopusPreferred != null && normalizeBlank(scopusPreferred.getAggregationType()) != null
                ? scopusPreferred.getAggregationType()
                : firstNonBlank(target.getAggregationType(), defaultAggregationType);
        target.setAggregationType(preferredAgg);
        target.setAggregationTypeNormalized(normalizeToken(preferredAgg));

        String forumId = target.getId();
        if (forumId == null) {
            forumId = buildCanonicalForumId(target.getIssn(), target.getEIssn(), target.getAliasIssns(), target.getNameNormalized(), target.getAggregationTypeNormalized());
            target.setId(forumId);
        }
        target.setSourceEventId(target.getSourceEventId());
        target.setSource(SOURCE_WOS);
        target.setSourceRecordId(wosForumId);
        target.setSourceBatchId(batchId);
        target.setSourceCorrelationId(correlationId);
        target.setUpdatedAt(now);
    }

    private List<ScopusForumFact> findScopusCandidates(
            List<ScopusForumFact> scopusForums,
            Collection<String> issnTokens,
            String nameNormalized,
            String aggregationTypeNormalized
    ) {
        List<ScopusForumFact> candidates = new ArrayList<>();
        for (ScopusForumFact scopusForum : scopusForums) {
            if (matchesIssn(scopusForum, issnTokens)) {
                candidates.add(scopusForum);
            }
        }
        if (!candidates.isEmpty() || nameNormalized == null) {
            return candidates;
        }
        for (ScopusForumFact scopusForum : scopusForums) {
            String scopusName = normalizeName(scopusForum.getPublicationName());
            String scopusAgg = normalizeToken(scopusForum.getAggregationType());
            if (nameNormalized.equals(scopusName) && normalizeToken(aggregationTypeNormalized).equals(scopusAgg)) {
                candidates.add(scopusForum);
            }
        }
        return candidates;
    }

    private List<ScholardexForumFact> findCanonicalCandidates(
            Collection<ScholardexForumFact> existingForums,
            Collection<String> issnTokens,
            String nameAggKey
    ) {
        List<ScholardexForumFact> candidates = new ArrayList<>();
        if (!issnTokens.isEmpty()) {
            for (ScholardexForumFact forum : existingForums) {
                if (matchesIssn(forum, issnTokens)) {
                    candidates.add(forum);
                }
            }
            return candidates;
        }
        for (ScholardexForumFact forum : existingForums) {
            String key = normalizeName(forum.getName()) + "|" + normalizeToken(forum.getAggregationType());
            if (key.equals(nameAggKey)) {
                candidates.add(forum);
            }
        }
        return candidates;
    }

    private boolean matchesIssn(ScopusForumFact scopusForum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        String issn = normalizeIssn(scopusForum.getIssn());
        String eIssn = normalizeIssn(scopusForum.getEIssn());
        return issnTokens.contains(issn) || issnTokens.contains(eIssn);
    }

    private boolean matchesIssn(ScholardexForumFact forum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        if (issnTokens.contains(normalizeIssn(forum.getIssn())) || issnTokens.contains(normalizeIssn(forum.getEIssn()))) {
            return true;
        }
        for (String alias : safeList(forum.getAliasIssns())) {
            if (issnTokens.contains(normalizeIssn(alias))) {
                return true;
            }
        }
        return false;
    }

    private void onboardPublicationWosLinks(
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        List<ScholardexPublicationFact> publications = new ArrayList<>(scholardexPublicationFactRepository.findAll());
        publications.sort(Comparator.comparing(ScholardexPublicationFact::getId, Comparator.nullsLast(String::compareTo)));
        for (ScholardexPublicationFact publication : publications) {
            String wosId = normalizeBlank(publication.getWosId());
            if (wosId == null || Publication.NON_WOS_ID.equalsIgnoreCase(wosId)) {
                continue;
            }
            List<ScholardexSourceLink> existing = sourceLinkService
                    .findByEntityTypeAndSourceRecordId(ScholardexEntityType.PUBLICATION, wosId);
            List<String> distinctCanonicalIds = existing.stream()
                    .map(ScholardexSourceLink::getCanonicalEntityId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
            if (distinctCanonicalIds.size() > 1
                    || (distinctCanonicalIds.size() == 1 && !distinctCanonicalIds.getFirst().equals(publication.getId()))) {
                upsertConflictSourceLink(ScholardexEntityType.PUBLICATION, SOURCE_WOS, wosId, REASON_SOURCE_ID_COLLISION, batchId, correlationId);
                openConflict(
                        ScholardexEntityType.PUBLICATION,
                        SOURCE_WOS,
                        wosId,
                        REASON_SOURCE_ID_COLLISION,
                        distinctCanonicalIds,
                        batchId,
                        correlationId
                );
                result.markSkipped("wos-publication-source-link-collision wosId=" + wosId);
                continue;
            }
            upsertLinkedSourceLink(
                    ScholardexEntityType.PUBLICATION,
                    SOURCE_WOS,
                    wosId,
                    publication.getId(),
                    REASON_WOS_PUBLICATION_LINK,
                    batchId,
                    correlationId
            );
            result.markUpdated();
        }
    }

    private void upsertLinkedSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String reason,
            String batchId,
            String correlationId
    ) {
        sourceLinkService.link(
                entityType,
                source,
                sourceRecordId,
                canonicalEntityId,
                reason,
                null,
                batchId,
                correlationId,
                false
        );
    }

    private void upsertConflictSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reason,
            String batchId,
            String correlationId
    ) {
        sourceLinkService.markConflict(
                entityType,
                source,
                sourceRecordId,
                reason,
                null,
                batchId,
                correlationId,
                false
        );
    }

    private void openConflict(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String reasonCode,
            List<String> candidateIds,
            String batchId,
            String correlationId
    ) {
        ScholardexIdentityConflict conflict = scholardexIdentityConflictRepository
                .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                        entityType,
                        source,
                        sourceRecordId,
                        reasonCode,
                        STATUS_OPEN
                )
                .orElseGet(ScholardexIdentityConflict::new);
        conflict.setEntityType(entityType);
        conflict.setIncomingSource(source);
        conflict.setIncomingSourceRecordId(sourceRecordId);
        conflict.setReasonCode(reasonCode);
        conflict.setStatus(STATUS_OPEN);
        conflict.setCandidateCanonicalIds(candidateIds == null ? List.of() : new ArrayList<>(candidateIds));
        conflict.setSourceBatchId(batchId);
        conflict.setSourceCorrelationId(correlationId);
        if (conflict.getDetectedAt() == null) {
            conflict.setDetectedAt(Instant.now());
        }
        scholardexIdentityConflictRepository.save(conflict);
        H19CanonicalMetrics.recordConflictCreated(entityType.name(), source, reasonCode);
    }

    private LinkedHashSet<String> normalizedIssnSet(
            String primaryIssn,
            String eIssn,
            List<String> aliasIssns,
            String rankingIssn,
            String rankingEIssn,
            List<String> rankingAliases
    ) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addIssn(out, primaryIssn);
        addIssn(out, eIssn);
        addIssn(out, rankingIssn);
        addIssn(out, rankingEIssn);
        for (String token : safeList(aliasIssns)) {
            addIssn(out, token);
        }
        for (String token : safeList(rankingAliases)) {
            addIssn(out, token);
        }
        return out;
    }

    private void addIssn(LinkedHashSet<String> out, String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized != null) {
            out.add(normalized);
        }
    }

    private String buildCanonicalForumId(
            String issn,
            String eIssn,
            List<String> aliasIssns,
            String nameNormalized,
            String aggregationTypeNormalized
    ) {
        LinkedHashSet<String> issnSet = new LinkedHashSet<>();
        addIssn(issnSet, issn);
        addIssn(issnSet, eIssn);
        for (String aliasIssn : safeList(aliasIssns)) {
            addIssn(issnSet, aliasIssn);
        }
        String material;
        if (!issnSet.isEmpty()) {
            List<String> sorted = issnSet.stream().sorted().toList();
            material = "issn|" + String.join("|", sorted);
        } else {
            material = "nameAgg|" + normalizeToken(nameNormalized) + "|" + normalizeToken(aggregationTypeNormalized);
        }
        return "sforum_" + shortHash(material);
    }

    private String normalizeIssn(String rawIssn) {
        String value = normalizeBlank(rawIssn);
        if (value == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        if (compact.length() != 8) {
            return null;
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    private String normalizeName(String rawName) {
        String value = normalizeBlank(rawName);
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = NON_ALNUM_OR_SPACE.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeToken(String rawValue) {
        String value = normalizeBlank(rawValue);
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private boolean hasAnyNonBlank(String... values) {
        for (String value : values) {
            if (normalizeBlank(value) != null) {
                return true;
            }
        }
        return false;
    }

    private String join(List<String> values) {
        return values == null ? null : String.join(",", values);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
