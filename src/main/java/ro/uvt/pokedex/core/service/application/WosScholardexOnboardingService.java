package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.service.importing.BuilderVersion;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

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
    // Scopus-native casing ("Journal"), so default-onboarded forums display consistently with
    // source-derived ones. Dedup is case-insensitive (normalizeToken lowercases), so this only
    // affects the stored/display casing, not forum identity.
    private static final String FORUM_DEFAULT_AGG = "Journal";

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String CONFLICT_RESOLVER_SCOPUS_FORUM = "scopus-forum-canonicalization";

    private static final String REASON_WOS_FORUM_ONBOARDING = "wos-forum-onboarding";
    private static final String REASON_SCOPUS_FORUM_ONBOARDING = "scopus-forum-onboarding";
    private static final String REASON_WOS_PUBLICATION_LINK = "wos-publication-link";

    private static final String REASON_AMBIGUOUS_ISSN = "AMBIGUOUS_ISSN_MATCH";
    private static final String REASON_AMBIGUOUS_NAME_AGG = "AMBIGUOUS_NAME_AGG_MATCH";
    private static final String REASON_SOURCE_ID_COLLISION = "SOURCE_ID_COLLISION";
    private static final String REASON_INVALID_ISSN = "NORMALIZATION_INVALID_ISSN";

    // H55 curated source-data correction. "SIAM Journal on Mathematical Analysis" (print ISSN
    // 0036-1410) carries eISSN 1095-7111 in the Scopus source — but 1095-7111 is "SIAM Journal on
    // Computing"'s eISSN; Math Analysis's real eISSN is 1095-7154. The shared *valid* eISSN otherwise
    // bridges two distinct journals (the AMBIGUOUS_ISSN_MATCH / FORUM_DEDUP_NAME_MISMATCH case).
    // Check-digit validation cannot catch this: the value is a valid ISSN, just on the wrong record.
    private static final String SIAM_MATH_ANALYSIS_PRINT_ISSN = "0036-1410";
    private static final String SIAM_COMPUTING_EISSN = "1095-7111";
    private static final String SIAM_MATH_ANALYSIS_EISSN = "1095-7154";
    private static final String REASON_FORUM_EXTERNAL_ID_ALREADY_LINKED = "FORUM_EXTERNAL_ID_ALREADY_LINKED";

    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");
    private static final Pattern NON_ALNUM_OR_SPACE = Pattern.compile("[^\\p{Alnum}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final WosJournalIdentityRepository journalIdentityRepository;
    private final ScopusForumFactRepository scopusForumFactRepository;
    private final ScholardexForumFactRepository scholardexForumFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    public ImportProcessingResult runWosOnboarding(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        // Read the stage-3 WoS journal-identity facts directly (the same source rebuildWosProjections
        // projects 1:1 into reporting_read.wos_ranking_view). Forum canonicalization is stage-3, so it
        // must not depend on the stage-4 projection: doing so created a backwards dependency that ran
        // onboarding before the view it read was built — leaving WoS canonical forums unrebuildable
        // (immortal/stale). Mapping mirrors WosProjectionBuilderService.toRankingView. Sorted by id for
        // deterministic processing order (was ORDER BY journal_id).
        List<WosRankingView> journals = journalIdentityRepository.findAll().stream()
                .sorted(Comparator.comparing(WosJournalIdentity::getId))
                .map(WosScholardexOnboardingService::toRankingView)
                .toList();

        List<ScopusForumFact> scopusForums = new ArrayList<>(scopusForumFactRepository.findAll());
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(scholardexForumFactRepository.findAll());
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            canonicalById.put(canonicalForum.getId(), canonicalForum);
        }

        Instant now = Instant.now();
        for (WosRankingView journal : journals) {
            result.markProcessed();
            upsertForumFromWos(journal, scopusForums, canonicalById, batchId, correlationId, now, result);
        }

        onboardPublicationWosLinks(batchId, correlationId, now, result);
        return result;
    }

    /**
     * Canonicalize every Scopus forum so it has exactly one canonical Scholardex forum, linked via a
     * {@code FORUM/SCOPUS} source link (the resolution surface H55.2 uses to re-point publication
     * {@code forumId} from the raw Scopus forum id to the canonical {@code sforum_…} id).
     *
     * <p>Symmetric to {@link #runWosOnboarding}. Scopus forums already folded into a canonical forum by
     * WoS onboarding (their {@code sourceId} already appears in some canonical's {@code scopusForumIds})
     * are not re-merged — only their source link is ensured. The remaining orphan Scopus forums (no
     * canonical at all) are resolved by ISSN/name against existing canonical forums (deduping orphans
     * that share an ISSN) or, failing that, materialize a new canonical forum. Run before publication
     * canonicalization so the FORUM source links exist when publications are re-pointed.
     */
    public ImportProcessingResult runScopusForumCanonicalization(String batchId, String correlationId) {
        ImportProcessingResult result = new ImportProcessingResult(20);

        List<ScopusForumFact> scopusForums = new ArrayList<>(scopusForumFactRepository.findAll());
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(scholardexForumFactRepository.findAll());
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        Map<String, String> canonicalIdByScopusForumId = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            canonicalById.put(canonicalForum.getId(), canonicalForum);
            for (String scopusForumId : safeList(canonicalForum.getScopusForumIds())) {
                String normalized = normalizeBlank(scopusForumId);
                if (normalized != null) {
                    canonicalIdByScopusForumId.put(normalized, canonicalForum.getId());
                }
            }
        }

        // Deterministic order so orphan dedup-by-ISSN (first writer wins the stored name/agg) is stable.
        scopusForums.sort(Comparator.comparing(forum -> {
            String sourceId = normalizeBlank(forum.getSourceId());
            return sourceId == null ? "" : sourceId;
        }));

        Instant now = Instant.now();
        for (ScopusForumFact scopusForum : scopusForums) {
            result.markProcessed();
            upsertForumFromScopus(scopusForum, canonicalById, canonicalIdByScopusForumId, batchId, correlationId, now, result);
        }
        return result;
    }

    private void upsertForumFromScopus(
            ScopusForumFact scopusForum,
            Map<String, ScholardexForumFact> canonicalById,
            Map<String, String> canonicalIdByScopusForumId,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        String sourceRecordId = normalizeBlank(scopusForum.getSourceId());
        if (sourceRecordId == null) {
            result.markSkipped("scopus-forum-missing-id");
            return;
        }

        LinkedHashSet<String> normalizedIssns = normalizedIssnSet(
                scopusForum.getIssn(),
                correctedScopusEIssn(scopusForum),
                null,
                null,
                null,
                null
        );
        String name = firstNonBlank(scopusForum.getPublicationName(), sourceRecordId);
        String aggregationType = firstNonBlank(scopusForum.getAggregationType(), FORUM_DEFAULT_AGG);
        String nameNormalized = normalizeName(name);
        String aggregationTypeNormalized = normalizeToken(aggregationType);
        String nameAggKey = nameNormalized + "|" + aggregationTypeNormalized;

        if (normalizedIssns.isEmpty() && hasAnyNonBlank(scopusForum.getIssn(), scopusForum.getEIssn())) {
            openConflict(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, REASON_INVALID_ISSN, List.of(), batchId, correlationId);
        }

        // Already folded into a canonical forum by WoS onboarding: do not re-merge, only ensure the
        // FORUM/SCOPUS source link so publication re-pointing can resolve this Scopus forum id.
        String linkedCanonicalId = canonicalIdByScopusForumId.get(sourceRecordId);
        if (linkedCanonicalId != null && canonicalById.containsKey(linkedCanonicalId)) {
            upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, linkedCanonicalId, REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId, true);
            resolveOpenForumAmbiguityConflict(sourceRecordId);
            result.markSkipped("scopus-forum-already-canonical sourceRecordId=" + sourceRecordId);
            return;
        }

        // Re-run idempotency: a prior canonicalization already created/linked a canonical forum for this
        // Scopus forum id; re-merge it in place and refresh the link.
        Optional<ScholardexSourceLink> existingLink = sourceLinkService
                .findByKey(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId);
        if (existingLink.isPresent()) {
            String canonicalId = normalizeBlank(existingLink.get().getCanonicalEntityId());
            if (canonicalId != null && canonicalById.containsKey(canonicalId)) {
                ScholardexForumFact target = canonicalById.get(canonicalId);
                mergeForumFromScopus(target, scopusForum, normalizedIssns, name, aggregationType, now, batchId, correlationId);
                target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
                if (persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
                    canonicalIdByScopusForumId.put(sourceRecordId, target.getId());
                    upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, target.getId(), REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId, true);
                    resolveOpenForumAmbiguityConflict(sourceRecordId);
                    result.markUpdated();
                }
                return;
            }
        }

        List<ScholardexForumFact> candidates = findCanonicalCandidates(canonicalById.values(), normalizedIssns, nameAggKey);
        if (candidates.size() > 1) {
            // H55.6: an ISSN-token match against several canonical forums is usually a forum that shares
            // only an eISSN with a sibling/continuation (e.g. European Physical Journal C vs Zeitschrift
            // für Physik C, or the mislabeled-eISSN SIAM pair). The primary (print) ISSN is the strongest
            // journal identity: if exactly one candidate carries this Scopus forum's primary ISSN, that is
            // the unambiguous match. Only fall back to a conflict when the primary ISSN cannot break the tie.
            String primaryIssn = normalizeIssn(scopusForum.getIssn());
            if (primaryIssn != null) {
                List<ScholardexForumFact> byPrimary = candidates.stream()
                        .filter(candidate -> matchesIssn(candidate, List.of(primaryIssn)))
                        .toList();
                if (byPrimary.size() == 1) {
                    candidates = byPrimary;
                }
            }
        }
        if (candidates.size() > 1) {
            String reason = normalizedIssns.isEmpty() ? REASON_AMBIGUOUS_NAME_AGG : REASON_AMBIGUOUS_ISSN;
            List<String> candidateIds = candidates.stream().map(ScholardexForumFact::getId).toList();
            upsertConflictSourceLink(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, reason, batchId, correlationId);
            openConflict(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, reason, candidateIds, batchId, correlationId);
            result.markSkipped("scopus-forum-ambiguous-candidates sourceRecordId=" + sourceRecordId);
            return;
        }

        ScholardexForumFact target = candidates.isEmpty() ? new ScholardexForumFact() : candidates.getFirst();
        boolean created = target.getId() == null;
        mergeForumFromScopus(target, scopusForum, normalizedIssns, name, aggregationType, now, batchId, correlationId);
        target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        if (!persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
            return;
        }
        canonicalById.put(target.getId(), target);
        canonicalIdByScopusForumId.put(sourceRecordId, target.getId());
        upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, target.getId(), REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId, true);
        resolveOpenForumAmbiguityConflict(sourceRecordId);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    /**
     * Fold a single Scopus forum into a canonical forum. Additive: contributes the Scopus forum id and
     * fills missing ISSN/name/aggregation, but never clears values an earlier (e.g. WoS) writer set.
     * Mints the canonical id from ISSN (or name+aggregation when ISSN-less) for new canonical forums.
     */
    private void mergeForumFromScopus(
            ScholardexForumFact target,
            ScopusForumFact scopusForum,
            LinkedHashSet<String> normalizedIssns,
            String name,
            String aggregationType,
            Instant now,
            String batchId,
            String correlationId
    ) {
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }

        LinkedHashSet<String> scopusIds = new LinkedHashSet<>(safeList(target.getScopusForumIds()));
        if (normalizeBlank(scopusForum.getSourceId()) != null) {
            scopusIds.add(scopusForum.getSourceId());
        }
        target.setScopusForumIds(new ArrayList<>(scopusIds));

        List<String> issnList = new ArrayList<>(normalizedIssns);
        String preferredIssn = issnList.isEmpty() ? null : issnList.getFirst();
        String preferredEIssn = issnList.size() > 1 ? issnList.get(1) : null;
        if (target.getIssn() == null) {
            target.setIssn(preferredIssn);
        }
        if (target.getEIssn() == null) {
            target.setEIssn(preferredEIssn);
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

        String preferredName = firstNonBlank(target.getName(), name);
        target.setName(preferredName);
        target.setNameNormalized(normalizeName(preferredName));

        String preferredAgg = firstNonBlank(target.getAggregationType(), aggregationType);
        target.setAggregationType(preferredAgg);
        target.setAggregationTypeNormalized(normalizeToken(preferredAgg));

        if (target.getId() == null) {
            String forumId = buildCanonicalForumId(target.getIssn(), target.getEIssn(), target.getAliasIssns(), target.getNameNormalized(), target.getAggregationTypeNormalized());
            target.setId(forumId);
        }
        target.setSource(SOURCE_SCOPUS);
        target.setSourceRecordId(scopusForum.getSourceId());
        target.setSourceBatchId(batchId);
        target.setSourceCorrelationId(correlationId);
        target.setUpdatedAt(now);
    }

    /**
     * Map a stage-3 WoS journal identity to the internal {@link WosRankingView} DTO consumed by forum
     * onboarding. Mirrors {@code WosProjectionBuilderService.toRankingView} field-for-field (the
     * onboarding-relevant subset), so reading identities here is equivalent to reading the projection
     * that is built 1:1 from them — minus the backwards stage-4 dependency.
     */
    private static WosRankingView toRankingView(WosJournalIdentity identity) {
        WosRankingView view = new WosRankingView();
        view.setId(identity.getId());
        view.setName(identity.getTitle());
        view.setIssn(identity.getPrimaryIssn());
        view.setEIssn(identity.getEIssn());
        view.setAlternativeIssns(identity.getAliasIssns() == null ? List.of() : new ArrayList<>(identity.getAliasIssns()));
        view.setAlternativeNames(identity.getAlternativeNames() == null ? List.of() : new ArrayList<>(identity.getAlternativeNames()));
        return view;
    }

    private void upsertForumFromWos(
            WosRankingView rankingView,
            List<ScopusForumFact> scopusForums,
            Map<String, ScholardexForumFact> canonicalById,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        String sourceRecordId = normalizeBlank(rankingView.getId());
        if (sourceRecordId == null) {
            result.markSkipped("wos-journal-missing-id");
            return;
        }

        LinkedHashSet<String> normalizedIssns = normalizedIssnSet(
                rankingView.getIssn(),
                rankingView.getEIssn(),
                rankingView.getAlternativeIssns(),
                null,
                null,
                null
        );
        String name = firstNonBlank(rankingView.getName(), sourceRecordId);
        String aggregationType = FORUM_DEFAULT_AGG;
        String nameNormalized = normalizeName(name);
        String aggregationTypeNormalized = normalizeToken(aggregationType);
        String nameAggKey = nameNormalized + "|" + aggregationTypeNormalized;

        if (normalizedIssns.isEmpty() && hasAnyNonBlank(rankingView.getIssn(), rankingView.getEIssn(), join(rankingView.getAlternativeIssns()))) {
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, REASON_INVALID_ISSN, List.of(), batchId, correlationId);
        }

        Optional<ScholardexSourceLink> existingLink = sourceLinkService
                .findByKey(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId);
        if (existingLink.isPresent()) {
            String canonicalId = normalizeBlank(existingLink.get().getCanonicalEntityId());
            if (canonicalId != null && canonicalById.containsKey(canonicalId)) {
                ScholardexForumFact target = canonicalById.get(canonicalId);
                mergeForum(target, sourceRecordId, normalizedIssns, name, nameNormalized, aggregationType, aggregationTypeNormalized, scopusForums, now, batchId, correlationId);
                target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
                if (persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
                    upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId, true);
                    result.markUpdated();
                }
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
        target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        if (!persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
            return;
        }
        canonicalById.put(target.getId(), target);
        upsertLinkedSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId, true);
        if (created) {
            result.markImported();
        } else {
            result.markUpdated();
        }
    }

    /**
     * Persist a WoS-onboarded canonical forum, recording a conflict and skipping if it collides with
     * the {@code uniq_scholardex_forum_*_id} unique index — i.e. a scopus/wos forum id this WoS journal
     * resolves to is already owned by a different canonical forum. The candidate search keys on
     * ISSN/name, not the external forum id, so two WoS journals can independently resolve to the same
     * external forum id; before the H54.2 partial unique index existed this duplicate was silently
     * tolerated. Returns true if persisted, false if a conflict was recorded and the journal skipped.
     */
    private boolean persistForumOrRecordConflict(
            ScholardexForumFact target,
            String sourceRecordId,
            String batchId,
            String correlationId,
            ImportProcessingResult result
    ) {
        try {
            scholardexForumFactRepository.save(target);
            return true;
        } catch (DuplicateKeyException ex) {
            upsertConflictSourceLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId,
                    REASON_FORUM_EXTERNAL_ID_ALREADY_LINKED, batchId, correlationId);
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId,
                    REASON_FORUM_EXTERNAL_ID_ALREADY_LINKED, List.of(), batchId, correlationId);
            result.markSkipped("wos-forum-external-id-already-linked sourceRecordId=" + sourceRecordId);
            return false;
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
            String scopusEIssn = normalizeIssn(correctedScopusEIssn(scopusPreferred));
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

    /**
     * Returns the Scopus forum's eISSN with the known SIAM misassignment corrected (see
     * {@link #SIAM_MATH_ANALYSIS_PRINT_ISSN}). Returns the raw eISSN unchanged for every other forum.
     */
    private String correctedScopusEIssn(ScopusForumFact scopusForum) {
        String rawEIssn = scopusForum.getEIssn();
        if (SIAM_MATH_ANALYSIS_PRINT_ISSN.equals(normalizeIssn(scopusForum.getIssn()))
                && SIAM_COMPUTING_EISSN.equals(normalizeIssn(rawEIssn))) {
            return SIAM_MATH_ANALYSIS_EISSN;
        }
        return rawEIssn;
    }

    private boolean matchesIssn(ScopusForumFact scopusForum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        return containsToken(issnTokens, normalizeIssn(scopusForum.getIssn()))
                || containsToken(issnTokens, normalizeIssn(correctedScopusEIssn(scopusForum)));
    }

    private boolean matchesIssn(ScholardexForumFact forum, Collection<String> issnTokens) {
        if (issnTokens == null || issnTokens.isEmpty()) {
            return false;
        }
        if (containsToken(issnTokens, normalizeIssn(forum.getIssn()))
                || containsToken(issnTokens, normalizeIssn(forum.getEIssn()))) {
            return true;
        }
        for (String alias : safeList(forum.getAliasIssns())) {
            if (containsToken(issnTokens, normalizeIssn(alias))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Null-safe membership test. A forum/source record may have no (or a check-digit-invalid) ISSN, in
     * which case {@code normalizeIssn} returns null; and the candidate token collection is often an
     * immutable {@code List.of(...)} which throws {@link NullPointerException} on {@code contains(null)}.
     * Guard the null before delegating.
     */
    private static boolean containsToken(Collection<String> tokens, String value) {
        return value != null && tokens.contains(value);
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
            if (wosId == null || CanonicalPublicationConstants.NON_WOS_ID.equalsIgnoreCase(wosId)) {
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
                    correlationId,
                    false
            );
            result.markUpdated();
        }
    }

    /**
     * @param explicitReplayAttempt forum canonicalization passes {@code true}: it only reaches a link
     *     once it has resolved a single canonical forum (incl. H55.6 primary-ISSN disambiguation), so a
     *     definitive link must override a stale CONFLICT/SKIPPED source link left by an earlier ambiguous
     *     run — otherwise the CONFLICT-&gt;LINKED transition is rejected and the forum never resolves.
     *     Publication links pass {@code false} so they do not silently override their own collision
     *     quarantines.
     */
    private void upsertLinkedSourceLink(
            ScholardexEntityType entityType,
            String source,
            String sourceRecordId,
            String canonicalEntityId,
            String reason,
            String batchId,
            String correlationId,
            boolean explicitReplayAttempt
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
                explicitReplayAttempt
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

    /**
     * Close any stale open {@code AMBIGUOUS_ISSN_MATCH}/{@code AMBIGUOUS_NAME_AGG} FORUM/SCOPUS conflict
     * for a Scopus forum that has now been unambiguously linked (e.g. via H55.6 primary-ISSN
     * disambiguation, or because a prior run folded its id into a canonical forum). Without this the
     * conflict lingers on {@code /admin/conflicts} even though the forum is resolved.
     */
    private void resolveOpenForumAmbiguityConflict(String sourceRecordId) {
        for (String reason : List.of(REASON_AMBIGUOUS_ISSN, REASON_AMBIGUOUS_NAME_AGG)) {
            scholardexIdentityConflictRepository
                    .findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                            ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, reason, STATUS_OPEN)
                    .ifPresent(conflict -> {
                        conflict.setStatus(STATUS_RESOLVED);
                        conflict.setResolvedAt(Instant.now());
                        conflict.setResolvedBy(CONFLICT_RESOLVER_SCOPUS_FORUM);
                        scholardexIdentityConflictRepository.save(conflict);
                    });
        }
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
        CanonicalObservabilityMetrics.recordConflictCreated(entityType.name(), source, reasonCode);
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
        if (!QueryNormalizationSupport.isValidIssn(compact)) {
            // H55: reject check-digit-invalid ISSNs (real source typos, e.g. Radical Philosophy
            // "0030-211X"). Treated as absent so the forum resolves by name instead of carrying a
            // malformed identity token. Genuinely ISSN-less forums already hit the same REASON_INVALID_ISSN
            // conflict path; this folds typos into that behaviour.
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
        // First 12 bytes == first 24 lowercase-hex chars: byte-identical to the shared implementation.
        return ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash(raw);
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
