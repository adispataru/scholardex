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
