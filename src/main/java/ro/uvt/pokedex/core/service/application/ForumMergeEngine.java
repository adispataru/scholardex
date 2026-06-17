package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.BuilderVersion;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * H66B M1c — the source-agnostic forum find-or-create/merge core, extracted from
 * {@code WosScholardexOnboardingService}. Holds the per-record identity resolution (candidate lookup via the
 * O(1) {@link ForumIdentityIndex} token index, {@link ForumMergeSafetyRule} cross-journal guard, save-or-
 * conflict, source-link command building) plus the H57 ISSN token-hygiene and canonical-id minting.
 *
 * <p>Per-run scratch state (the primary-ISSN index, the in-memory canonical maps, the accumulated source-link
 * commands) lives in a per-invocation {@link Context}, not in singleton fields — the engine itself is a
 * stateless Spring bean. The two onboarding entry methods build a Context via {@link #startWosRun} /
 * {@link #startScopusRun}, loop their source records through {@link #upsertForumFromWos} /
 * {@link #upsertForumFromScopus}, then {@link #flush}. M2 collapses the two upsert methods into one
 * {@code ingest(ForumSourceRecord)}.
 */
@Service
@RequiredArgsConstructor
public class ForumMergeEngine {

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

    private static final String REASON_AMBIGUOUS_ISSN = "AMBIGUOUS_ISSN_MATCH";
    private static final String REASON_AMBIGUOUS_NAME_AGG = "AMBIGUOUS_NAME_AGG_MATCH";
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
    private final ForumMergeSafetyRule mergeSafetyRule;
    private final ConflictRecorder conflictRecorder;

    /**
     * Per-invocation scratch state for one forum-build run. Owns the index + maps (loaded once, mutated
     * incrementally) and the accumulated source-link commands flushed at the end — never a singleton field,
     * so concurrent runs cannot bleed into each other.
     */
    public static final class Context {
        // H57 Layer 2 (token hygiene): all primary (print) ISSNs known across sources; an eISSN/alias that
        // equals a *different* journal's primary print ISSN is a misassigned-eISSN source error and is
        // dropped from a forum's identity tokens so it cannot bridge two distinct journals.
        Set<String> primaryIssnIndex = Set.of();
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        ForumIdentityIndex forumIndex;
        ScopusForumIndex scopusForumIndex;
        // Scopus-run only: canonical id keyed by an already-folded scopus forum id (the already-canonical check).
        Map<String, String> canonicalIdByScopusForumId = new LinkedHashMap<>();
        // Scopus-run only: keys of OPEN forum conflicts preloaded once (per-row resolve hits the DB only on a hit).
        Set<String> openForumConflictKeys = Set.of();
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks = new HashMap<>();
        final List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = new ArrayList<>();
        // Scopus-run only: the loaded + deterministically sorted scopus forums to iterate.
        List<ScopusForumFact> scopusForums = List.of();

        public List<ScopusForumFact> scopusForums() {
            return scopusForums;
        }
    }

    /**
     * Build the per-run context for WoS forum onboarding: load the Scopus forums (for the primary-ISSN index
     * + the scopus-enrichment index), the canonical forums (into the ISSN/name token index), and the existing
     * FORUM/WOS source links (preloaded for in-memory re-run idempotency + the batch flush).
     */
    public Context startWosRun(List<WosRankingView> journals) {
        List<ScopusForumFact> scopusForums = new ArrayList<>(scopusForumFactRepository.findAll());
        Context ctx = new Context();
        ctx.primaryIssnIndex = buildPrimaryIssnIndex(scopusForums);
        ctx.canonicalById = loadCanonicalById();
        ctx.forumIndex = new ForumIdentityIndex(ctx.canonicalById);
        ctx.scopusForumIndex = new ScopusForumIndex(scopusForums);
        ctx.existingForumLinks = loadForumSourceLinks(SOURCE_WOS, journals.stream().map(WosRankingView::getId).toList());
        return ctx;
    }

    /**
     * Build the per-run context for Scopus forum canonicalization: load + deterministically sort the Scopus
     * forums, the canonical forums (token index + already-folded lookup), the OPEN forum-conflict keys, and
     * the existing FORUM/SCOPUS source links. The sorted scopus forums are exposed via {@link Context#scopusForums()}.
     */
    public Context startScopusRun() {
        List<ScopusForumFact> scopusForums = new ArrayList<>(scopusForumFactRepository.findAll());
        Context ctx = new Context();
        ctx.primaryIssnIndex = buildPrimaryIssnIndex(scopusForums);
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        Map<String, String> canonicalIdByScopusForumId = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : scholardexForumFactRepository.findAll()) {
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
        ctx.canonicalById = canonicalById;
        ctx.canonicalIdByScopusForumId = canonicalIdByScopusForumId;
        ctx.forumIndex = new ForumIdentityIndex(canonicalById);
        ctx.openForumConflictKeys = loadOpenForumConflictKeys();
        ctx.existingForumLinks = loadForumSourceLinks(
                SOURCE_SCOPUS, scopusForums.stream().map(ScopusForumFact::getSourceId).toList());
        ctx.scopusForums = scopusForums;
        return ctx;
    }

    /** Flush the accumulated source-link writes for one run in a single batch upsert. */
    public void flush(Context ctx) {
        sourceLinkService.batchUpsertWithState(ctx.linkCommands, ctx.existingForumLinks);
    }

    private Map<String, ScholardexForumFact> loadCanonicalById() {
        Map<String, ScholardexForumFact> canonicalById = new LinkedHashMap<>();
        for (ScholardexForumFact canonicalForum : scholardexForumFactRepository.findAll()) {
            canonicalById.put(canonicalForum.getId(), canonicalForum);
        }
        return canonicalById;
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

    public void upsertForumFromScopus(
            ScopusForumFact scopusForum,
            Context ctx,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        Map<String, ScholardexForumFact> canonicalById = ctx.canonicalById;
        Map<String, String> canonicalIdByScopusForumId = ctx.canonicalIdByScopusForumId;
        ForumIdentityIndex forumIndex = ctx.forumIndex;
        Set<String> openForumConflictKeys = ctx.openForumConflictKeys;
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks = ctx.existingForumLinks;
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = ctx.linkCommands;

        String sourceRecordId = normalizeBlank(scopusForum.getSourceId());
        if (sourceRecordId == null) {
            result.markSkipped("scopus-forum-missing-id");
            return;
        }

        LinkedHashSet<String> normalizedIssns = normalizedIssnSet(
                ctx.primaryIssnIndex,
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

    public void upsertForumFromWos(
            WosRankingView rankingView,
            Context ctx,
            String batchId,
            String correlationId,
            Instant now,
            ImportProcessingResult result
    ) {
        Map<String, ScholardexForumFact> canonicalById = ctx.canonicalById;
        ForumIdentityIndex forumIndex = ctx.forumIndex;
        ScopusForumIndex scopusForumIndex = ctx.scopusForumIndex;
        Map<ScholardexSourceLinkService.SourceLinkKey, ScholardexSourceLink> existingForumLinks = ctx.existingForumLinks;
        List<ScholardexSourceLinkService.SourceLinkUpsertCommand> linkCommands = ctx.linkCommands;

        String sourceRecordId = normalizeBlank(rankingView.getId());
        if (sourceRecordId == null) {
            result.markSkipped("wos-journal-missing-id");
            return;
        }

        LinkedHashSet<String> normalizedIssns = normalizedIssnSet(
                ctx.primaryIssnIndex,
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
            conflictRecorder.markConflictLink(ScholardexEntityType.FORUM, SOURCE_WOS, sourceRecordId,
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
        conflictRecorder.openConflict(entityType, source, sourceRecordId, reasonCode, candidateIds, batchId, correlationId);
    }

    private LinkedHashSet<String> normalizedIssnSet(
            Set<String> primaryIssnIndex,
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
        addSecondaryIssn(primaryIssnIndex, out, primaryNorm, eIssn);
        addSecondaryIssn(primaryIssnIndex, out, primaryNorm, rankingIssn);
        addSecondaryIssn(primaryIssnIndex, out, primaryNorm, rankingEIssn);
        for (String token : safeList(aliasIssns)) {
            addSecondaryIssn(primaryIssnIndex, out, primaryNorm, token);
        }
        for (String token : safeList(rankingAliases)) {
            addSecondaryIssn(primaryIssnIndex, out, primaryNorm, token);
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
    private void addSecondaryIssn(Set<String> primaryIssnIndex, LinkedHashSet<String> out, String primaryNorm, String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized != null && !isCrossJournalToken(primaryIssnIndex, primaryNorm, normalized)) {
            out.add(normalized);
        }
    }

    /**
     * True when {@code token} is a primary print ISSN of some <i>other</i> journal — so adopting it as
     * this forum's identity token would bridge two distinct journals. Only fires when this forum has its
     * own primary (so a journal known only by one ISSN never loses it).
     */
    private boolean isCrossJournalToken(Set<String> primaryIssnIndex, String primaryNorm, String token) {
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

    private static String normalizeIssn(String rawIssn) {
        return ForumIdentityNormalization.normalizeIssn(rawIssn);
    }

    private static String normalizeName(String rawName) {
        return ForumIdentityNormalization.normalizeName(rawName);
    }

    private static String normalizeToken(String rawValue) {
        return ForumIdentityNormalization.normalizeToken(rawValue);
    }

    private static String normalizeBlank(String value) {
        return ForumIdentityNormalization.normalizeBlank(value);
    }

    private static String correctedScopusEIssn(ScopusForumFact scopusForum) {
        return ForumIdentityNormalization.correctedScopusEIssn(scopusForum);
    }

    private static boolean matchesIssn(ScholardexForumFact forum, Collection<String> issnTokens) {
        return ForumIdentityNormalization.matchesIssn(forum, issnTokens);
    }

    private static String shortHash(String raw) {
        // First 12 bytes == first 24 lowercase-hex chars: byte-identical to the shared implementation.
        return ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash(raw);
    }

    private static String firstNonBlank(String... values) {
        return ForumIdentityNormalization.firstNonBlank(values);
    }

    private static boolean hasAnyNonBlank(String... values) {
        for (String value : values) {
            if (normalizeBlank(value) != null) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> values) {
        return values == null ? null : String.join(",", values);
    }

    private static List<String> safeList(List<String> values) {
        return ForumIdentityNormalization.safeList(values);
    }
}
