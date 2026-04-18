package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationAuthorshipDecisionServiceTest {

    @Mock
    private PublicationAuthorshipDecisionRepository decisionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock
    private ScholardexAuthorshipFactRepository authorshipFactRepository;
    @Mock
    private UserIndicatorResultService userIndicatorResultService;
    @Mock
    private UserIndividualReportRunService userIndividualReportRunService;

    private PublicationAuthorshipDecisionService service;

    @BeforeEach
    void setUp() {
        service = new PublicationAuthorshipDecisionService(
                decisionRepository,
                userRepository,
                publicationFactRepository,
                authorshipFactRepository,
                userIndicatorResultService,
                userIndividualReportRunService
        );
    }

    @Test
    void createsConfirmedDecisionWithCompactSnapshot() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        ScholardexPublicationFact publication = publication("spub_1", "Paper title", "2-s2.0-1", "10.1000/test", List.of("sauth_primary"));
        ScholardexAuthorshipFact extraAuthorship = authorship("spub_1", "sauth_secondary");

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));
        when(publicationFactRepository.findById("spub_1")).thenReturn(Optional.of(publication));
        when(decisionRepository.findByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("spub_1")).thenReturn(List.of(extraAuthorship));
        when(decisionRepository.save(any(PublicationAuthorshipDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PublicationAuthorshipDecision saved = service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.CONFIRMED,
                "confirmed by researcher"
        );

        assertThat(saved.getStatus()).isEqualTo(PublicationAuthorshipDecision.Status.CONFIRMED);
        assertThat(saved.getDecisionSource()).isEqualTo(PublicationAuthorshipDecision.DecisionSource.USER_REVIEW);
        assertThat(saved.getSnapshot().getPublication().getTitle()).isEqualTo("Paper title");
        assertThat(saved.getSnapshot().getUser().getPrimaryScholardexAuthorId()).isEqualTo("sauth_primary");
        assertThat(saved.getSnapshot().getLinkedAuthorIds()).containsExactly("sauth_primary", "sauth_secondary");
        verify(publicationFactRepository, never()).save(any());
        verify(authorshipFactRepository, never()).save(any());
        verify(userIndicatorResultService).invalidateLatestResults("user@example.com");
        verify(userIndividualReportRunService).invalidateLatestRuns("user@example.com");
    }

    @Test
    void updatesExistingDecisionWithoutReplacingImmutableSnapshot() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        ScholardexPublicationFact publication = publication("spub_1", "New title", "2-s2.0-1", "10.1000/test", List.of("sauth_primary"));
        PublicationAuthorshipDecision existing = new PublicationAuthorshipDecision();
        existing.setId("decision-1");
        existing.setUserEmail("user@example.com");
        existing.setPublicationId("spub_1");
        existing.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        existing.setDecisionSource(PublicationAuthorshipDecision.DecisionSource.USER_REVIEW);
        existing.setReason("old");
        existing.setCreatedAt(Instant.parse("2026-04-16T09:00:00Z"));
        existing.setUpdatedAt(Instant.parse("2026-04-16T09:00:00Z"));
        PublicationAuthorshipDecision.Snapshot snapshot = new PublicationAuthorshipDecision.Snapshot();
        snapshot.getPublication().setTitle("Original title");
        existing.setSnapshot(snapshot);

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));
        when(publicationFactRepository.findById("spub_1")).thenReturn(Optional.of(publication));
        when(decisionRepository.findByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(Optional.of(existing));
        when(decisionRepository.save(any(PublicationAuthorshipDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PublicationAuthorshipDecision saved = service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.REJECTED,
                "not mine"
        );

        assertThat(saved.getId()).isEqualTo("decision-1");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-04-16T09:00:00Z"));
        assertThat(saved.getStatus()).isEqualTo(PublicationAuthorshipDecision.Status.REJECTED);
        assertThat(saved.getSnapshot().getPublication().getTitle()).isEqualTo("Original title");
        verify(userIndicatorResultService).invalidateLatestResults("user@example.com");
        verify(userIndividualReportRunService).invalidateLatestRuns("user@example.com");
    }

    @Test
    void clearDecisionDeletesOverlayRow() {
        when(decisionRepository.deleteByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(1L);

        boolean cleared = service.clearDecision("user@example.com", "spub_1");

        assertThat(cleared).isTrue();
        verify(userIndicatorResultService).invalidateLatestResults("user@example.com");
        verify(userIndividualReportRunService).invalidateLatestRuns("user@example.com");
    }

    @Test
    void clearDecisionDoesNotInvalidateWhenNothingWasDeleted() {
        when(decisionRepository.deleteByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(0L);

        boolean cleared = service.clearDecision("user@example.com", "spub_1");

        assertThat(cleared).isFalse();
        verify(userIndicatorResultService, never()).invalidateLatestResults(any());
        verify(userIndividualReportRunService, never()).invalidateLatestRuns(any());
    }

    @Test
    void rejectsMissingUser() {
        when(userRepository.findById("user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.CONFIRMED,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void rejectsMissingPublication() {
        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user("user@example.com", "researcher-1", "sauth_primary")));
        when(publicationFactRepository.findById("spub_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.CONFIRMED,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication not found");
    }

    @Test
    void blocksDecisionWhenAffiliationScopeConfirmationIsMissing() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        user.getResearcherProfile().setAffiliationsConfirmedAt(null);
        user.getResearcherProfile().setScopusId(List.of("55637349100"));

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.CONFIRMED,
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confirm your current and past affiliations");

        verify(publicationFactRepository, never()).findById(any());
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void fillsSnapshotOnLegacyDecisionWithoutOne() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        ScholardexPublicationFact publication = publication("spub_1", "Paper title", "2-s2.0-1", "10.1000/test", List.of());
        PublicationAuthorshipDecision existing = new PublicationAuthorshipDecision();
        existing.setId("decision-1");
        existing.setUserEmail("user@example.com");
        existing.setPublicationId("spub_1");
        existing.setCreatedAt(Instant.parse("2026-04-16T09:00:00Z"));

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));
        when(publicationFactRepository.findById("spub_1")).thenReturn(Optional.of(publication));
        when(decisionRepository.findByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(Optional.of(existing));
        when(authorshipFactRepository.findByPublicationId("spub_1")).thenReturn(List.of());
        when(decisionRepository.save(any(PublicationAuthorshipDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PublicationAuthorshipDecision saved = service.upsertDecision(
                "user@example.com",
                "spub_1",
                PublicationAuthorshipDecision.Status.CONFIRMED,
                null
        );

        assertThat(saved.getSnapshot()).isNotNull();
        assertThat(saved.getSnapshot().getPublication().getTitle()).isEqualTo("Paper title");
        verify(userIndicatorResultService).invalidateLatestResults("user@example.com");
        verify(userIndividualReportRunService).invalidateLatestRuns("user@example.com");
    }

    @Test
    void bulkDecisionProcessesPendingItemsBestEffort() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        ScholardexPublicationFact pending = publication("spub_1", "Paper title", "2-s2.0-1", "10.1000/test", List.of("sauth_primary"));

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));
        when(decisionRepository.findByUserEmailAndPublicationIdIn("user@example.com", java.util.Set.of("spub_1", "spub_2")))
                .thenReturn(List.of(existingDecision("user@example.com", "spub_2", PublicationAuthorshipDecision.Status.REJECTED)));
        when(publicationFactRepository.findById("spub_1")).thenReturn(Optional.of(pending));
        when(decisionRepository.findByUserEmailAndPublicationId("user@example.com", "spub_1")).thenReturn(Optional.empty());
        when(authorshipFactRepository.findByPublicationId("spub_1")).thenReturn(List.of());
        when(decisionRepository.save(any(PublicationAuthorshipDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PublicationAuthorshipDecisionService.BulkDecisionResult result = service.upsertBulkDecisions(
                "user@example.com",
                List.of("spub_1", "spub_2"),
                PublicationAuthorshipDecision.Status.CONFIRMED,
                "mine"
        );

        assertThat(result.succeededByPublicationId()).containsOnlyKeys("spub_1");
        assertThat(result.succeededByPublicationId().get("spub_1").getStatus()).isEqualTo(PublicationAuthorshipDecision.Status.CONFIRMED);
        assertThat(result.failures()).containsExactly(new PublicationAuthorshipDecisionService.DecisionFailure("spub_2", "Only pending publications can be reviewed in bulk."));
        verify(userIndicatorResultService).invalidateLatestResults("user@example.com");
        verify(userIndividualReportRunService).invalidateLatestRuns("user@example.com");
    }

    @Test
    void bulkDecisionFailsFastWhenAffiliationScopeConfirmationIsMissing() {
        User user = user("user@example.com", "researcher-1", "sauth_primary");
        user.getResearcherProfile().setAffiliationsConfirmedAt(null);
        user.getResearcherProfile().setScopusId(List.of("55637349100"));

        when(userRepository.findById("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.upsertBulkDecisions(
                "user@example.com",
                List.of("spub_1"),
                PublicationAuthorshipDecision.Status.CONFIRMED,
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Confirm your current and past affiliations");

        verify(decisionRepository, never()).findByUserEmailAndPublicationIdIn(any(), any());
    }

    private PublicationAuthorshipDecision existingDecision(String userEmail,
                                                           String publicationId,
                                                           PublicationAuthorshipDecision.Status status) {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setId("decision-" + publicationId);
        decision.setUserEmail(userEmail);
        decision.setPublicationId(publicationId);
        decision.setStatus(status);
        return decision;
    }

    private User user(String email, String researcherId, String primaryAuthorId) {
        User user = new User();
        user.setEmail(email);
        user.setResearcherId(researcherId);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Ada");
        profile.setLastName("Lovelace");
        profile.setPrimaryScholardexAuthorId(primaryAuthorId);
        profile.setAffiliationsConfirmedAt(Instant.parse("2026-04-18T08:00:00Z"));
        user.setResearcherProfile(profile);
        return user;
    }

    private ScholardexPublicationFact publication(String id,
                                                  String title,
                                                  String eid,
                                                  String doi,
                                                  List<String> authorIds) {
        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId(id);
        publication.setTitle(title);
        publication.setEid(eid);
        publication.setDoi(doi);
        publication.setAuthorIds(authorIds);
        return publication;
    }

    private ScholardexAuthorshipFact authorship(String publicationId, String authorId) {
        ScholardexAuthorshipFact fact = new ScholardexAuthorshipFact();
        fact.setPublicationId(publicationId);
        fact.setAuthorId(authorId);
        return fact;
    }
}
