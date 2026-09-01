package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.SuspiciousAuthorshipState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPublicationFacadeTest {

    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    @Mock
    private PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;
    @Mock
    private SuspiciousAuthorshipTriageService suspiciousAuthorshipTriageService;
    @Mock
    private ScholardexSourceLinkService scholardexSourceLinkService;

    @Mock
    private ro.uvt.pokedex.core.repository.UserRepository userRepository;

    @InjectMocks
    private UserPublicationFacade facade;

    @Test
    void buildUserPublicationsViewBuildsMapsAndHIndex() {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId("p1");
        p.setTitle("T1");
        p.setForum("f1");
        p.setAuthors(new java.util.ArrayList<>());
        p.getAuthors().add("a1");
        p.setCitedbyCount(3);

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");

        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("r1")).thenReturn(List.of(p));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        var vm = vmOpt.get();
        assertEquals(1, vm.publications().size());
        assertEquals(1, vm.hIndex());
        assertEquals(3, vm.numCitations());
        assertEquals("a1", vm.authorMap().get("a1").getId());
        assertEquals("f1", vm.forumMap().get("f1").getId());
        assertTrue(vm.authorshipReviewStateByPublicationId().isEmpty());
    }

    @Test
    void buildWorkspacePublicationsViewIncludesPendingConfirmedAndRejectedReviewStates() {
        ScholardexPublicationView pending = publication("p1", "Pending", "2024-01-01", 1, "f1", List.of("a1"));
        ScholardexPublicationView confirmed = publication("p2", "Confirmed", "2024-01-01", 2, "f1", List.of("a1"));
        ScholardexPublicationView rejected = publication("p3", "Rejected", "2024-01-01", 3, "f1", List.of("a1"));

        when(effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser("r1"))
                .thenReturn(List.of(pending, confirmed, rejected));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));
        when(publicationAuthorshipDecisionService.findDecisionsForUserAndPublications(eq("r1"), anyCollection()))
                .thenReturn(List.of(
                        decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED, "confirmed"),
                        decision("p3", PublicationAuthorshipDecision.Status.REJECTED, "not mine")
                ));
        when(suspiciousAuthorshipTriageService.evaluatePendingSuspiciousAuthorship(eq("r1"), anyList(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "p1",
                        new SuspiciousAuthorshipState(List.of(
                                new SuspiciousAuthorshipState.Flag(
                                        SuspiciousAuthorshipState.Code.NAME_MISMATCH,
                                        "name mismatch"
                                )
                        ))
                ));

        var vmOpt = facade.buildWorkspacePublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        var states = vmOpt.get().authorshipReviewStateByPublicationId();
        assertEquals(PublicationAuthorshipReviewState.Status.PENDING, states.get("p1").status());
        assertEquals(PublicationAuthorshipReviewState.Status.CONFIRMED, states.get("p2").status());
        assertEquals(PublicationAuthorshipReviewState.Status.REJECTED, states.get("p3").status());
        assertEquals("not mine", states.get("p3").reason());
        assertEquals(1, vmOpt.get().pendingReviewCount());
        assertEquals(1, vmOpt.get().suspiciousPendingCount());
        assertEquals(0, vmOpt.get().recommendedPendingCount());
        assertEquals(SuspiciousAuthorshipState.Code.NAME_MISMATCH,
                vmOpt.get().suspiciousAuthorshipByPublicationId().get("p1").flags().get(0).code());
    }

    @Test
    void countPendingAuthorshipReviewsCountsUndecidedPublicationsOnly() {
        ScholardexPublicationView pending = publication("p1", "Pending", "2024-01-01", 1, "f1", List.of("a1"));
        ScholardexPublicationView confirmed = publication("p2", "Confirmed", "2024-01-01", 2, "f1", List.of("a1"));
        ScholardexPublicationView rejected = publication("p3", "Rejected", "2024-01-01", 3, "f1", List.of("a1"));

        when(effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser("r1"))
                .thenReturn(List.of(pending, confirmed, rejected));
        when(publicationAuthorshipDecisionService.findDecisionsForUserAndPublications(eq("r1"), anyCollection()))
                .thenReturn(List.of(
                        decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED, "confirmed"),
                        decision("p3", PublicationAuthorshipDecision.Status.REJECTED, "not mine")
                ));

        assertEquals(1, facade.countPendingAuthorshipReviews("r1"));
    }

    @Test
    void buildWorkspacePublicationsViewCountsRecommendedPendingSeparately() {
        ScholardexPublicationView safePending = publication("p1", "Pending", "2024-01-01", 1, "f1", List.of("a1"));
        ScholardexPublicationView suspiciousPending = publication("p2", "Suspicious", "2024-01-01", 1, "f1", List.of("a1"));

        when(effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser("r1"))
                .thenReturn(List.of(safePending, suspiciousPending));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));
        when(publicationAuthorshipDecisionService.findDecisionsForUserAndPublications(eq("r1"), anyCollection()))
                .thenReturn(List.of());
        when(suspiciousAuthorshipTriageService.evaluatePendingSuspiciousAuthorship(eq("r1"), anyList(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "p2",
                        new SuspiciousAuthorshipState(List.of(
                                new SuspiciousAuthorshipState.Flag(
                                        SuspiciousAuthorshipState.Code.NAME_MISMATCH,
                                        "name mismatch"
                                )
                        ))
                ));

        var vmOpt = facade.buildWorkspacePublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(2, vmOpt.get().pendingReviewCount());
        assertEquals(1, vmOpt.get().suspiciousPendingCount());
        assertEquals(1, vmOpt.get().recommendedPendingCount());
    }

    @Test
    void buildWorkspacePublicationsViewReincludesConfirmedPublicationWithoutRawAuthorLink() {
        ScholardexPublicationView confirmed = publication("p2", "Confirmed", "2024-01-01", 2, "f1", List.of("a1"));

        when(effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser("r1"))
                .thenReturn(List.of(confirmed));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));
        when(publicationAuthorshipDecisionService.findDecisionsForUserAndPublications(eq("r1"), anyCollection()))
                .thenReturn(List.of(decision("p2", PublicationAuthorshipDecision.Status.CONFIRMED, null)));
        when(suspiciousAuthorshipTriageService.evaluatePendingSuspiciousAuthorship(eq("r1"), anyList(), anyMap(), anyMap()))
                .thenReturn(Map.of());

        var vmOpt = facade.buildWorkspacePublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(List.of("p2"), vmOpt.get().publications().stream().map(ScholardexPublicationView::getId).toList());
        assertEquals(PublicationAuthorshipReviewState.Status.CONFIRMED,
                vmOpt.get().authorshipReviewStateByPublicationId().get("p2").status());
        assertTrue(vmOpt.get().suspiciousAuthorshipByPublicationId().isEmpty());
    }

    @Test
    void buildUserPublicationsViewDedupesByPublicationIdAcrossMultipleAuthors() {
        ScholardexPublicationView shared = new ScholardexPublicationView();
        shared.setId("p-shared");
        shared.setTitle("Shared");
        shared.setForum("f1");
        shared.setAuthors(List.of("a1", "a2"));
        shared.setCitedbyCount(2);
        shared.setCoverDate("2022-01-01");

        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("r1")).thenReturn(List.of(shared));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1"), author("a2")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(1, vmOpt.get().publications().size());
        assertEquals(2, vmOpt.get().numCitations());
    }

    @Test
    void buildUserPublicationsViewUsesDeterministicOrderingContract() {
        ScholardexPublicationView malformed = publication("p3", "Zeta", "bad-date", 1, "f1", List.of("a1"));
        ScholardexPublicationView newest = publication("p2", "Alpha", "2024-02-01", 1, "f1", List.of("a1"));
        ScholardexPublicationView sameYearHigherTitle = publication("p1", "Beta", "2024-01-10", 1, "f1", List.of("a1"));

        when(effectiveAuthorshipReadService.findEffectivePublicationsForUser("r1"))
                .thenReturn(List.of(malformed, sameYearHigherTitle, newest));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        var vmOpt = facade.buildUserPublicationsView("r1");

        assertTrue(vmOpt.isPresent());
        assertEquals(List.of("p2", "p1", "p3"), vmOpt.get().publications().stream().map(ScholardexPublicationView::getId).toList());
    }

    @Test
    void buildAuthorPublicationsViewBuildsSharedSummaryForCanonicalAuthorId() {
        ScholardexAuthorView selectedAuthor = author("sauth_1");
        selectedAuthor.setName("Alice");
        ScholardexPublicationView publication = publication("p1", "T1", "2024-01-01", 4, "f1", List.of("sauth_1", "a2"));

        when(scholardexProjectionReadService.findAuthorById("sauth_1")).thenReturn(Optional.of(selectedAuthor));
        when(scholardexProjectionReadService.findAllPublicationsByAuthorsContaining("sauth_1")).thenReturn(List.of(publication));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(selectedAuthor, author("a2")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1")));

        var vmOpt = facade.buildAuthorPublicationsView("sauth_1");

        assertTrue(vmOpt.isPresent());
        assertEquals(1, vmOpt.get().publications().size());
        assertEquals(1, vmOpt.get().hIndex());
        assertEquals(4, vmOpt.get().numCitations());
        assertEquals("Alice", vmOpt.get().authorMap().get("sauth_1").getName());
        verify(scholardexProjectionReadService).findAuthorById("sauth_1");
        verify(scholardexProjectionReadService).findAllPublicationsByAuthorsContaining("sauth_1");
    }

    @Test
    void buildAuthorPublicationsViewReturnsEmptyWhenAuthorMissing() {
        when(scholardexProjectionReadService.findAuthorById("missing")).thenReturn(Optional.empty());
        assertTrue(facade.buildAuthorPublicationsView("missing").isEmpty());
    }

    @Test
    void buildCitationsViewSortsCitationsDeterministically() {
        ScholardexPublicationView publication = publication("p1", "Main", "2023-01-01", 0, "f1", List.of("a1"));
        ScholardexPublicationView c1 = publication("c1", "Zulu", "bad-date", 0, "f2", List.of("a2"));
        ScholardexPublicationView c2 = publication("c2", "Alpha", "2024-01-01", 0, "f2", List.of("a3"));
        ScholardexPublicationView c3 = publication("c3", "Beta", "2024-01-01", 0, "f2", List.of("a4"));
        ScholardexCitationView link1 = citation("p1", "c1");
        ScholardexCitationView link2 = citation("p1", "c2");
        ScholardexCitationView link3 = citation("p1", "c3");

        when(scholardexProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(effectiveAuthorshipReadService.userEffectivelyOwnsPublication("user@uvt.ro", "p1")).thenReturn(true);
        when(scholardexProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of(link1, link2, link3));
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of("c1", "c2", "c3"))).thenReturn(List.of(c1, c3, c2));
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum("f1")));
        when(scholardexProjectionReadService.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1")));
        when(scholardexProjectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f2")));

        var vmOpt = facade.buildCitationsView("user@uvt.ro", "p1");

        assertTrue(vmOpt.isPresent());
        assertEquals(List.of("c2", "c3", "c1"), vmOpt.get().citations().stream().map(ScholardexPublicationView::getId).toList());
    }

    @Test
    void buildCitationsViewRejectsPublicationNotOwnedEffectively() {
        ScholardexPublicationView publication = publication("p1", "Main", "2023-01-01", 0, "f1", List.of("a1"));

        when(scholardexProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(effectiveAuthorshipReadService.userEffectivelyOwnsPublication("user@uvt.ro", "p1")).thenReturn(false);

        var vmOpt = facade.buildCitationsView("user@uvt.ro", "p1");

        assertTrue(vmOpt.isEmpty());
    }

    @Test
    void buildCitationsViewReturnsEmptyWhenForumMissing() {
        ScholardexPublicationView publication = publication("p1", "Main", "2023-01-01", 0, "f1", List.of("a1"));
        when(scholardexProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(effectiveAuthorshipReadService.userEffectivelyOwnsPublication("user@uvt.ro", "p1")).thenReturn(true);
        when(scholardexProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of());
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of())).thenReturn(List.of());
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.empty());

        assertTrue(facade.buildCitationsView("user@uvt.ro", "p1").isEmpty());
    }

    @Test
    void buildCitationsViewReturnsEmptyInsteadOfThrowingWhenPublicationHasNoForum() {
        // Reproduces prod NPE (publication spub_d8a54153be25ce599bd87183 has a null/blank forumId):
        // findForumById(null) used to construct List.of(null) and blow up with NPE before this fix.
        ScholardexPublicationView publication = publication("p1", "Main", "2023-01-01", 0, null, List.of("a1"));
        when(scholardexProjectionReadService.findPublicationByAnyId("p1")).thenReturn(Optional.of(publication));
        when(effectiveAuthorshipReadService.userEffectivelyOwnsPublication("user@uvt.ro", "p1")).thenReturn(true);
        when(scholardexProjectionReadService.findAllCitationsByCitedId("p1")).thenReturn(List.of());
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(List.of())).thenReturn(List.of());
        when(scholardexProjectionReadService.findForumById(null)).thenReturn(Optional.empty());

        assertTrue(facade.buildCitationsView("user@uvt.ro", "p1").isEmpty());
    }

    private static ScholardexPublicationView publication(String id, String title, String coverDate, int citedByCount, String forumId, List<String> authors) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setTitle(title);
        publication.setCoverDate(coverDate);
        publication.setCitedbyCount(citedByCount);
        publication.setForum(forumId);
        publication.setAuthors(authors);
        return publication;
    }

    private static ScholardexCitationView citation(String citedId, String citingId) {
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setCitedId(citedId);
        citation.setCitingId(citingId);
        return citation;
    }

    private static ScholardexForumView forum(String id) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(id);
        return forum;
    }

    private static ScholardexAuthorView author(String id) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        author.setName(id);
        return author;
    }

    private static PublicationAuthorshipDecision decision(String publicationId,
                                                          PublicationAuthorshipDecision.Status status,
                                                          String reason) {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setPublicationId(publicationId);
        decision.setStatus(status);
        decision.setReason(reason);
        return decision;
    }

    @Test
    void buildCitationsPerYearBucketsByCitingYearSplitsSelfCitationsAndFillsContinuousAxis() {
        // researcher's own publications carry the ids of the papers that cite them
        ScholardexPublicationView p1 = new ScholardexPublicationView();
        p1.setId("p1");
        p1.setCitedBy(new java.util.LinkedHashSet<>(List.of("c1", "c2")));
        ScholardexPublicationView p2 = new ScholardexPublicationView();
        p2.setId("p2");
        p2.setCitedBy(new java.util.LinkedHashSet<>(List.of("c3")));
        ScholardexPublicationView p3 = new ScholardexPublicationView();
        p3.setId("p3");
        p3.setCitedBy(new java.util.LinkedHashSet<>(List.of("c4", "c5")));

        // citing papers, dated by their own cover year; c2 is authored by the researcher (self-citation)
        var c1 = citing("c1", "2020-05-01", List.of("x"));
        var c2 = citing("c2", "2021-01-01", List.of("me", "y"));
        var c3 = citing("c3", "2021-06-01", List.of("z"));
        var c4 = citing("c4", "2023-01-01", List.of("w"));
        // c5 is intentionally NOT resolvable (outside our corpus) -> must be dropped
        when(scholardexProjectionReadService.findAllPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(c1, c2, c3, c4));

        var t = facade.buildCitationsPerYear(List.of(p1, p2, p3), java.util.Set.of("me"));

        assertEquals(List.of("2020", "2021", "2022", "2023"), t.years()); // continuous; 2022 filled with 0
        assertEquals(List.of(1, 2, 0, 1), t.inclSelf());
        assertEquals(List.of(1, 1, 0, 1), t.exclSelf());                  // the 2021 self-citation drops from excl
        assertEquals(4, t.total());                                        // c5 (unresolvable) not counted
    }

    @Test
    void buildCitationsPerYearIsEmptyWhenThereAreNoIncomingCitations() {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId("p1");
        var t = facade.buildCitationsPerYear(List.of(p), java.util.Set.of("me"));
        assertEquals(0, t.total());
        assertEquals(List.of(), t.years());
    }

    private static ScholardexPublicationView citing(String id, String coverDate, List<String> authorIds) {
        ScholardexPublicationView v = new ScholardexPublicationView();
        v.setId(id);
        v.setCoverDate(coverDate);
        v.setAuthorIds(new java.util.ArrayList<>(authorIds));
        return v;
    }
}
