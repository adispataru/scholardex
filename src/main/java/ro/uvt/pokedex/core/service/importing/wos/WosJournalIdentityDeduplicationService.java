package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosCoverageFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.importing.BuilderVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * H66B M9: post-build deduplication of {@code wos.journal_identity} records.
 *
 * <p>WoS emits two source rows for the same journal with different ISSN completeness (one print-only, one
 * print+eISSN). Because they carry different ISSN-token sets they hash to different {@code identityKey}s and
 * land in different in-chunk resolution caches, and {@link WosFactBuilderService}'s candidate prefetch is a
 * chunk-start snapshot that is never updated with mid-chunk creates — so two same-journal rows in one chunk
 * both create a fresh identity. The result is a duplicate identity that then matches multiple forum
 * candidates at WoS onboarding and quarantines as {@code AMBIGUOUS_ISSN_MATCH} instead of linking.
 *
 * <p>This pass runs after fact-building and before projections/forum onboarding. It groups identities that
 * share an ISSN token <em>and</em> the same normalized title (the title guard keeps journals that merely
 * share an eISSN apart), merges each group into one canonical identity (union of ISSN tokens / aliases /
 * alternative names), repoints the losers' metric/category/coverage facts to the winner — dropping facts that
 * would collide on the winner's unique key, since the duplicates describe the same journal — and deletes the
 * losers. After this pass each real journal has exactly one identity carrying the full ISSN set, so forum
 * onboarding sees a single candidate and links cleanly.
 */
