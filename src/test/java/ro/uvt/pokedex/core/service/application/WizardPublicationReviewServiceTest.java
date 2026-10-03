package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.WizardPublicationReview;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.WizardPublicationReviewRepository;
import ro.uvt.pokedex.core.service.crossref.CrossrefClient;
import ro.uvt.pokedex.core.service.security.PrincipalAuthorDeclarationAccessService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WizardPublicationReviewServiceTest {

    private static final String ENTRY = "USER_DEFINED:PUBLICATION:abc";
    private static final String RESEARCHER = "ana.popescu@e-uvt.ro";

    @Mock private WizardPublicationReviewRepository reviewRepository;
    @Mock private UserDefinedPublicationFactRepository entryRepository;
    @Mock private ScholardexPublicationFactRepository publicationFactRepository;
    @Mock private ScholardexBookFactRepository bookFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexProjectionReadService projectionReadService;
    @Mock private CrossrefClient crossrefClient;
    @Mock private UserRepository userRepository;
    @Mock private PrincipalAuthorDeclarationAccessService access;

    private WizardPublicationReviewService service;
    private final Map<String, WizardPublicationReview> reviews = new HashMap<>();
    private UserDefinedPublicationFact entry;
    private ScholardexPublicationFact fact;

    @BeforeEach
    void setUp() {
        service = new WizardPublicationReviewService(reviewRepository, entryRepository, publicationFactRepository,
                bookFactRepository, sourceLinkService, projectionReadService, crossrefClient, userRepository, access);
        entry = new UserDefinedPublicationFact();
        entry.setSourceRecordId(ENTRY);
        entry.setTitle("Learning to rank venues");
        entry.setSubtype("ar");
        entry.setCoverDate("2024-03-01");
        entry.setForumSourceRecordId("USER_DEFINED:FORUM:j1");
        entry.setAuthorIds(List.of("sauth_ana"));
        entry.setAuthorCount(1);
        entry.setDoi("10.1/abc");
        entry.setWizardSubmitterEmail(RESEARCHER);
        fact = new ScholardexPublicationFact();
        fact.setId("spub_w");
        fact.setEid("USER_DEFINED:EID:abc");
        fact.setUserSourceId(ENTRY);
        fact.setForumId("sforum_j1");
        lenient().when(entryRepository.findBySourceRecordId(ENTRY)).thenReturn(Optional.of(entry));
        lenient().when(publicationFactRepository.findByUserSourceId(ENTRY)).thenReturn(Optional.of(fact));
        lenient().when(publicationFactRepository.findAllById(any())).thenReturn(List.of(fact));
        lenient().when(sourceLinkService.findByCanonical(ScholardexEntityType.PUBLICATION, "spub_w")).thenReturn(List.of());
        lenient().when(reviewRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(reviews.get(inv.<String>getArgument(0))));
        lenient().when(reviewRepository.existsById(anyString())).thenAnswer(inv -> reviews.containsKey(inv.<String>getArgument(0)));
        lenient().when(reviewRepository.save(any())).thenAnswer(inv -> {
            WizardPublicationReview r = inv.getArgument(0);
            reviews.put(r.getId(), r);
            return r;
        });
        ScholardexForumView journal = new ScholardexForumView();
        journal.setId("sforum_j1");
        journal.setPublicationName("Journal of Ranking");
        journal.setIssn("1234-5678");
        lenient().when(projectionReadService.findForumById("sforum_j1")).thenReturn(Optional.of(journal));
        User ana = new User();
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName("Ana");
        profile.setLastName("Popescu");
        ana.setResearcherProfile(profile);
        lenient().when(userRepository.findById(RESEARCHER)).thenReturn(Optional.of(ana));
    }

    private static ScholardexPublicationView view(String id, String eid) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(id);
        view.setEid(eid);
        return view;
    }

    private static CrossrefClient.Work work(String title, int year, String family, String issn) {
        return new CrossrefClient.Work(title, List.of(year), List.of(family), List.of("Journal of Ranking"),
                List.of(issn), List.of(), "journal-article");
    }

    // ── scoring ─────────────────────────────────────────────────────────────

    @Test
    void anUnverifiedWizardEntryDoesNotCountAndImportedOnesAreNotLookedAt() {
        List<ScholardexPublicationView> in = List.of(view("spub_s", "2-s2.0-1"), view("spub_w", "USER_DEFINED:EID:abc"));

        assertEquals(List.of("spub_s"), service.countable(in).stream().map(ScholardexPublicationView::getId).toList());

        List<ScholardexPublicationView> onlyImported = List.of(view("spub_s", "2-s2.0-1"));
        assertEquals(onlyImported, service.countable(onlyImported));
        verify(publicationFactRepository, org.mockito.Mockito.times(1)).findAllById(any());
    }

    @Test
    void anApprovedEntryCountsOnlyWhileItStatesWhatWasApproved() {
        WizardPublicationReview review = new WizardPublicationReview();
        review.setId(ENTRY);
        review.setStatus(WizardPublicationReview.Status.APPROVED);
        review.setFacts(WizardPublicationReviewService.factsOf(entry));
        reviews.put(ENTRY, review);
        List<ScholardexPublicationView> in = List.of(view("spub_w", "USER_DEFINED:EID:abc"));

        assertEquals(1, service.countable(in).size());

        entry.setAuthorIds(List.of("sauth_other", "sauth_ana")); // the author order changed after the approval
        assertEquals(0, service.countable(in).size());
    }

    @Test
    void anEntryAnotherSourceHoldsCountsAsThatSources() {
        ScholardexSourceLink openAlex = new ScholardexSourceLink();
        openAlex.setSource("OPENALEX");
        openAlex.setLinkState(ScholardexSourceLinkService.STATE_LINKED);
        when(sourceLinkService.findByCanonical(ScholardexEntityType.PUBLICATION, "spub_w")).thenReturn(List.of(openAlex));

        assertEquals(1, service.countable(List.of(view("spub_w", "USER_DEFINED:EID:abc"))).size());
        assertTrue(service.afterSubmit(ENTRY, RESEARCHER).isEmpty(), "no review for a publication a source holds");
    }

    // ── submission ──────────────────────────────────────────────────────────

    @Test
    void aDoiThatResolvesToTheSameWorkIsVerifiedAtOnce() {
        when(crossrefClient.work("10.1/abc")).thenReturn(Optional.of(work("Learning to Rank Venues", 2023, "Popescu", "12345678")));

        assertEquals(Optional.of(WizardPublicationReview.Status.VERIFIED), service.afterSubmit(ENTRY, RESEARCHER));
        assertEquals(WizardPublicationReviewService.CROSSREF, reviews.get(ENTRY).getDecidedBy());
        assertEquals(1, service.countable(List.of(view("spub_w", "USER_DEFINED:EID:abc"))).size());
    }

    @Test
    void aDoiOfAnotherWorkOrVenueWaitsForAHeadAndSaysWhy() {
        when(crossrefClient.work("10.1/abc")).thenReturn(Optional.of(work("Something else entirely", 2019, "Ionescu", "0000-0000")));

        assertEquals(Optional.of(WizardPublicationReview.Status.PENDING), service.afterSubmit(ENTRY, RESEARCHER));
        String note = reviews.get(ENTRY).getVerificationNote();
        assertTrue(note.contains("title") && note.contains("year") && note.contains("authors") && note.contains("ISSN"), note);
    }

    @Test
    void withoutADoiTheEntryWaitsAndCrossrefIsNotAsked() {
        entry.setDoi(null);

        assertEquals(Optional.of(WizardPublicationReview.Status.PENDING), service.afterSubmit(ENTRY, RESEARCHER));
        verify(crossrefClient, never()).work(any());
    }

    @Test
    void resubmittingTheSameEntryKeepsItsDecisionAndAChangedOneWaitsAgain() {
        entry.setDoi(null);
        service.afterSubmit(ENTRY, RESEARCHER);
        reviews.get(ENTRY).setStatus(WizardPublicationReview.Status.APPROVED);

        assertEquals(Optional.of(WizardPublicationReview.Status.APPROVED), service.afterSubmit(ENTRY, RESEARCHER));

        entry.setCoverDate("2021-01-01");
        assertEquals(Optional.of(WizardPublicationReview.Status.PENDING), service.afterSubmit(ENTRY, RESEARCHER));
    }

    // ── heads ───────────────────────────────────────────────────────────────

    @Test
    void aHeadDecidesTheEntryAsThePageShowedItAndNeverTheirOwn() {
        entry.setDoi(null);
        service.afterSubmit(ENTRY, RESEARCHER);
        Authentication head = new UsernamePasswordAuthenticationToken("head@e-uvt.ro", "x", List.of());
        String seen = WizardPublicationReviewService.factsOf(entry);
        when(access.canDecide(RESEARCHER, head)).thenReturn(true);

        WizardPublicationReviewService.ReviewRefused changed = assertThrows(WizardPublicationReviewService.ReviewRefused.class,
                () -> service.approve(ENTRY, head, null, "stale"));
        assertEquals(WizardPublicationReviewService.Refusal.CHANGED, changed.refusal());
        assertEquals(WizardPublicationReviewService.Refusal.NOTE_REQUIRED, assertThrows(WizardPublicationReviewService.ReviewRefused.class,
                () -> service.reject(ENTRY, head, " ", seen)).refusal());

        assertEquals(WizardPublicationReview.Status.APPROVED, service.approve(ENTRY, head, null, seen).getStatus());
        assertEquals("head@e-uvt.ro", reviews.get(ENTRY).getDecidedBy());
        assertEquals(WizardPublicationReview.Status.REJECTED, service.revoke(ENTRY, head, "not this book", seen).getStatus());

        Authentication self = new UsernamePasswordAuthenticationToken(RESEARCHER, "x", List.of());
        reviews.get(ENTRY).setStatus(WizardPublicationReview.Status.PENDING);
        assertEquals(WizardPublicationReviewService.Refusal.NOT_ALLOWED, assertThrows(WizardPublicationReviewService.ReviewRefused.class,
                () -> service.approve(ENTRY, self, null, seen)).refusal());
    }

    @Test
    void entriesMadeBeforeVerificationGetAPendingReviewOnce() {
        when(entryRepository.findAll()).thenReturn(List.of(entry));

        assertEquals(1, service.openMissingReviews());
        assertEquals(WizardPublicationReview.Status.PENDING, reviews.get(ENTRY).getStatus());
        assertEquals(RESEARCHER, reviews.get(ENTRY).getSubmitterEmail());
        assertEquals(0, service.openMissingReviews());
        verify(crossrefClient, never()).work(any());
    }

    @Test
    void titlesMatchWhateverTheCaseAndPunctuation() {
        assertTrue(WizardPublicationReviewService.sameTitle("Learning to Rank: Venues!", "learning to rank venues"));
        assertTrue(!WizardPublicationReviewService.sameTitle("Learning to rank venues", "Ranking venues by learning"));
        assertEquals("12345678", WizardPublicationReviewService.identifier("1234-5678"));
    }
}
