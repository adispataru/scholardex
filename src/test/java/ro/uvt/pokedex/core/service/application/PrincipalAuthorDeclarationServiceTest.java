package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Action;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Kind;
import ro.uvt.pokedex.core.model.scopus.canonical.PrincipalAuthorDeclaration.Status;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.scopus.canonical.PrincipalAuthorDeclarationRepository;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.DeclarationException;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.Refusal;
import ro.uvt.pokedex.core.service.application.PrincipalAuthorDeclarationService.Role;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrincipalAuthorDeclarationServiceTest {

    private static final String ME = "researcher@uvt.ro";
    private static final String WHERE = "Footnote on the first page: these authors contributed equally.";

    @Mock
    private PrincipalAuthorDeclarationRepository repository;
    @Mock
    private EffectiveAuthorshipReadService authorship;
    @Mock
    private PrincipalAuthorDeclarationAccessService access;

    private PrincipalAuthorDeclarationService service;
    private final Authentication dean = as("dean@uvt.ro");

    @BeforeEach
    void setUp() {
        service = new PrincipalAuthorDeclarationService(repository, authorship, access);
        lenient().when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(authorship.findCanonicalAuthorIdsForUser(ME)).thenReturn(List.of("me"));
        lenient().when(authorship.findEffectivePublicationsForUser(ME)).thenReturn(List.of(
                publication("first", List.of("me", "x"), List.of()),
                publication("corresponding", List.of("x", "me"), List.of("me")),
                publication("coauthor", List.of("x", "me", "y"), List.of("x"))));
        lenient().when(repository.findByUserEmail(ME)).thenReturn(List.of());
        lenient().when(access.canDecide(eq(ME), any())).thenReturn(true);
    }

    private static Authentication as(String email) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(UserRole.RESEARCHER)));
        user.setSupervisorByPosition(true);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    private static ScholardexPublicationView publication(String id, List<String> authors, List<String> corresponding) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setTitle("Paper " + id);
        publication.setDoi("https://doi.org/10.1000/" + id);
        publication.setDoiNormalized("10.1000/" + id);
        publication.setCoverDate("2023-04-01");
        publication.setAuthors(authors);
        publication.setCorrespondingAuthorIds(new ArrayList<>(corresponding));
        return publication;
    }

    private static PrincipalAuthorDeclaration stored(String id, String publicationId, Status status) {
        PrincipalAuthorDeclaration declaration = new PrincipalAuthorDeclaration();
        declaration.setId(id);
        declaration.setUserEmail(ME);
        declaration.setPublicationId(publicationId);
        declaration.setDoiNormalized("10.1000/" + publicationId);
        declaration.setStatus(status);
        declaration.setKind(Kind.CORRESPONDING_AUTHOR);
        declaration.setEvidence(WHERE);
        return declaration;
    }

    private Refusal refusalOf(Runnable call) {
        return assertThrows(DeclarationException.class, call::run).refusal();
    }

    // ------------------------------------------------------------------ the researcher

    @Test
    void theWorkspaceShowsHowTheDataSeesEachPublication() {
        when(repository.findByUserEmail(ME)).thenReturn(List.of(stored("d1", "coauthor", Status.PENDING)));

        var states = service.stateFor(ME).byPublicationId();

        assertEquals(Role.FIRST_AUTHOR, states.get("first").role());
        assertEquals(Role.CORRESPONDING_AUTHOR, states.get("corresponding").role());
        assertEquals(Role.CO_AUTHOR, states.get("coauthor").role());
        assertEquals(Status.PENDING, states.get("coauthor").declaration());
        assertNull(states.get("first").declaration());
    }

    @Test
    void aCoAuthorDeclaresAndTheDeclarationWaits() {
        PrincipalAuthorDeclaration declaration =
                service.declare(ME, "coauthor", Kind.EQUAL_CONTRIBUTION, "  " + WHERE + "  ", " https://example.org/paper.pdf ");

        assertEquals(Status.PENDING, declaration.getStatus());
        assertEquals(Kind.EQUAL_CONTRIBUTION, declaration.getKind());
        assertEquals(ME, declaration.getUserEmail());
        assertEquals(WHERE, declaration.getEvidence());
        assertEquals("https://example.org/paper.pdf", declaration.getEvidenceUrl());
        // Remembered by more than its id, so that a rebuild cannot lose it.
        assertEquals("10.1000/coauthor", declaration.getDoiNormalized());
        assertEquals("paper coauthor", declaration.getTitleNormalized());
        assertEquals(2023, declaration.getYear());
        assertEquals(1, declaration.getHistory().size());
        assertEquals(Action.DECLARED, declaration.getHistory().getFirst().getAction());
        assertEquals(ME, declaration.getHistory().getFirst().getBy());
    }

    @Test
    void whoIsAlreadyAPrincipalAuthorHasNothingToDeclare() {
        assertEquals(Refusal.ALREADY_PRINCIPAL,
                refusalOf(() -> service.declare(ME, "first", Kind.CORRESPONDING_AUTHOR, WHERE, null)));
        assertEquals(Refusal.ALREADY_PRINCIPAL,
                refusalOf(() -> service.declare(ME, "corresponding", Kind.EQUAL_CONTRIBUTION, WHERE, null)));
    }

    @Test
    void aDeclarationIsOnlyAboutOnesOwnPublication() {
        assertEquals(Refusal.NOT_YOUR_PUBLICATION,
                refusalOf(() -> service.declare(ME, "somebody-elses", Kind.CORRESPONDING_AUTHOR, WHERE, null)));
        assertEquals(Refusal.NOT_YOUR_PUBLICATION,
                refusalOf(() -> service.declare(ME, null, Kind.CORRESPONDING_AUTHOR, WHERE, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void aDeclarationSaysWhatAndWhere() {
        assertEquals(Refusal.KIND_MISSING, refusalOf(() -> service.declare(ME, "coauthor", null, WHERE, null)));
        assertEquals(Refusal.EVIDENCE_TOO_SHORT,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, "  page 1 ", null)));
        assertEquals(Refusal.EVIDENCE_TOO_SHORT,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, null, null)));
        assertEquals(Refusal.EVIDENCE_TOO_LONG,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, "x".repeat(1001), null)));
        assertEquals(Refusal.LINK_NOT_A_WEB_ADDRESS,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, WHERE, "javascript:alert(1)")));
        verify(repository, never()).save(any());
    }

    @Test
    void thereIsOneDeclarationPerPublication() {
        when(repository.findByUserEmail(ME)).thenReturn(List.of(stored("d1", "coauthor", Status.PENDING)));
        assertEquals(Refusal.ALREADY_PENDING,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, WHERE, null)));

        when(repository.findByUserEmail(ME)).thenReturn(List.of(stored("d1", "coauthor", Status.APPROVED)));
        assertEquals(Refusal.ALREADY_APPROVED,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, WHERE, null)));
    }

    @Test
    void aRejectedDeclarationCanBeMadeAgainAndKeepsItsHistory() {
        PrincipalAuthorDeclaration rejected = stored("d1", "coauthor", Status.REJECTED);
        rejected.setDecidedBy("dean@uvt.ro");
        rejected.setDecisionNote("The footnote names somebody else.");
        rejected.getHistory().add(PrincipalAuthorDeclaration.Event.of(Action.DECLARED, ME, "first try"));
        rejected.getHistory().add(PrincipalAuthorDeclaration.Event.of(Action.REJECTED, "dean@uvt.ro", "wrong footnote"));
        when(repository.findByUserEmail(ME)).thenReturn(List.of(rejected));

        PrincipalAuthorDeclaration again = service.declare(ME, "coauthor", Kind.EQUAL_CONTRIBUTION, WHERE, null);

        assertEquals("d1", again.getId(), "the same record");
        assertEquals(Status.PENDING, again.getStatus());
        assertNull(again.getDecidedBy());
        assertNull(again.getDecisionNote());
        assertEquals(List.of(Action.DECLARED, Action.REJECTED, Action.DECLARED),
                again.getHistory().stream().map(PrincipalAuthorDeclaration.Event::getAction).toList());
    }

    @Test
    void theResearcherTakesADeclarationBack() {
        when(repository.findByUserEmail(ME)).thenReturn(List.of(stored("d1", "coauthor", Status.APPROVED)));

        PrincipalAuthorDeclaration withdrawn = service.withdraw(ME, "coauthor");

        assertEquals(Status.WITHDRAWN, withdrawn.getStatus());
        assertEquals(Action.WITHDRAWN, withdrawn.getHistory().getLast().getAction());

        when(repository.findByUserEmail(ME)).thenReturn(List.of(stored("d1", "coauthor", Status.REJECTED)));
        assertEquals(Refusal.NOTHING_TO_WITHDRAW, refusalOf(() -> service.withdraw(ME, "coauthor")));
        when(repository.findByUserEmail(ME)).thenReturn(List.of());
        assertEquals(Refusal.NOTHING_TO_WITHDRAW, refusalOf(() -> service.withdraw(ME, "coauthor")));
    }

    @Test
    void aDeclarationIsStillFoundAfterARebuildGaveThePublicationANewId() {
        // Made when the publication was "spub_old"; the corpus now knows it as "coauthor", same DOI.
        PrincipalAuthorDeclaration old = stored("d1", "spub_old", Status.APPROVED);
        old.setDoiNormalized("10.1000/coauthor");
        when(repository.findByUserEmail(ME)).thenReturn(List.of(old));

        assertEquals(Status.APPROVED, service.stateFor(ME).byPublicationId().get("coauthor").declaration());
        assertEquals(Refusal.ALREADY_APPROVED,
                refusalOf(() -> service.declare(ME, "coauthor", Kind.CORRESPONDING_AUTHOR, WHERE, null)));
        assertEquals(Status.WITHDRAWN, service.withdraw(ME, "coauthor").getStatus());
    }

    // ------------------------------------------------------------------ the head

    @Test
    void aHeadApprovesAndTheRecordSaysWhoAndWhen() {
        when(repository.findById("d1")).thenReturn(Optional.of(stored("d1", "coauthor", Status.PENDING)));

        PrincipalAuthorDeclaration approved = service.approve("d1", dean, "  Checked in the PDF. ");

        assertEquals(Status.APPROVED, approved.getStatus());
        assertEquals("dean@uvt.ro", approved.getDecidedBy());
        assertNotNull(approved.getDecidedAt());
        assertEquals("Checked in the PDF.", approved.getDecisionNote());
        assertEquals(Action.APPROVED, approved.getHistory().getLast().getAction());
        assertEquals("dean@uvt.ro", approved.getHistory().getLast().getBy());
    }

    @Test
    void anApprovalNeedsNoRemarkARejectionNeedsAReason() {
        when(repository.findById("d1")).thenReturn(Optional.of(stored("d1", "coauthor", Status.PENDING)));
        assertNull(service.approve("d1", dean, " ").getDecisionNote());

        when(repository.findById("d2")).thenReturn(Optional.of(stored("d2", "coauthor", Status.PENDING)));
        assertEquals(Refusal.NOTE_REQUIRED, refusalOf(() -> service.reject("d2", dean, "  ")));
        assertEquals(Refusal.NOTE_TOO_LONG, refusalOf(() -> service.reject("d2", dean, "x".repeat(1001))));
        assertEquals(Status.REJECTED, service.reject("d2", dean, "Not stated in the article.").getStatus());
    }

    @Test
    void onlyWhoAnswersForTheResearcherDecides() {
        when(repository.findById("d1")).thenReturn(Optional.of(stored("d1", "coauthor", Status.PENDING)));
        Authentication stranger = as("director.other@uvt.ro");
        when(access.canDecide(ME, stranger)).thenReturn(false);

        assertEquals(Refusal.NOT_ALLOWED, refusalOf(() -> service.approve("d1", stranger, null)));
        assertEquals(Refusal.NOT_ALLOWED, refusalOf(() -> service.reject("d1", stranger, "no")));
        verify(repository, never()).save(any());
    }

    @Test
    void aDecisionIsTakenOnce() {
        when(repository.findById("d1")).thenReturn(Optional.of(stored("d1", "coauthor", Status.APPROVED)));
        assertEquals(Refusal.NOT_PENDING, refusalOf(() -> service.approve("d1", dean, null)));
        assertEquals(Refusal.NOT_PENDING, refusalOf(() -> service.reject("d1", dean, "late")));
        assertEquals(Refusal.NOT_FOUND, refusalOf(() -> service.approve("gone", dean, null)));
        assertEquals(Refusal.NOT_FOUND, refusalOf(() -> service.approve(null, dean, null)));
    }

    @Test
    void anApprovalCanBeTakenBackWithAReason() {
        when(repository.findById("d1")).thenReturn(Optional.of(stored("d1", "coauthor", Status.APPROVED)));

        assertEquals(Refusal.NOTE_REQUIRED, refusalOf(() -> service.revoke("d1", dean, null)));
        PrincipalAuthorDeclaration revoked = service.revoke("d1", dean, "The published version names another author.");

        assertEquals(Status.REJECTED, revoked.getStatus());
        assertEquals(Action.REVOKED, revoked.getHistory().getLast().getAction());

        when(repository.findById("d2")).thenReturn(Optional.of(stored("d2", "coauthor", Status.PENDING)));
        assertEquals(Refusal.NOT_APPROVED, refusalOf(() -> service.revoke("d2", dean, "nothing to take back")));
    }

    @Test
    void aHeadSeesTheDeclarationsOfTheirOwnResearchersOnly() {
        PrincipalAuthorDeclaration mine = stored("d1", "coauthor", Status.PENDING);
        PrincipalAuthorDeclaration elsewhere = stored("d2", "other", Status.PENDING);
        elsewhere.setUserEmail("far@uvt.ro");
        when(repository.findByStatusOrderByCreatedAtAsc(Status.PENDING)).thenReturn(List.of(mine, elsewhere));
        when(access.canDecide("far@uvt.ro", dean)).thenReturn(false);

        assertEquals(List.of(mine), service.pendingFor(dean));
        assertEquals(1, service.pendingCountFor(dean));
    }

    @Test
    void theRecordOfDecisionsIsBounded() {
        List<PrincipalAuthorDeclaration> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(stored("d" + i, "p" + i, i % 2 == 0 ? Status.APPROVED : Status.REJECTED));
        }
        when(repository.findByStatusInOrderByDecidedAtDesc(List.of(Status.APPROVED, Status.REJECTED))).thenReturn(many);

        assertEquals(3, service.decidedFor(dean, 3).size());
        assertEquals(5, service.decidedFor(dean, 100).size());
    }
}
