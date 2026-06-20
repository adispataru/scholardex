package ro.uvt.pokedex.core.service.application.onboarding;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.PublicationAuthorshipDecisionService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H70 step 5 — recommends which of the researcher's candidate publications to bulk-confirm during onboarding.
 *
 * <p>Confidence = <b>author-id + affiliation overlap</b> (decided H70): a candidate is recommend-confirm iff it
 * is attributed to one of the researcher's confirmed canonical author records AND shares an affiliation with
 * their confirmed affiliation set. Names are deliberately not used (artefacts). Non-matching candidates are left
 * for review, never auto-rejected.
 */
@Service
public class OnboardingClaimRecommendationService {

    private static final int MAX_SAMPLE_TITLES = 5;

    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;

    public OnboardingClaimRecommendationService(
            EffectiveAuthorshipReadService effectiveAuthorshipReadService,
            PublicationAuthorshipDecisionService publicationAuthorshipDecisionService) {
        this.effectiveAuthorshipReadService = effectiveAuthorshipReadService;
        this.publicationAuthorshipDecisionService = publicationAuthorshipDecisionService;
    }

    public ClaimRecommendations recommend(String userEmail, User.ResearcherProfile profile) {
        if (profile == null) {
            return ClaimRecommendations.empty();
        }
        List<ScholardexPublicationView> candidates =
                effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser(userEmail);
        if (candidates.isEmpty()) {
            return ClaimRecommendations.empty();
        }

        Set<String> alreadyDecided = new HashSet<>();
        for (PublicationAuthorshipDecision d : publicationAuthorshipDecisionService.findDecisionsForUser(userEmail)) {
            if (d.getStatus() != null && d.getPublicationId() != null) {
                alreadyDecided.add(d.getPublicationId());   // CONFIRMED or REJECTED
            }
        }
        Set<String> confirmedAuthors = toSet(profile.getConfirmedScholardexAuthorIds());
        Set<String> affiliations = toSet(profile.getCurrentAffiliationIds());
        affiliations.addAll(toSet(profile.getPastAffiliationIds()));

        List<String> recommendedConfirmIds = new ArrayList<>();
        List<String> sampleTitles = new ArrayList<>();
        int reviewCount = 0;
        int alreadyDecidedCount = 0;
        for (ScholardexPublicationView pub : candidates) {
            String id = pub.getId();
            if (id == null) {
                continue;
            }
            if (alreadyDecided.contains(id)) {
                alreadyDecidedCount++;
                continue;
            }
            boolean authorMatch = intersects(pub.getAuthors(), confirmedAuthors);
            boolean affiliationMatch = intersects(pub.getAffiliations(), affiliations);
            if (authorMatch && affiliationMatch) {
                recommendedConfirmIds.add(id);
                if (sampleTitles.size() < MAX_SAMPLE_TITLES && pub.getTitle() != null && !pub.getTitle().isBlank()) {
                    sampleTitles.add(pub.getTitle());
                }
            } else {
                reviewCount++;
            }
        }
        return new ClaimRecommendations(recommendedConfirmIds, reviewCount, alreadyDecidedCount, sampleTitles);
    }

    private static Set<String> toSet(List<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static boolean intersects(List<String> values, Set<String> set) {
        if (values == null || set.isEmpty()) {
            return false;
        }
        for (String v : values) {
            if (v != null && set.contains(v)) {
                return true;
            }
        }
        return false;
    }
}
