package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.SuspiciousAuthorshipState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuspiciousAuthorshipTriageServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;

    @InjectMocks
    private SuspiciousAuthorshipTriageService service;

    @Test
    void flagsNameMismatchWhenMatchedAuthorNameDoesNotFitResearcherProfile() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        ScholardexPublicationView publication = publication("p1", List.of("sauth_secondary"), List.of("af1"));
        ScholardexAuthorView secondary = author("sauth_secondary", "Grace Hopper", List.of("af1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary", "sauth_secondary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary", "sauth_secondary")))
                .thenReturn(List.of(author("sauth_primary", "Ada Lovelace", List.of("af1")), secondary));

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_secondary", secondary)
        );

        assertThat(result.get("p1").flags())
                .extracting(SuspiciousAuthorshipState.Flag::code)
                .contains(SuspiciousAuthorshipState.Code.NAME_MISMATCH, SuspiciousAuthorshipState.Code.SECONDARY_ID_ONLY);
    }

    @Test
    void flagsAffiliationMismatchWhenThereIsNoAffiliationOverlap() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af_allowed"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af_pub"));
        ScholardexAuthorView primary = author("sauth_primary", "Ada Lovelace", List.of("af_author"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result.get("p1").flags())
                .extracting(SuspiciousAuthorshipState.Flag::code)
                .containsExactly(SuspiciousAuthorshipState.Code.AFFILIATION_SCOPE_MISMATCH);
    }

    @Test
    void doesNotFlagPublicationWhenNameAndAffiliationAlign() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af1"));
        ScholardexAuthorView primary = author("sauth_primary", "Ada Lovelace", List.of("af1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void doesNotFlagNameMismatchForCommaFormattedMatchedAuthorName() {
        User user = user("Adrian", "Spataru", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af1"));
        ScholardexAuthorView primary = author("sauth_primary", "Spataru, Adrian", List.of("af1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void doesNotFlagNameMismatchWhenAlternativeAuthorNameMatchesResearcherProfile() {
        User user = user("Adrian", "Spataru", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af1"));
        ScholardexAuthorView primary = author("sauth_primary", "Spataru A.", List.of("af1"));
        primary.setAlternativeNames(List.of("Spataru, Adrian"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void ignoresReviewedPublicationsFromPendingSuspiciousQueue() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_secondary"), List.of("af1"));
        ScholardexAuthorView secondary = author("sauth_secondary", "Grace Hopper", List.of("af2"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary", "sauth_secondary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary", "sauth_secondary")))
                .thenReturn(List.of(author("sauth_primary", "Ada Lovelace", List.of("af1")), secondary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.REJECTED, "not mine", null)),
                Map.of("sauth_secondary", secondary)
        );

        assertThat(result).isEmpty();
    }

    @Test
    void flagsPaperSpecificAffiliationOutsideConfirmedResearcherScope() {
        User user = user("Adrian", "Spataru", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("saff_uvt"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("saff_graz"));
        ScholardexAuthorView primary = author("sauth_primary", "Spataru, Adrian", List.of("saff_uvt", "saff_graz"));
        ScholardexPublicationAuthorAffiliationFact edge = new ScholardexPublicationAuthorAffiliationFact();
        edge.setPublicationId("p1");
        edge.setAuthorId("sauth_primary");
        edge.setAffiliationId("saff_graz");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1"))).thenReturn(List.of(edge));

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result.get("p1").flags())
                .extracting(SuspiciousAuthorshipState.Flag::code)
                .containsExactly(SuspiciousAuthorshipState.Code.AFFILIATION_SCOPE_MISMATCH);
    }

    @Test
    void returnsEmptyWhenUserOrProfileMissing() {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());
        assertThat(service.evaluatePendingSuspiciousAuthorship(
                "missing@uvt.ro", List.of(), Map.of(), Map.of()
        )).isEmpty();

        User noProfile = new User();
        noProfile.setEmail("u@uvt.ro");
        when(userService.getUserByEmail("u@uvt.ro")).thenReturn(Optional.of(noProfile));
        assertThat(service.evaluatePendingSuspiciousAuthorship(
                "u@uvt.ro", List.of(publication("p1", List.of("a1"), List.of())), Map.of(), Map.of()
        )).isEmpty();
    }

    @Test
    void affiliationScopeConfirmationRequirementSuppressesAffiliationMismatch() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af_allowed"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(null);
        user.getResearcherProfile().setScopusId(List.of("12345"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af_pub"));
        ScholardexAuthorView primary = author("sauth_primary", "Ada Lovelace", List.of("af_other"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1")))
                .thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void secondaryIdOnlyNotRaisedWhenPrimaryMissing() {
        User user = user("Ada", "Lovelace", null);
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_secondary"), List.of("af1"));
        ScholardexAuthorView secondary = author("sauth_secondary", "Ada Lovelace", List.of("af1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_secondary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_secondary")))
                .thenReturn(List.of(secondary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1")))
                .thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_secondary", secondary)
        );
        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void affiliationScopeConfirmationAlsoUsesWosAndScopusIds() {
        User user = user("Ada", "Lovelace", "");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setScopusId(List.of(" ", "123"));
        user.getResearcherProfile().setWosId(List.of(" ", "W-1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(null);
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af_mismatch"));
        ScholardexAuthorView primary = author("sauth_primary", "Ada Lovelace", List.of("af_other"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1")))
                .thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );
        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void publicationWithNoMatchedResearchersDoesNotTriggerNameMismatch() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af1"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("other-author"), List.of("af1"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(author("sauth_primary", "Ada Lovelace", List.of("af1"))));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1")))
                .thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of()
        );
        assertThat(result).doesNotContainKey("p1");
    }

    @Test
    void helperLogicContracts() throws Exception {
        assertThat((boolean) invoke("familyNamesAgree", new Class<?>[]{String.class, String.class}, "smith", "smi")).isTrue();
        assertThat((boolean) invoke("familyNamesAgree", new Class<?>[]{String.class, String.class}, "smith", "jones")).isFalse();
        assertThat((boolean) invoke("givenNamesAgree", new Class<?>[]{String.class, String.class}, "ada", "a")).isTrue();
        assertThat((boolean) invoke("givenNamesAgree", new Class<?>[]{String.class, String.class}, "", "a")).isFalse();
        assertThat((boolean) invoke("overlaps", new Class<?>[]{java.util.Set.class, java.util.Set.class},
                java.util.Set.of("a", "b"), java.util.Set.of("c", "b"))).isTrue();
        assertThat((boolean) invoke("overlaps", new Class<?>[]{java.util.Set.class, java.util.Set.class},
                java.util.Set.of("a"), java.util.Set.of("c"))).isFalse();
        assertThat((List<String>) invoke("safeList", new Class<?>[]{List.class}, (Object) null)).isEmpty();
    }

    @Test
    void nameMatchingHelperContracts() throws Exception {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Adrian");
        profile.setLastName("Spataru");
        assertThat((boolean) invoke("namesLookLikeSamePerson",
                new Class<?>[]{User.ResearcherProfile.class, String.class},
                profile, "Spataru, Adrian")).isTrue();
        assertThat((boolean) invoke("namesLookLikeSamePerson",
                new Class<?>[]{User.ResearcherProfile.class, String.class},
                profile, "Grace Hopper")).isFalse();
        assertThat((boolean) invoke("namesLookLikeSamePerson",
                new Class<?>[]{User.ResearcherProfile.class, String.class},
                profile, " ")).isFalse();
    }

    @Test
    void secondaryIdAndAffiliationConfirmationHelpers() throws Exception {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setPrimaryScholardexAuthorId("sauth_primary");
        profile.setScopusId(List.of(" "));
        profile.setWosId(List.of("w1"));
        profile.setAffiliationsConfirmedAt(null);
        assertThat((boolean) invoke("requiresAffiliationScopeConfirmation",
                new Class<?>[]{User.ResearcherProfile.class}, profile)).isTrue();

        ScholardexPublicationView publication = publication("p1", List.of("sauth_secondary"), List.of());
        assertThat((boolean) invoke("isSecondaryIdOnly",
                new Class<?>[]{User.ResearcherProfile.class, ScholardexPublicationView.class, java.util.Set.class},
                profile, publication, java.util.Set.of("sauth_secondary"))).isTrue();
    }

    @Test
    void additionalHelperCoverageForAffiliationAndIdentityStreams() throws Exception {
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setCurrentAffiliationIds(List.of("af1", " "));
        profile.setPastAffiliationIds(java.util.Arrays.asList(" ", null, "af2"));
        profile.setWosId(java.util.Arrays.asList(" ", null, "w-ok"));
        profile.setScopusId(List.of());
        profile.setAffiliationsConfirmedAt(null);
        profile.setPrimaryScholardexAuthorId("");

        @SuppressWarnings("unchecked")
        java.util.Set<String> ids = (java.util.Set<String>) invoke("researcherAffiliationScopeIds",
                new Class<?>[]{User.ResearcherProfile.class}, profile);
        assertThat(ids).containsExactly("af1", "af2");
        assertThat((boolean) invoke("requiresAffiliationScopeConfirmation",
                new Class<?>[]{User.ResearcherProfile.class}, profile)).isTrue();

        ScholardexPublicationView pub = publication("p1", List.of("a1"), List.of("afx"));
        ScholardexAuthorView matched = author("a1", "A One", List.of("afx"));
        boolean mismatch = (boolean) invoke("hasAffiliationScopeMismatch",
                new Class<?>[]{ScholardexPublicationView.class, List.class, java.util.Set.class, Map.class, boolean.class},
                pub, List.of(matched), java.util.Set.of("af1"), Map.of("a1", java.util.Set.of("afx")), false);
        assertThat(mismatch).isTrue();

        boolean noMismatch = (boolean) invoke("hasAffiliationScopeMismatch",
                new Class<?>[]{ScholardexPublicationView.class, List.class, java.util.Set.class, Map.class, boolean.class},
                pub, List.of(matched), java.util.Set.of("afx"), Map.of("a1", java.util.Set.of("afx")), false);
        assertThat(noMismatch).isFalse();
    }

    @Test
    void affiliationMismatchUsesPublicationAffiliationsWhenNoPaperSpecificEdges() {
        User user = user("Ada", "Lovelace", "sauth_primary");
        user.getResearcherProfile().setCurrentAffiliationIds(List.of("af_allowed"));
        user.getResearcherProfile().setAffiliationsConfirmedAt(java.time.Instant.parse("2026-04-18T08:00:00Z"));
        ScholardexPublicationView publication = publication("p1", List.of("sauth_primary"), List.of("af_outside"));
        ScholardexAuthorView primary = author("sauth_primary", "Ada Lovelace", List.of("af_outside"));

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("sauth_primary"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("sauth_primary")))
                .thenReturn(List.of(primary));
        when(publicationAuthorAffiliationFactRepository.findByPublicationIdIn(List.of("p1")))
                .thenReturn(List.of());

        Map<String, SuspiciousAuthorshipState> result = service.evaluatePendingSuspiciousAuthorship(
                "user@uvt.ro",
                List.of(publication),
                Map.of("p1", new PublicationAuthorshipReviewState(PublicationAuthorshipReviewState.Status.PENDING, null, null)),
                Map.of("sauth_primary", primary)
        );

        assertThat(result.get("p1").flags())
                .extracting(SuspiciousAuthorshipState.Flag::code)
                .contains(SuspiciousAuthorshipState.Code.AFFILIATION_SCOPE_MISMATCH);
    }

    private Object invoke(String methodName, Class<?>[] argTypes, Object... args) throws Exception {
        Method method = SuspiciousAuthorshipTriageService.class.getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private static User user(String firstName, String lastName, String primaryAuthorId) {
        User user = new User();
        user.setEmail("user@uvt.ro");
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profile.setPrimaryScholardexAuthorId(primaryAuthorId);
        user.setResearcherProfile(profile);
        return user;
    }

    private static ScholardexPublicationView publication(String id, List<String> authorIds, List<String> affiliationIds) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setAuthors(authorIds);
        publication.setAffiliations(affiliationIds);
        return publication;
    }

    private static ScholardexAuthorView author(String id, String name, List<String> affiliationIds) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        author.setName(name);
        author.setAffiliationIds(affiliationIds);
        return author;
    }
}
