package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationMergeDecisionRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H84 S3 — the researcher-facing side of publication merges. Suggests likely duplicates among the
 * researcher's OWN publications (exact normalized title, publication year within ±1 — the shape of the
 * FedCSIS pairs: same paper via Scopus and OpenAlex with different coverDate/creator formats) and accepts
 * merge-request flags, which land as PENDING decisions in the admin queue.
 *
 * <p>Ownership is enforced here: a researcher can only flag pairs where BOTH publications are on their own
 * effective list. Pairs with any standing decision (pending, approved, or rejected) are never re-suggested;
 * rows in a PENDING pair carry a "merge requested" state so the researcher sees the request is with the
 * admin.</p>
 */
@Service
@RequiredArgsConstructor
public class PublicationMergeWorkspaceFacade {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final PublicationMergeService publicationMergeService;
    private final PublicationMergeDecisionRepository decisionRepository;

    public MergeWorkspaceView mergeState(String userEmail) {
        List<ScholardexPublicationView> publications =
                effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail);
        if (publications.isEmpty()) {
            return new MergeWorkspaceView(List.of(), Map.of());
        }
        Set<String> ownIds = new HashSet<>();
        publications.forEach(pub -> ownIds.add(pub.getId()));

        List<PublicationMergeDecision> decisions =
                decisionRepository.findBySurvivorCanonicalIdInOrDuplicateCanonicalIdIn(ownIds, ownIds);
        Set<String> decidedPairKeys = new HashSet<>();
        Map<String, String> stateByPublicationId = new HashMap<>();
        for (PublicationMergeDecision decision : decisions) {
            decidedPairKeys.add(decision.getPairKey());
            if (decision.getStatus() == PublicationMergeDecision.Status.PENDING) {
                stateByPublicationId.put(decision.getSurvivor().getCanonicalId(), "PENDING");
                stateByPublicationId.put(decision.getDuplicate().getCanonicalId(), "PENDING");
            }
        }

        return new MergeWorkspaceView(suggest(publications, decidedPairKeys), stateByPublicationId);
    }

    /** Flag a pair for merging. Both publications must be the researcher's own; sides may arrive in any
     *  order — the survivor is re-picked by record richness, matching what the suggestions propose. */
    public PublicationMergeDecision flag(String userEmail, String researcherId,
                                         String publicationIdA, String publicationIdB, String note) {
        if (publicationIdA == null || publicationIdA.equals(publicationIdB)) {
            throw new IllegalArgumentException("two distinct publications are required");
        }
        Map<String, ScholardexPublicationView> ownById = new LinkedHashMap<>();
        effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail)
                .forEach(pub -> ownById.put(pub.getId(), pub));
        ScholardexPublicationView a = ownById.get(publicationIdA);
        ScholardexPublicationView b = ownById.get(publicationIdB);
        if (a == null || b == null) {
            throw new IllegalArgumentException("both publications must be on your own publication list");
        }
        boolean aSurvives = richness(a) >= richness(b);
        ScholardexPublicationView survivor = aSurvives ? a : b;
        ScholardexPublicationView duplicate = aSurvives ? b : a;
        return publicationMergeService.requestMerge(survivor.getId(), duplicate.getId(), userEmail, researcherId, note);
    }

    /* ------------------------------------------------------------------ */

    private List<Suggestion> suggest(List<ScholardexPublicationView> publications, Set<String> decidedPairKeys) {
        Map<String, List<ScholardexPublicationView>> byTitle = new LinkedHashMap<>();
        for (ScholardexPublicationView pub : publications) {
            String normalized = ScholardexPublicationCanonicalizationService.normalizeTitle(pub.getTitle());
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            byTitle.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(pub);
        }
        List<Suggestion> suggestions = new ArrayList<>();
        for (List<ScholardexPublicationView> group : byTitle.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    ScholardexPublicationView a = group.get(i);
                    ScholardexPublicationView b = group.get(j);
                    if (!yearsCompatible(a, b)) {
                        continue;
                    }
                    if (decidedPairKeys.contains(PublicationMergeDecision.pairKeyOf(a.getId(), b.getId()))) {
                        continue;
                    }
                    boolean aSurvives = richness(a) >= richness(b);
                    suggestions.add(new Suggestion(
                            sideRef(aSurvives ? a : b),
                            sideRef(aSurvives ? b : a)
                    ));
                }
            }
        }
        return suggestions;
    }

    static boolean yearsCompatible(ScholardexPublicationView a, ScholardexPublicationView b) {
        Integer yearA = parseYear(a.getCoverDate());
        Integer yearB = parseYear(b.getCoverDate());
        return yearA == null || yearB == null || Math.abs(yearA - yearB) <= 1;
    }

    /** Richer record survives: a Scopus EID outweighs a DOI, citations break ties. (Package-visible: the S4 sweep reuses it.) */
    static long richness(ScholardexPublicationView pub) {
        long score = 0;
        if (!isBlank(pub.getEid())) {
            score += 4_000_000_000L;
        }
        if (!isBlank(pub.getDoi())) {
            score += 2_000_000_000L;
        }
        return score + Math.max(pub.getCitedByCount(), 0);
    }

    private static SideRef sideRef(ScholardexPublicationView pub) {
        return new SideRef(pub.getId(), pub.getTitle(), pub.getEid() != null && !pub.getEid().isBlank() ? "SCOPUS" : "OTHER",
                pub.getCoverDate(), blankToNull(pub.getDoi()), blankToNull(pub.getEid()), pub.getCitedByCount());
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = YEAR.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record MergeWorkspaceView(List<Suggestion> suggestions, Map<String, String> mergeStateByPublicationId) {
    }

    /** A suggested pair, survivor-first (richness order) — the flag endpoint re-derives sides anyway. */
    public record Suggestion(SideRef survivor, SideRef duplicate) {
    }

    public record SideRef(String id, String title, String sourceHint, String coverDate,
                          String doi, String eid, int citedByCount) {
    }
}