@Service
public class WosJournalIdentityDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(WosJournalIdentityDeduplicationService.class);

    private final WosJournalIdentityRepository journalIdentityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosCoverageFactRepository coverageFactRepository;

    public WosJournalIdentityDeduplicationService(
            WosJournalIdentityRepository journalIdentityRepository,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            WosCoverageFactRepository coverageFactRepository
    ) {
        this.journalIdentityRepository = journalIdentityRepository;
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.coverageFactRepository = coverageFactRepository;
    }

    public WosIdentityDedupResult deduplicate() {
        long startedAt = System.nanoTime();
        List<WosJournalIdentity> all = journalIdentityRepository.findAll();
        MergeCounts issnPass = mergeGroups(groupDuplicates(all));

        // Pass 2 — ISSN succession: the same journal under identical full titles but fully disjoint ISSN
        // sets (the journal changed its ISSN record, e.g. Current Psychology 0737-8262 → 1046-1310), which
        // pass 1 can never see. Same-title-only is unsafe for homonyms, so a group merges only when its
        // members' metric facts never disagree on the same (year, metricType) — succession records cover
        // complementary year ranges; genuinely distinct same-named journals conflict and stay quarantined.
        List<WosJournalIdentity> remaining = all.stream()
                .filter(identity -> !issnPass.removedIds().contains(identity.getId()))
                .toList();
        List<List<WosJournalIdentity>> titleGroups = groupByNormalizedTitle(remaining);
        List<List<WosJournalIdentity>> mergeable = new ArrayList<>();
        int successionQuarantined = 0;
        for (List<WosJournalIdentity> group : titleGroups) {
            if (hasConflictingMetrics(group)) {
                successionQuarantined++;
            } else {
                mergeable.add(group);
            }
        }
        MergeCounts titlePass = mergeGroups(mergeable);

        WosIdentityDedupResult result = new WosIdentityDedupResult(
                all.size(),
                issnPass.groupsMerged() + titlePass.groupsMerged(),
                issnPass.identitiesMerged() + titlePass.identitiesMerged(),
                issnPass.metricRepointed() + titlePass.metricRepointed(),
                issnPass.metricDropped() + titlePass.metricDropped(),
                issnPass.categoryRepointed() + titlePass.categoryRepointed(),
                issnPass.categoryDropped() + titlePass.categoryDropped(),
                issnPass.coverageRepointed() + titlePass.coverageRepointed(),
                issnPass.coverageDropped() + titlePass.coverageDropped()
        );
        log.info("WoS journal-identity dedup complete: identities={} groupsMerged={} (issn={}, titleSuccession={}, "
                        + "successionQuarantined={}) identitiesMerged={} metric[repointed={}, dropped={}] "
                        + "category[repointed={}, dropped={}] coverage[repointed={}, dropped={}] totalMs={}",
                all.size(), result.groupsMerged(), issnPass.groupsMerged(), titlePass.groupsMerged(),
                successionQuarantined, result.identitiesMerged(),
                result.metricFactsRepointed(), result.metricFactsDropped(),
                result.categoryFactsRepointed(), result.categoryFactsDropped(),
                result.coverageFactsRepointed(), result.coverageFactsDropped(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    private MergeCounts mergeGroups(List<List<WosJournalIdentity>> groups) {
        int groupsMerged = 0;
        int identitiesMerged = 0;
        long metricRepointed = 0, metricDropped = 0;
        long categoryRepointed = 0, categoryDropped = 0;
        long coverageRepointed = 0, coverageDropped = 0;
        Set<String> removedIds = new LinkedHashSet<>();

        for (List<WosJournalIdentity> group : groups) {
            if (group.size() < 2) {
                continue;
            }
            WosJournalIdentity winner = chooseWinner(group);
            List<WosJournalIdentity> losers = group.stream().filter(i -> !i.getId().equals(winner.getId())).toList();

            mergeInto(winner, losers);

            for (WosJournalIdentity loser : losers) {
                FactMoveCounts m = repointMetricFacts(loser.getId(), winner.getId());
                metricRepointed += m.repointed();
                metricDropped += m.dropped();
                FactMoveCounts c = repointCategoryFacts(loser.getId(), winner.getId());
                categoryRepointed += c.repointed();
                categoryDropped += c.dropped();
                FactMoveCounts v = repointCoverageFacts(loser.getId(), winner.getId());
                coverageRepointed += v.repointed();
                coverageDropped += v.dropped();
            }

            winner.setUpdatedAt(Instant.now());
            winner.setBuilderVersion(BuilderVersion.WOS_FACT);
            journalIdentityRepository.save(winner);
            journalIdentityRepository.deleteAll(losers);
            losers.forEach(loser -> removedIds.add(loser.getId()));

            groupsMerged++;
            identitiesMerged += losers.size();
        }
        return new MergeCounts(groupsMerged, identitiesMerged,
                metricRepointed, metricDropped, categoryRepointed, categoryDropped, coverageRepointed, coverageDropped,
                removedIds);
    }

    /** Identities sharing an identical non-blank normalized title (only multi-member groups). */
    private List<List<WosJournalIdentity>> groupByNormalizedTitle(List<WosJournalIdentity> identities) {
        Map<String, List<WosJournalIdentity>> byTitle = new LinkedHashMap<>();
        for (WosJournalIdentity identity : identities) {
            String title = identity.getNormalizedTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            byTitle.computeIfAbsent(title, k -> new ArrayList<>()).add(identity);
        }
        return byTitle.values().stream().filter(g -> g.size() > 1).toList();
    }

    /**
     * True when two members carry a metric fact for the same (year, metricType) with different values —
     * the signature of two concurrent, genuinely distinct journals rather than one journal's ISSN succession.
     */
    private boolean hasConflictingMetrics(List<WosJournalIdentity> group) {
        Map<String, WosMetricFact> firstByKey = new LinkedHashMap<>();
        for (WosJournalIdentity identity : group) {
            for (WosMetricFact fact : metricFactRepository.findAllByJournalId(identity.getId())) {
                String key = fact.getYear() + "|" + fact.getMetricType();
                WosMetricFact seen = firstByKey.putIfAbsent(key, fact);
                if (seen != null
                        && !Objects.equals(seen.getJournalId(), fact.getJournalId())
                        && seen.getValue() != null && fact.getValue() != null
                        && !Objects.equals(seen.getValue(), fact.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private record MergeCounts(
            int groupsMerged,
            int identitiesMerged,
            long metricRepointed,
            long metricDropped,
            long categoryRepointed,
            long categoryDropped,
            long coverageRepointed,
            long coverageDropped,
            Set<String> removedIds
    ) {
    }

    /**
     * Union-find over identities: two identities join the same group when they share an ISSN token
     * <em>and</em> carry the same normalized title. The title guard is what keeps distinct journals that
     * merely share an eISSN (a sibling/continuation) from collapsing together.
     */
    private List<List<WosJournalIdentity>> groupDuplicates(List<WosJournalIdentity> identities) {
        Map<String, WosJournalIdentity> byId = new LinkedHashMap<>();
        Map<String, String> parent = new HashMap<>();
        for (WosJournalIdentity identity : identities) {
            byId.put(identity.getId(), identity);
            parent.put(identity.getId(), identity.getId());
        }

        // token -> identities carrying it; union within a token only when normalized titles match.
        Map<String, List<WosJournalIdentity>> byToken = new HashMap<>();
        for (WosJournalIdentity identity : identities) {
            for (String token : issnTokens(identity)) {
                byToken.computeIfAbsent(token, k -> new ArrayList<>()).add(identity);
            }
        }
        for (List<WosJournalIdentity> sharing : byToken.values()) {
            for (int i = 0; i < sharing.size(); i++) {
                for (int j = i + 1; j < sharing.size(); j++) {
                    if (sameTitle(sharing.get(i), sharing.get(j))) {
                        union(parent, sharing.get(i).getId(), sharing.get(j).getId());
                    }
                }
            }
        }

        Map<String, List<WosJournalIdentity>> grouped = new LinkedHashMap<>();
        for (WosJournalIdentity identity : identities) {
            grouped.computeIfAbsent(find(parent, identity.getId()), k -> new ArrayList<>()).add(identity);
        }
        return grouped.values().stream().filter(g -> g.size() > 1).toList();
    }

    private boolean sameTitle(WosJournalIdentity a, WosJournalIdentity b) {
        String ta = a.getNormalizedTitle();
        String tb = b.getNormalizedTitle();
        return ta != null && !ta.isBlank() && ta.equals(tb);
    }

    private String find(Map<String, String> parent, String id) {
        String root = id;
        while (!Objects.equals(parent.get(root), root)) {
            root = parent.get(root);
        }
        // path compression
        String cursor = id;
        while (!Objects.equals(parent.get(cursor), root)) {
            String next = parent.get(cursor);
            parent.put(cursor, root);
            cursor = next;
        }
        return root;
    }

    private void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    /**
     * Winner = the most complete identity (most ISSN tokens), tie-broken by earliest creation then smallest
     * id so the choice is deterministic across rebuilds. The winner inherits the union of all tokens during
     * the merge, so forum matching is unaffected by which record wins.
     */
    private WosJournalIdentity chooseWinner(List<WosJournalIdentity> group) {
        return group.stream()
                .min((a, b) -> {
                    int byTokens = Integer.compare(issnTokens(b).size(), issnTokens(a).size());
                    if (byTokens != 0) {
                        return byTokens;
                    }
                    int byCreated = effectiveCreatedAt(a).compareTo(effectiveCreatedAt(b));
                    if (byCreated != 0) {
                        return byCreated;
                    }
                    return a.getId().compareTo(b.getId());
                })
                .orElseThrow();
    }

    private void mergeInto(WosJournalIdentity winner, List<WosJournalIdentity> losers) {
        if (winner.getAliasIssns() == null) {
            winner.setAliasIssns(new ArrayList<>());
        }
        if (winner.getAlternativeNames() == null) {
            winner.setAlternativeNames(new ArrayList<>());
        }
        Set<String> primaryAndE = new LinkedHashSet<>();
        if (winner.getPrimaryIssn() != null) {
            primaryAndE.add(winner.getPrimaryIssn());
        }
        if (winner.getEIssn() != null) {
            primaryAndE.add(winner.getEIssn());
        }
        for (WosJournalIdentity loser : losers) {
            for (String token : issnTokens(loser)) {
                if (!primaryAndE.contains(token) && !winner.getAliasIssns().contains(token)) {
                    winner.getAliasIssns().add(token);
                }
            }
            if ((winner.getAbbreviatedTitle() == null || winner.getAbbreviatedTitle().isBlank())
                    && loser.getAbbreviatedTitle() != null && !loser.getAbbreviatedTitle().isBlank()) {
                winner.setAbbreviatedTitle(loser.getAbbreviatedTitle());
            }
            if (loser.getAlternativeNames() != null) {
                for (String alt : loser.getAlternativeNames()) {
                    if (alt != null && !alt.isBlank() && !winner.getAlternativeNames().contains(alt)) {
                        winner.getAlternativeNames().add(alt);
                    }
                }
            }
            // keep the loser's display title as an alternative name when it differs from the winner's
            if (loser.getTitle() != null && !loser.getTitle().isBlank()
                    && !sameTitle(loser, winner)
                    && !winner.getAlternativeNames().contains(loser.getTitle())) {
                winner.getAlternativeNames().add(loser.getTitle());
            }
        }
    }

    private FactMoveCounts repointMetricFacts(String loserId, String winnerId) {
        List<WosMetricFact> winnerFacts = metricFactRepository.findAllByJournalId(winnerId);
        Set<String> winnerKeys = new LinkedHashSet<>();
        for (WosMetricFact f : winnerFacts) {
            winnerKeys.add(metricKey(f));
        }
        List<WosMetricFact> loserFacts = metricFactRepository.findAllByJournalId(loserId);
        List<WosMetricFact> toSave = new ArrayList<>();
        List<WosMetricFact> toDelete = new ArrayList<>();
        for (WosMetricFact f : loserFacts) {
            String key = metricKey(f);
            if (winnerKeys.contains(key)) {
                toDelete.add(f);
            } else {
                f.setJournalId(winnerId);
                winnerKeys.add(key);
                toSave.add(f);
            }
        }
        if (!toSave.isEmpty()) {
            metricFactRepository.saveAll(toSave);
        }
        if (!toDelete.isEmpty()) {
            metricFactRepository.deleteAll(toDelete);
        }
        return new FactMoveCounts(toSave.size(), toDelete.size());
    }

    private FactMoveCounts repointCategoryFacts(String loserId, String winnerId) {
        List<WosCategoryFact> winnerFacts = categoryFactRepository.findAllByJournalId(winnerId);
        Set<String> winnerKeys = new LinkedHashSet<>();
        for (WosCategoryFact f : winnerFacts) {
            winnerKeys.add(categoryKey(f));
        }
        List<WosCategoryFact> loserFacts = categoryFactRepository.findAllByJournalId(loserId);
        List<WosCategoryFact> toSave = new ArrayList<>();
        List<WosCategoryFact> toDelete = new ArrayList<>();
        for (WosCategoryFact f : loserFacts) {
            String key = categoryKey(f);
            if (winnerKeys.contains(key)) {
                toDelete.add(f);
            } else {
                f.setJournalId(winnerId);
                winnerKeys.add(key);
                toSave.add(f);
            }
        }
        if (!toSave.isEmpty()) {
            categoryFactRepository.saveAll(toSave);
        }
        if (!toDelete.isEmpty()) {
            categoryFactRepository.deleteAll(toDelete);
        }
        return new FactMoveCounts(toSave.size(), toDelete.size());
    }

    private FactMoveCounts repointCoverageFacts(String loserId, String winnerId) {
        List<WosCoverageFact> winnerFacts = coverageFactRepository.findAllByJournalIdIn(List.of(winnerId));
        Set<String> winnerKeys = new LinkedHashSet<>();
        for (WosCoverageFact f : winnerFacts) {
            winnerKeys.add(coverageKey(f));
        }
        List<WosCoverageFact> loserFacts = coverageFactRepository.findAllByJournalIdIn(List.of(loserId));
        List<WosCoverageFact> toSave = new ArrayList<>();
        List<WosCoverageFact> toDelete = new ArrayList<>();
        for (WosCoverageFact f : loserFacts) {
            String key = coverageKey(f);
            if (winnerKeys.contains(key)) {
                toDelete.add(f);
            } else {
                f.setJournalId(winnerId);
                winnerKeys.add(key);
                toSave.add(f);
            }
        }
        if (!toSave.isEmpty()) {
            coverageFactRepository.saveAll(toSave);
        }
        if (!toDelete.isEmpty()) {
            coverageFactRepository.deleteAll(toDelete);
        }
        return new FactMoveCounts(toSave.size(), toDelete.size());
    }

    private String metricKey(WosMetricFact f) {
        return f.getYear() + "|" + f.getMetricType();
    }

    private String categoryKey(WosCategoryFact f) {
        return f.getYear() + "|" + f.getCategoryNameCanonical() + "|" + f.getEditionNormalized() + "|" + f.getMetricType();
    }

    private String coverageKey(WosCoverageFact f) {
        return f.getYear() + "|" + f.getEditionNormalized() + "|" + f.getCategoryNameCanonical();
    }

    private Set<String> issnTokens(WosJournalIdentity identity) {
        Set<String> tokens = new LinkedHashSet<>();
        addToken(tokens, identity.getPrimaryIssn());
        addToken(tokens, identity.getEIssn());
        if (identity.getAliasIssns() != null) {
            for (String alias : identity.getAliasIssns()) {
                addToken(tokens, alias);
            }
        }
        return tokens;
    }

    private void addToken(Set<String> tokens, String raw) {
        String normalized = WosCanonicalContractSupport.normalizeIssnToken(raw);
        if (normalized != null) {
            tokens.add(normalized);
        }
    }

    private Instant effectiveCreatedAt(WosJournalIdentity identity) {
        return identity.getCreatedAt() == null ? Instant.EPOCH : identity.getCreatedAt();
    }

    private record FactMoveCounts(long repointed, long dropped) {
    }

    public record WosIdentityDedupResult(
            int identitiesScanned,
            int groupsMerged,
            int identitiesMerged,
            long metricFactsRepointed,
            long metricFactsDropped,
            long categoryFactsRepointed,
            long categoryFactsDropped,
            long coverageFactsRepointed,
            long coverageFactsDropped
    ) {
    }
}
