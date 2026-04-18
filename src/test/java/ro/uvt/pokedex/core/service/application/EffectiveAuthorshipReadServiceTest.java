package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveAuthorshipReadServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private ResearcherAuthorLookupService researcherAuthorLookupService;
    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private PublicationAuthorshipDecisionRepository publicationAuthorshipDecisionRepository;

    private EffectiveAuthorshipReadService service;

    @BeforeEach
    void setUp() {
        service = new EffectiveAuthorshipReadService(
                userService,
                researcherAuthorLookupService,
                scholardexProjectionReadService,
                publicationAuthorshipDecisionRepository
        );
    }

    @Test
    void returnsRawPublicationSetWhenNoDecisionsExist() {
        User user = user("user@uvt.ro");
        ScholardexAuthorView author = author("a1");
        ScholardexPublicationView publication = publication("p1", "Paper 1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro")).thenReturn(List.of());

        List<ScholardexPublicationView> result = service.findEffectivePublicationsForUser("user@uvt.ro");

        assertThat(result).containsExactly(publication);
    }

    @Test
    void removesRejectedPublicationFromEffectiveSet() {
        User user = user("user@uvt.ro");
        ScholardexAuthorView author = author("a1");
        ScholardexPublicationView kept = publication("p1", "Paper 1");
        ScholardexPublicationView rejected = publication("p2", "Paper 2");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(kept, rejected));
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(decision("p2", PublicationAuthorshipDecision.Status.REJECTED)));

        List<ScholardexPublicationView> result = service.findEffectivePublicationsForUser("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactly("p1");
    }

    @Test
    void keepsConfirmedPublicationOnceWhenAlreadyInRawSet() {
        User user = user("user@uvt.ro");
        ScholardexAuthorView author = author("a1");
        ScholardexPublicationView publication = publication("p1", "Paper 1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(publication));
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(decision("p1", PublicationAuthorshipDecision.Status.CONFIRMED)));

        List<ScholardexPublicationView> result = service.findEffectivePublicationsForUser("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactly("p1");
    }

    @Test
    void reincludesConfirmedPublicationMissingFromRawSet() {
        User user = user("user@uvt.ro");
        ScholardexAuthorView author = author("a1");
        ScholardexPublicationView raw = publication("p1", "Paper 1");
        ScholardexPublicationView confirmed = publication("p2", "Paper 2");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(raw));
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED)));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(confirmed));

        List<ScholardexPublicationView> result = service.findEffectivePublicationsForUser("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void ignoresStaleConfirmedDecisionWhenPublicationCannotBeFetched() {
        User user = user("user@uvt.ro");
        ScholardexAuthorView author = author("a1");
        ScholardexPublicationView raw = publication("p1", "Paper 1");

        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));
        when(researcherAuthorLookupService.resolveAuthorLookupKeys(any(Researcher.class))).thenReturn(List.of("a1"));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsIn(List.of("a1"))).thenReturn(List.of(raw));
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(decision("p-stale", PublicationAuthorshipDecision.Status.CONFIRMED)));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("p-stale"))).thenReturn(List.of());

        List<ScholardexPublicationView> result = service.findEffectivePublicationsForUser("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactly("p1");
    }

    @Test
    void returnsEmptyWhenUserMissing() {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        assertThat(service.findEffectivePublicationsForUser("missing@uvt.ro")).isEmpty();
    }

    @Test
    void returnsEmptyWhenUserHasNoResearcherProfile() {
        User user = new User();
        user.setEmail("user@uvt.ro");
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(Optional.of(user));

        assertThat(service.findEffectivePublicationsForUser("user@uvt.ro")).isEmpty();
    }

    @Test
    void confirmedOnlyPathReturnsOnlyConfirmedPublications() {
        ScholardexPublicationView confirmed = publication("p2", "Paper 2");

        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(
                        decision("p1", PublicationAuthorshipDecision.Status.REJECTED),
                        decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED)
                ));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(confirmed));

        List<ScholardexPublicationView> result = service.findConfirmedPublicationsForScoring("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactly("p2");
        assertThat(service.hasConfirmedPublicationsForScoring("user@uvt.ro")).isTrue();
    }

    @Test
    void confirmedOnlyPathExcludesPendingRawPublications() {
        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of());

        assertThat(service.findConfirmedPublicationsForScoring("user@uvt.ro")).isEmpty();
        assertThat(service.hasConfirmedPublicationsForScoring("user@uvt.ro")).isFalse();
    }

    @Test
    void confirmedOnlyPathReincludesConfirmedPublicationWithoutRawAuthorLink() {
        ScholardexPublicationView confirmed = publication("p2", "Paper 2");

        when(publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc("user@uvt.ro"))
                .thenReturn(List.of(decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED)));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(anyCollection())).thenReturn(List.of(confirmed));

        List<ScholardexPublicationView> result = service.findConfirmedPublicationsForScoring("user@uvt.ro");

        assertThat(result).extracting(ScholardexPublicationView::getId).containsExactly("p2");
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setScopusId(List.of("a1"));
        user.setResearcherProfile(profile);
        return user;
    }

    private ScholardexAuthorView author(String id) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        return author;
    }

    private ScholardexPublicationView publication(String id, String title) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setTitle(title);
        publication.setCoverDate("2024-01-01");
        return publication;
    }

    private PublicationAuthorshipDecision decision(String publicationId, PublicationAuthorshipDecision.Status status) {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setPublicationId(publicationId);
        decision.setStatus(status);
        decision.setUpdatedAt(Instant.parse("2026-04-16T12:00:00Z"));
        return decision;
    }
}
