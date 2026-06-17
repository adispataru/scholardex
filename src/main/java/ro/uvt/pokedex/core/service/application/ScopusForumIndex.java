package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * H66B M1b — ISSN-token + name|agg index over Scopus forum facts for O(1) candidate lookup while building
 * forums. Extracted from {@code WosScholardexOnboardingService.ScopusForumIndex}; mirrors
 * {@link ForumIdentityIndex} (the prior linear scan per WoS journal was O(n²), ≈800M comparisons / ~16 min).
 */
public final class ScopusForumIndex {

    private final Map<String, List<ScopusForumFact>> byIssnToken = new HashMap<>();
    private final Map<String, List<ScopusForumFact>> byNameAgg = new HashMap<>();

    public ScopusForumIndex(List<ScopusForumFact> scopusForums) {
        for (ScopusForumFact scopusForum : scopusForums) {
            for (String token : ForumIdentityNormalization.scopusIssnTokens(scopusForum)) {
                byIssnToken.computeIfAbsent(token, k -> new ArrayList<>()).add(scopusForum);
            }
            byNameAgg.computeIfAbsent(ForumIdentityNormalization.scopusNameAggKey(scopusForum), k -> new ArrayList<>())
                    .add(scopusForum);
        }
    }

    /** ISSN-token matches (exactness-rechecked), falling back to the name|agg bucket when none. */
    public List<ScopusForumFact> findCandidates(Collection<String> issnTokens, String nameNormalized,
                                                String aggregationTypeNormalized) {
        LinkedHashSet<ScopusForumFact> issnMatches = new LinkedHashSet<>();
        if (issnTokens != null) {
            for (String token : issnTokens) {
                List<ScopusForumFact> hits = byIssnToken.get(token);
                if (hits != null) {
                    for (ScopusForumFact scopusForum : hits) {
                        if (ForumIdentityNormalization.matchesIssn(scopusForum, issnTokens)) {
                            issnMatches.add(scopusForum);
                        }
                    }
                }
            }
        }
        if (!issnMatches.isEmpty() || nameNormalized == null) {
            return new ArrayList<>(issnMatches);
        }
        List<ScopusForumFact> nameHits =
                byNameAgg.get(nameNormalized + "|" + ForumIdentityNormalization.normalizeToken(aggregationTypeNormalized));
        return nameHits == null ? new ArrayList<>() : new ArrayList<>(nameHits);
    }
}
