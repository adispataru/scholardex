package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H66B M1b — incremental in-memory index over the canonical-forum-by-id map giving O(1) candidate lookup
 * during bulk forum building. Extracted from {@code WosScholardexOnboardingService.CanonicalForumIndex}; the
 * perf heart of the merge engine (the prior linear scan per source row was O(n²), ~5.8 min on 29.7k forums).
 *
 * <p>Forums are indexed by their ISSN tokens (issn/eIssn/aliases, normalized) and by name|agg key, updated
 * incrementally as forums are created or merged. Merges only add tokens, so re-indexing is idempotent and no
 * stale-entry removal is needed. The {@code byId} map is shared with the caller, so {@code containsKey}/
 * {@code get} on it still observe inserts made through this index.
 */
public final class ForumIdentityIndex {

    private final Map<String, ScholardexForumFact> byId;
    private final Map<String, Set<String>> issnTokenToIds = new HashMap<>();
    private final Map<String, Set<String>> nameAggToIds = new HashMap<>();

    public ForumIdentityIndex(Map<String, ScholardexForumFact> byId) {
        this.byId = byId;
        for (ScholardexForumFact forum : byId.values()) {
            indexTokens(forum);
        }
    }

    /** Insert/refresh a forum: store it in the id map and (re)index its current tokens. Idempotent. */
    public void put(ScholardexForumFact forum) {
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
        for (String token : ForumIdentityNormalization.issnTokensOf(forum)) {
            issnTokenToIds.computeIfAbsent(token, k -> new LinkedHashSet<>()).add(id);
        }
        nameAggToIds.computeIfAbsent(ForumIdentityNormalization.nameAggKeyOf(forum), k -> new LinkedHashSet<>()).add(id);
    }

    /**
     * Canonical forums sharing ≥1 ISSN token with {@code issnTokens}; when ISSN-less, those matching the
     * {@code nameAggKey}. Id-sorted for deterministic candidate order. The {@code matchesIssn} re-check is a
     * redundant exactness guard over the tiny candidate set.
     */
    public List<ScholardexForumFact> findCandidates(Collection<String> issnTokens, String nameAggKey) {
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
            if (byIssn && !ForumIdentityNormalization.matchesIssn(forum, issnTokens)) {
                continue;
            }
            candidates.add(forum);
        }
        candidates.sort(Comparator.comparing(ScholardexForumFact::getId));
        return candidates;
    }
}
