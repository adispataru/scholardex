package ro.uvt.pokedex.core.service.application.onboarding;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.user.User;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearcherOnboardingServiceTest {

    private final ResearcherOnboardingService service = new ResearcherOnboardingService();

    @Test
    void nullProfileResumesAtFirstStepWithZeroPercent() {
        ResearcherOnboardingService.OnboardingStatus s = service.evaluate(null);
        assertEquals(OnboardingStep.IDENTITY_IDS, s.nextStep());
        assertTrue(s.completedSteps().isEmpty());
        assertEquals(0, s.percentComplete());
        assertFalse(s.complete());
    }

    @Test
    void emptyProfileResumesAtIdentity() {
        ResearcherOnboardingService.OnboardingStatus s = service.evaluate(new User.ResearcherProfile());
        assertEquals(OnboardingStep.IDENTITY_IDS, s.nextStep());
        assertEquals(0, s.percentComplete());
    }

    @Test
    void scopusFromImportResumesAtOrcid() {
        // mirrors the 51/55 imported staff: scopus id present, nothing else done
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setScopusId(List.of("36674707400"));

        ResearcherOnboardingService.OnboardingStatus s = service.evaluate(p);

        assertEquals(OnboardingStep.ORCID, s.nextStep());
        assertEquals(List.of(OnboardingStep.IDENTITY_IDS), s.completedSteps());
        assertEquals(20, s.percentComplete());   // 1 of 5
        assertFalse(s.complete());
    }

    @Test
    void wosIdAloneSatisfiesIdentity() {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setWosId(List.of("X-1234-2020"));
        assertEquals(OnboardingStep.ORCID, service.evaluate(p).nextStep());
    }

    @Test
    void invalidOrcidDoesNotCompleteOrcidStep() {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setScopusId(List.of("sc-1"));
        p.setOrcid("not-an-orcid");
        assertEquals(OnboardingStep.ORCID, service.evaluate(p).nextStep());
    }

    @Test
    void validOrcidAdvancesToAffiliations() {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setScopusId(List.of("sc-1"));
        p.setOrcid("https://orcid.org/0000-0002-1825-0097");   // URL form normalizes
        assertEquals(OnboardingStep.AFFILIATIONS, service.evaluate(p).nextStep());
    }

    @Test
    void confirmedAffiliationsAdvanceToAuthorMatch() {
        User.ResearcherProfile p = base();
        p.setAffiliationsConfirmedAt(Instant.now());
        assertEquals(OnboardingStep.AUTHOR_MATCH, service.evaluate(p).nextStep());
    }

    @Test
    void confirmedAuthorAdvancesToPublicationClaim() {
        User.ResearcherProfile p = base();
        p.setAffiliationsConfirmedAt(Instant.now());
        p.setConfirmedScholardexAuthorIds(List.of("author-1"));
        ResearcherOnboardingService.OnboardingStatus s = service.evaluate(p);
        assertEquals(OnboardingStep.PUBLICATION_CLAIM, s.nextStep());
        assertEquals(80, s.percentComplete());   // 4 of 5
        assertFalse(s.complete());
    }

    @Test
    void finishedOnboardingHasNoNextStepAndIsComplete() {
        User.ResearcherProfile p = base();
        p.setAffiliationsConfirmedAt(Instant.now());
        p.setConfirmedScholardexAuthorIds(List.of("author-1"));
        p.setOnboardingCompletedAt(Instant.now());

        ResearcherOnboardingService.OnboardingStatus s = service.evaluate(p);

        assertNull(s.nextStep());
        assertTrue(s.complete());
        assertEquals(100, s.percentComplete());
        assertEquals(5, s.completedSteps().size());
    }

    @Test
    void blankConfirmedAuthorIdsDoNotCountAsAuthorMatch() {
        User.ResearcherProfile p = base();
        p.setAffiliationsConfirmedAt(Instant.now());
        p.setConfirmedScholardexAuthorIds(List.of("  ", ""));
        assertEquals(OnboardingStep.AUTHOR_MATCH, service.evaluate(p).nextStep());
    }

    /** Identity + ORCID done — the common resume point after the first two steps. */
    private static User.ResearcherProfile base() {
        User.ResearcherProfile p = new User.ResearcherProfile();
        p.setScopusId(List.of("sc-1"));
        p.setOrcid("0000-0002-1825-0097");
        return p;
    }
}
