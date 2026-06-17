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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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

    private static final String REASON_FORUM_EXTERNAL_ID_ALREADY_LINKED = "FORUM_EXTERNAL_ID_ALREADY_LINKED";
    // H57: an incoming forum matched a canonical candidate only via a shared eISSN/alias while carrying a
    // *different* primary print ISSN (a misassigned-eISSN cross-journal bridge) — not auto-merged.
    private static final String REASON_FORUM_CROSS_JOURNAL_ISSN = "FORUM_CROSS_JOURNAL_ISSN";

    private final WosJournalIdentityRepository journalIdentityRepository;
    private final ScopusForumFactRepository scopusForumFactRepository;
    private final ScholardexForumFactRepository scholardexForumFactRepository;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ForumMergeSafetyRule mergeSafetyRule;

    // H57 Layer 2 (token hygiene): the set of all primary (print) ISSNs known across sources, rebuilt at
    // the start of each onboarding/canonicalization run. An eISSN/alias that equals a *different*
    // journal's primary print ISSN is a misassigned-eISSN source error and is dropped from a forum's
    // identity tokens so it cannot bridge two distinct journals. Non-final: per-run scratch state (the
    // builder runs single-threaded per invocation).
    private Set<String> primaryIssnIndex = Set.of();

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
        primaryIssnIndex = buildPrimaryIssnIndex(scopusForums);
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(scholardexForumFactRepository.findAll());
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            canonicalById.put(canonicalForum.getId(), canonicalForum);
        }
        CanonicalForumIndex forumIndex = new CanonicalForumIndex(canonicalById);
        ScopusForumIndex scopusForumIndex = new ScopusForumIndex(scopusForums);
        // H66: same preload + batch as the Scopus path — preload existing FORUM/WOS source links (in-memory
        // re-run-idempotency check instead of per-row findByKey) and accumulate link writes for one flush.
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks = loadForumSourceLinks(
                SOURCE_WOS, journals.stream().map(WosRankingView::getId).toList());
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new ArrayList<>();

        Instant now = Instant.now();
        for (WosRankingView journal : journals) {
            result.markProcessed();
            upsertForumFromWos(journal, scopusForumIndex, canonicalById, forumIndex, existingForumLinks, linkCommands, batchId, correlationId, now, result);
        }
        sourceLinkService.batchUpsertWithState(linkCommands, existingForumLinks);

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
        primaryIssnIndex = buildPrimaryIssnIndex(scopusForums);
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

        CanonicalForumIndex forumIndex = new CanonicalForumIndex(canonicalById);
        // H66: preload the OPEN forum-conflict keys once, so the per-row ambiguity-resolve does a DB read
        // only when a conflict actually exists for that record. On a from-empty rebuild the set is empty,
        // turning ~60k (2 reasons × n) always-miss lookups into in-memory checks.
        Set<String> openForumConflictKeys = loadOpenForumConflictKeys();
        // H66: preload existing FORUM source links once (keyed by SourceLinkKey) — serves both the per-row
        // re-run-idempotency lookup (in-memory instead of a DB findByKey) AND the batchUpsertWithState
        // preloadedByKey below (so the batch needs no per-command fallback lookups). Empty on a fresh build.
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks = loadForumSourceLinks(
                SOURCE_SCOPUS, scopusForums.stream().map(ScopusForumFact::getSourceId).toList());
        // H66: accumulate source-link writes and flush once after the loop (nothing reads links mid-loop;
        // the in-memory canonicalIdByScopusForumId map handles the already-canonical check).
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new ArrayList<>();
        Instant now = Instant.now();
        for (ScopusForumFact scopusForum : scopusForums) {
            result.markProcessed();
            upsertForumFromScopus(scopusForum, canonicalById, canonicalIdByScopusForumId, forumIndex, openForumConflictKeys, existingForumLinks, linkCommands, batchId, correlationId, now, result);
        }
        sourceLinkService.batchUpsertWithState(linkCommands, existingForumLinks);
        return result;
    }

    /** H66: existing FORUM source links for the given source, keyed by SourceLinkKey (for preload-and-skip + batch). */
    private Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> loadForumSourceLinks(String source, Collection<String> sourceRecordIds) {
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> byKey = new HashMap<>();
        for (ScholardexSourceLink link : sourceLinkService.findByEntityTypeAndSourceRecordIds(ScholardexEntityType.FORUM, sourceRecordIds)) {
            if (source.equals(link.getSource())) {
                byKey.put(ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.FORUM, link.getSource(), link.getSourceRecordId()), link);
            }
        }
        return byKey;
    }

    /** H66: keys ("source|sourceRecordId|reason") of all OPEN forum identity conflicts, for preload-and-skip. */
    private Set<String> loadOpenForumConflictKeys() {
        Set<String> keys = new HashSet<>();
        for (ScholardexIdentityConflict conflict : scholardexIdentityConflictRepository
                .findByEntityTypeAndStatus(ScholardexEntityType.FORUM, STATUS_OPEN)) {
            keys.add(openConflictKey(conflict.getIncomingSource(), conflict.getIncomingSourceRecordId(), conflict.getReasonCode()));
        }
        return keys;
    }

    private static String openConflictKey(String source, String sourceRecordId, String reason) {
        return source + "|" + sourceRecordId + "|" + reason;
    }

    /** H66: LINKED source-link command (replaces per-row upsertLinkedSourceLink for batch flush). */
    private static ScholardexSourceLinkService.SourceLinkUpsertCommand linkedCommand(
            String source, String sourceRecordId, String canonicalEntityId, String reason, String batchId, String correlationId) {
        return new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.FORUM, source, sourceRecordId, canonicalEntityId,
                ScholardexSourceLinkService.STATE_LINKED, reason, null, batchId, correlationId, true);
    }

    /** H66: CONFLICT source-link command (replaces per-row upsertConflictSourceLink for batch flush). */
    private static ScholardexSourceLinkService.SourceLinkUpsertCommand conflictCommand(
            String source, String sourceRecordId, String reason, String batchId, String correlationId) {
        return new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                ScholardexEntityType.FORUM, source, sourceRecordId, null,
                ScholardexSourceLinkService.STATE_CONFLICT, reason, null, batchId, correlationId, false);
    }

    private void upsertForumFromScopus(
            ScopusForumFact scopusForum,
            Map<String, ScholardexForumFact> canonicalById,
            Map<String, String> canonicalIdByScopusForumId,
            CanonicalForumIndex forumIndex,
            Set<String> openForumConflictKeys,
            Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks,
            List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands,
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
            linkCommands.add(linkedCommand(SOURCE_SCOPUS, sourceRecordId, linkedCanonicalId, REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId));
            resolveOpenForumAmbiguityConflict(sourceRecordId, openForumConflictKeys);
            result.markSkipped("scopus-forum-already-canonical sourceRecordId=" + sourceRecordId);
            return;
        }

        // Re-run idempotency: a prior canonicalization already created/linked a canonical forum for this
        // Scopus forum id; re-merge it in place and refresh the link.
        Optional<ScholardexSourceLink> existingLink = Optional.ofNullable(
                existingForumLinks.get(ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId)));
        if (existingLink.isPresent()) {
            String canonicalId = normalizeBlank(existingLink.get().getCanonicalEntityId());
            if (canonicalId != null && canonicalById.containsKey(canonicalId)) {
                ScholardexForumFact target = canonicalById.get(canonicalId);
                mergeForumFromScopus(target, scopusForum, normalizedIssns, name, aggregationType, now, batchId, correlationId);
                target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
                if (persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
                    forumIndex.put(target); // re-index: the merge may have added ISSN tokens/aliases
                    canonicalIdByScopusForumId.put(sourceRecordId, target.getId());
                    linkCommands.add(linkedCommand(SOURCE_SCOPUS, sourceRecordId, target.getId(), REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId));
                    resolveOpenForumAmbiguityConflict(sourceRecordId, openForumConflictKeys);
                    result.markUpdated();
                }
                return;
            }
        }

        List<ScholardexForumFact> candidates = forumIndex.findCandidates(normalizedIssns, nameAggKey);
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
            linkCommands.add(conflictCommand(SOURCE_SCOPUS, sourceRecordId, reason, batchId, correlationId));
            openConflict(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, reason, candidateIds, batchId, correlationId);
            result.markSkipped("scopus-forum-ambiguous-candidates sourceRecordId=" + sourceRecordId);
            return;
        }

        // H57: only fold into a candidate if the safe-merge rule allows it. A candidate matched solely via
        // a shared eISSN/alias while carrying a different primary print ISSN (names not matching) is a
        // different journal — mint a separate forum and flag the bridge instead of merging.
        ScholardexForumFact target;
        if (candidates.isEmpty()) {
            target = new ScholardexForumFact();
        } else if (mergeSafetyRule.isSafeToMerge(scopusForum.getIssn(), name, candidates.getFirst())) {
            target = candidates.getFirst();
        } else {
            openConflict(ScholardexEntityType.FORUM, SOURCE_SCOPUS, sourceRecordId, REASON_FORUM_CROSS_JOURNAL_ISSN,
                    List.of(candidates.getFirst().getId()), batchId, correlationId);
            target = new ScholardexForumFact();
        }
        boolean created = target.getId() == null;
        mergeForumFromScopus(target, scopusForum, normalizedIssns, name, aggregationType, now, batchId, correlationId);
        target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        if (!persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
            return;
        }
        forumIndex.put(target);
        canonicalIdByScopusForumId.put(sourceRecordId, target.getId());
        linkCommands.add(linkedCommand(SOURCE_SCOPUS, sourceRecordId, target.getId(), REASON_SCOPUS_FORUM_ONBOARDING, batchId, correlationId));
        resolveOpenForumAmbiguityConflict(sourceRecordId, openForumConflictKeys);
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

        // H66 A2: C-scalars from the Scopus/CiteScore forum — additive, never clears a prior writer's value.
        target.setForumType(firstNonBlank(target.getForumType(), scopusForum.getForumType()));
        LinkedHashSet<String> asjc = new LinkedHashSet<>(safeList(target.getAsjc()));
        asjc.addAll(safeList(scopusForum.getAsjc()));
        target.setAsjc(new ArrayList<>(asjc));

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
            ScopusForumIndex scopusForumIndex,
            Map<String, ScholardexForumFact> canonicalById,
            CanonicalForumIndex forumIndex,
            Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks,
            List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands,
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

        Optional<ScholardexSourceLink> existingLink = Optional.ofNullable(
                existingForumLinks.get(ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId)));
        if (existingLink.isPresent()) {
            String canonicalId = normalizeBlank(existingLink.get().getCanonicalEntityId());
            if (canonicalId != null && canonicalById.containsKey(canonicalId)) {
                ScholardexForumFact target = canonicalById.get(canonicalId);
                mergeForum(target, sourceRecordId, normalizedIssns, name, nameNormalized, aggregationType, aggregationTypeNormalized, scopusForumIndex, now, batchId, correlationId);
                target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
                if (persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
                    forumIndex.put(target); // re-index: the merge may have added ISSN tokens/aliases
                    linkCommands.add(linkedCommand(SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId));
                    result.markUpdated();
                }
                return;
            }
        }

        List<ScholardexForumFact> candidates = forumIndex.findCandidates(normalizedIssns, nameAggKey);
        if (candidates.size() > 1) {
            String reason = normalizedIssns.isEmpty() ? REASON_AMBIGUOUS_NAME_AGG : REASON_AMBIGUOUS_ISSN;
            List<String> candidateIds = candidates.stream().map(ScholardexForumFact::getId).toList();
            linkCommands.add(conflictCommand(SOURCE_WOS, sourceRecordId, reason, batchId, correlationId));
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, reason, candidateIds, batchId, correlationId);
            result.markSkipped("wos-forum-ambiguous-candidates sourceRecordId=" + sourceRecordId);
            return;
        }

        // H57: same safe-merge guard as the Scopus path — don't bridge two distinct journals that share
        // only an eISSN/alias.
        ScholardexForumFact target;
        if (candidates.isEmpty()) {
            target = new ScholardexForumFact();
        } else if (mergeSafetyRule.isSafeToMerge(rankingView.getIssn(), name, candidates.getFirst())) {
            target = candidates.getFirst();
        } else {
            openConflict(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId, REASON_FORUM_CROSS_JOURNAL_ISSN,
                    List.of(candidates.getFirst().getId()), batchId, correlationId);
            target = new ScholardexForumFact();
        }
        boolean created = target.getId() == null;
        mergeForum(target, sourceRecordId, normalizedIssns, name, nameNormalized, aggregationType, aggregationTypeNormalized, scopusForumIndex, now, batchId, correlationId);
        target.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        if (!persistForumOrRecordConflict(target, sourceRecordId, batchId, correlationId, result)) {
            return;
        }
        forumIndex.put(target);
        linkCommands.add(linkedCommand(SOURCE_WOS, sourceRecordId, target.getId(), REASON_WOS_FORUM_ONBOARDING, batchId, correlationId));
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
            ScopusForumIndex scopusForumIndex,
            Instant now,
            String batchId,
            String correlationId
    ) {
        if (target.getCreatedAt() == null) {
            target.setCreatedAt(now);
        }

        List<ScopusForumFact> scopusCandidates = scopusForumIndex.findCandidates(normalizedIssns, wosNameNormalized, defaultAggregationTypeNormalized);
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

    /**
     * H66 perf: ISSN-token + name|agg index over Scopus forums for O(1) candidate lookup during WoS
     * onboarding. Mirrors {@link CanonicalForumIndex}; replaces the prior linear scan over all scopus forums
     * per WoS journal — which was O(n²) (≈26.9k journals × 29.8k scopus forums ≈ 800M comparisons, ~16 min),
     * invisible until a real CiteScore+MJL run (the scopus-forum list is empty in MJL-only tests).
     */
    private final class ScopusForumIndex {
        private final Map<String, List<ScopusForumFact>> byIssnToken = new HashMap<>();
        private final Map<String, List<ScopusForumFact>> byNameAgg = new HashMap<>();

        ScopusForumIndex(List<ScopusForumFact> scopusForums) {
            for (ScopusForumFact scopusForum : scopusForums) {
                for (String token : scopusIssnTokens(scopusForum)) {
                    byIssnToken.computeIfAbsent(token, k -> new ArrayList<>()).add(scopusForum);
                }
                byNameAgg.computeIfAbsent(scopusNameAggKey(scopusForum), k -> new ArrayList<>()).add(scopusForum);
            }
        }

        /** Same result as the old linear scan + matchesIssn: ISSN-token matches, falling back to name|agg. */
        List<ScopusForumFact> findCandidates(Collection<String> issnTokens, String nameNormalized, String aggregationTypeNormalized) {
            LinkedHashSet<ScopusForumFact> issnMatches = new LinkedHashSet<>();
            if (issnTokens != null) {
                for (String token : issnTokens) {
                    List<ScopusForumFact> hits = byIssnToken.get(token);
                    if (hits != null) {
                        for (ScopusForumFact scopusForum : hits) {
                            if (matchesIssn(scopusForum, issnTokens)) {
                                issnMatches.add(scopusForum);
                            }
                        }
                    }
                }
            }
            if (!issnMatches.isEmpty() || nameNormalized == null) {
                return new ArrayList<>(issnMatches);
            }
            List<ScopusForumFact> nameHits = byNameAgg.get(nameNormalized + "|" + normalizeToken(aggregationTypeNormalized));
            return nameHits == null ? new ArrayList<>() : new ArrayList<>(nameHits);
        }
    }

    private List<String> scopusIssnTokens(ScopusForumFact scopusForum) {
        return ForumIdentityNormalization.scopusIssnTokens(scopusForum);
    }

    private String scopusNameAggKey(ScopusForumFact scopusForum) {
        return ForumIdentityNormalization.scopusNameAggKey(scopusForum);
    }

    /**
     * H66: incremental index over the canonical-forum-by-id map giving O(1) candidate lookup during bulk
     * forum canonicalization. The prior {@code findCanonicalCandidates} linear-scanned every canonical forum
     * per source row, so onboarding 29.7k Scopus forums was O(n²) (~5.8 min measured). Forums are indexed by
     * their ISSN tokens (issn/eIssn/aliases, normalized) and by name|agg key, updated incrementally as forums
     * are created or merged. Merges only add tokens, so re-indexing is idempotent and no stale-entry removal
     * is needed. The id map is shared with the caller, so {@code containsKey}/{@code get} on it still observe
     * inserts made through this index.
     */
    private final class CanonicalForumIndex {
        private final Map<String, ScholardexForumFact> byId;
        private final Map<String, Set<String>> issnTokenToIds = new HashMap<>();
        private final Map<String, Set<String>> nameAggToIds = new HashMap<>();

        CanonicalForumIndex(Map<String, ScholardexForumFact> byId) {
            this.byId = byId;
            for (ScholardexForumFact forum : byId.values()) {
                indexTokens(forum);
            }
        }

        /** Insert/refresh a forum: store it in the id map and (re)index its current tokens. Idempotent. */
        void put(ScholardexForumFact forum) {
            if (forum == null || forum.getId() == null) {
                return;
            }
            byId.put(forum.getId(), forum);
            indexTokens(forum);
        }

        private void indexTokens(ScholardexForumFact forum) {
            String id = forum.getId();
            if (id == null) {
                return;
            }
            for (String token : issnTokensOf(forum)) {
                issnTokenToIds.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(id);
            }
            nameAggToIds.computeIfAbsent(nameAggKeyOf(forum), k -> new LinkedHashSet<>()).add(id);
        }

        /**
         * Canonical forums sharing ≥1 ISSN token with {@code issnTokens}; when ISSN-less, those matching the
         * {@code nameAggKey}. Same result as the old per-row linear scan + {@code matchesIssn}, but via the
         * index. Id-sorted for deterministic candidate order (order only affects the reported candidate list
         * of an ambiguity conflict). The {@code matchesIssn} re-check is a redundant exactness guard over the
         * tiny candidate set.
         */
        List<ScholardexForumFact> findCandidates(Collection<String> issnTokens, String nameAggKey) {
            boolean byIssn = issnTokens != null && !issnTokens.isEmpty();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (byIssn) {
                for (String token : issnTokens) {
                    Set<String> hits = issnTokenToIds.get(token);
                    if (hits != null) {
                        ids.addAll(hits);
                    }
                }
            } else {
                Set<String> hits = nameAggToIds.get(nameAggKey);
                if (hits != null) {
                    ids.addAll(hits);
                }
            }
            List<ScholardexForumFact> candidates = new ArrayList<>();
            for (String id : ids) {
                ScholardexForumFact forum = byId.get(id);
                if (forum == null) {
                    continue;
                }
                if (byIssn && !matchesIssn(forum, issnTokens)) {
                    continue;
                }
                candidates.add(forum);
            }
            candidates.sort(Comparator.comparing(ScholardexForumFact::getId));
            return candidates;
        }
    }

    private List<String> issnTokensOf(ScholardexForumFact forum) {
        return ForumIdentityNormalization.issnTokensOf(forum);
    }

    private String nameAggKeyOf(ScholardexForumFact forum) {
        return ForumIdentityNormalization.nameAggKeyOf(forum);
    }

    /**
     * Returns the Scopus forum's eISSN with the known SIAM misassignment corrected (see
     * {@link #SIAM_MATH_ANALYSIS_PRINT_ISSN}). Returns the raw eISSN unchanged for every other forum.
     */
    private String correctedScopusEIssn(ScopusForumFact scopusForum) {
        return ForumIdentityNormalization.correctedScopusEIssn(scopusForum);
    }

    private boolean matchesIssn(ScopusForumFact scopusForum, Collection<String> issnTokens) {
        return ForumIdentityNormalization.matchesIssn(scopusForum, issnTokens);
    }

    private boolean matchesIssn(ScholardexForumFact forum, Collection<String> issnTokens) {
        return ForumIdentityNormalization.matchesIssn(forum, issnTokens);
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
    private void resolveOpenForumAmbiguityConflict(String sourceRecordId, Set<String> openForumConflictKeys) {
        for (String reason : List.of(REASON_AMBIGUOUS_ISSN, REASON_AMBIGUOUS_NAME_AGG)) {
            // H66: skip the DB lookup unless a matching OPEN conflict was preloaded for this record.
            if (!openForumConflictKeys.contains(openConflictKey(SOURCE_SCOPUS, sourceRecordId, reason))) {
                continue;
            }
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
        // The forum's own primary print ISSN (arg 1) is never filtered. Every other token (eISSN,
        // aliases, ranking ISSNs) is a "secondary" and is dropped if it is a *different* journal's
        // primary print ISSN — a misassigned-eISSN cross-journal bridge (H57 Layer 2).
        String primaryNorm = normalizeIssn(primaryIssn);
        if (primaryNorm != null) {
            out.add(primaryNorm);
        }
        addSecondaryIssn(out, primaryNorm, eIssn);
        addSecondaryIssn(out, primaryNorm, rankingIssn);
        addSecondaryIssn(out, primaryNorm, rankingEIssn);
        for (String token : safeList(aliasIssns)) {
            addSecondaryIssn(out, primaryNorm, token);
        }
        for (String token : safeList(rankingAliases)) {
            addSecondaryIssn(out, primaryNorm, token);
        }
        return out;
    }

    private void addIssn(LinkedHashSet<String> out, String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized != null) {
            out.add(normalized);
        }
    }

    /** Add a non-primary ISSN token unless it is a different journal's primary print ISSN (H57). */
    private void addSecondaryIssn(LinkedHashSet<String> out, String primaryNorm, String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized != null && !isCrossJournalToken(primaryNorm, normalized)) {
            out.add(normalized);
        }
    }

    /**
     * True when {@code token} is a primary print ISSN of some <i>other</i> journal — so adopting it as
     * this forum's identity token would bridge two distinct journals. Only fires when this forum has its
     * own primary (so a journal known only by one ISSN never loses it).
     */
    private boolean isCrossJournalToken(String primaryNorm, String token) {
        return primaryNorm != null && !token.equals(primaryNorm) && primaryIssnIndex.contains(token);
    }

    /** Builds the set of all primary (print) ISSNs across Scopus forums + WoS journal identities. */
    private Set<String> buildPrimaryIssnIndex(List<ScopusForumFact> scopusForums) {
        Set<String> index = new HashSet<>();
        for (ScopusForumFact sf : scopusForums) {
            String p = normalizeIssn(sf.getIssn());
            if (p != null) {
                index.add(p);
            }
        }
        for (WosJournalIdentity id : journalIdentityRepository.findAll()) {
            String p = normalizeIssn(id.getPrimaryIssn());
            if (p != null) {
                index.add(p);
            }
        }
        return index;
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
        return ForumIdentityNormalization.normalizeIssn(rawIssn);
    }

    private String normalizeName(String rawName) {
        return ForumIdentityNormalization.normalizeName(rawName);
    }

    private String normalizeToken(String rawValue) {
        return ForumIdentityNormalization.normalizeToken(rawValue);
    }

    private String normalizeBlank(String value) {
        return ForumIdentityNormalization.normalizeBlank(value);
    }

    private String shortHash(String raw) {
        // First 12 bytes == first 24 lowercase-hex chars: byte-identical to the shared implementation.
        return ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash(raw);
    }

    private String firstNonBlank(String... values) {
        return ForumIdentityNormalization.firstNonBlank(values);
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
        return ForumIdentityNormalization.safeList(values);
    }
}
