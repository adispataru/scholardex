package ro.uvt.pokedex.core.service.application.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.PublicationAuthorshipDecisionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingClaimRecommendationServiceTest {

    @Mock private EffectiveAuthorshipReadService effectiveAuthorship;
    @Mock private PublicationAuthorshipDecisionService decisions;
    @InjectMocks private OnboardingClaimRecommendationService service;

    @Test
    void nullProfileYieldsEmpty() {
        assertEquals(0, service.recommend("u@uvt.ro", null).recommendedConfirmCount());
    }

    @Test
    void recommendsOnlyAuthorAndAffiliationMatches() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setConfirmedScholardexAuthorIds(List.of("author-me"));
        profile.setCurrentAffiliationIds(List.of("aff-uvt"));

        // p1: my author + my affiliation        -> CONFIRM
        // p2: my author, different affiliation   -> REVIEW
        // p3: different author, my affiliation    -> REVIEW
        when(effectiveAuthorship.findWorkspaceReviewPublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("p1", "Mine at UVT", List.of("author-me", "co"), List.of("aff-uvt")),
                pub("p2", "Mine elsewhere", List.of("author-me"), List.of("aff-other")),
                pub("p3", "Homonym at UVT", List.of("author-other"), List.of("aff-uvt"))));
        when(decisions.findDecisionsForUser("u@uvt.ro")).thenReturn(List.of());

        ClaimRecommendations r = service.recommend("u@uvt.ro", profile);

        assertEquals(List.of("p1"), r.recommendedConfirmIds());
        assertEquals(1, r.recommendedConfirmCount());
        assertEquals(2, r.reviewCount());
        assertEquals(0, r.alreadyDecidedCount());
        assertEquals(List.of("Mine at UVT"), r.sampleTitles());
    }

    @Test
    void skipsPublicationsAlreadyDecided() {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setConfirmedScholardexAuthorIds(List.of("author-me"));
        profile.setCurrentAffiliationIds(List.of("aff-uvt"));

        when(effectiveAuthorship.findWorkspaceReviewPublicationsForUser("u@uvt.ro")).thenReturn(List.of(
                pub("p1", "Already confirmed", List.of("author-me"), List.of("aff-uvt")),
                pub("p2", "Fresh", List.of("author-me"), List.of("aff-uvt"))));
        when(decisions.findDecisionsForUser("u@uvt.ro"))
                .thenReturn(List.of(decision("p1", PublicationAuthorshipDecision.Status.CONFIRMED)));

        ClaimRecommendations r = service.recommend("u@uvt.ro", profile);

        assertEquals(List.of("p2"), r.recommendedConfirmIds());
        assertEquals(1, r.alreadyDecidedCount());
    }

    @Test
    void noCandidatesYieldsEmpty() {
        when(effectiveAuthorship.findWorkspaceReviewPublicationsForUser("u@uvt.ro")).thenReturn(List.of());
        lenient().when(decisions.findDecisionsForUser("u@uvt.ro")).thenReturn(List.of());
        assertEquals(0, service.recommend("u@uvt.ro", new User.ResearcherProfile()).recommendedConfirmCount());
    }

    private static ScholardexPublicationView pub(String id, String title, List<String> authorIds, List<String> affIds) {
        ScholardexPublicationView v = new ScholardexPublicationView();
        v.setId(id);
        v.setTitle(title);
        v.setAuthors(authorIds);
        v.setAffiliations(affIds);
        return v;
    }

    private static PublicationAuthorshipDecision decision(String pubId, PublicationAuthorshipDecision.Status status) {
        PublicationAuthorshipDecision d = new PublicationAuthorshipDecision();
        d.setPublicationId(pubId);
        d.setStatus(status);
        return d;
    }
}
