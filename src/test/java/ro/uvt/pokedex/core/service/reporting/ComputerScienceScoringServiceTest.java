package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ComputerScienceScoringServiceTest {

    @Mock
    private ComputerScienceJournalScoringService journalScoringService;
    @Mock
    private ComputerScienceConferenceScoringService conferenceScoringService;
    @Mock
    private ComputerScienceBookService bookScoringService;
    @Mock
    private ReportingLookupPort cacheService;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(cacheService.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void bkSubtypeIsNotScoredByThisJournalConferenceFramework() {
        // The CS router (Info_B / Info_C, A*/A/B) dispatches only journals + conferences. Books ("bk") belong to
        // the SENSE book indicator (CS_SENSE), so here they yield an empty score — not a book-scorer delegation.
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        ScoringPublication publication = publication("p-bk", null, "bk", null);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        verifyNoInteractions(bookScoringService, journalScoringService, conferenceScoringService);
    }

    @Test
    void chSubtypeIsNotScoredByThisJournalConferenceFramework() {
        // Book chapters ("ch") must not double-count into the journal+conference indicator — empty score here.
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        ScoringPublication publication = publication("p-ch", null, "ch", null);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        verifyNoInteractions(bookScoringService, journalScoringService, conferenceScoringService);
    }

    @Test
    void bookAggregationDelegatesToBookScoringService() {
        Score bookScore = score(4.0, 2023, "C", "NOT_FOUND");
        when(bookScoringService.getScore(any(ActivityInstance.class), any(Indicator.class))).thenReturn(bookScore);
        ComputerScienceScoringService service = activityAwareService("Book");

        Score score = service.getScore(new ActivityInstance(), new Indicator());

        assertEquals(4.0, score.getScore());
        verify(bookScoringService, times(1)).getScore(any(ActivityInstance.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, conferenceScoringService);
    }

    @Test
    void bookSeriesAggregationDelegatesToBookScoringService() {
        Score bookScore = score(2.0, 2023, "D", "NOT_FOUND");
        when(bookScoringService.getScore(any(ActivityInstance.class), any(Indicator.class))).thenReturn(bookScore);
        ComputerScienceScoringService service = activityAwareService("Book Series");

        Score score = service.getScore(new ActivityInstance(), new Indicator());

        assertEquals(2.0, score.getScore());
        verify(bookScoringService, times(1)).getScore(any(ActivityInstance.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, conferenceScoringService);
    }

    @Test
    void unknownSubtypeFallsBackToEmptyScore() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        ScoringPublication publication = publication("p-1", null, "xx", null);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    @Test
    void scopusSubtypeConferenceDispatchWorksWhenSubtypeIsNull() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Score conferenceScore = score(8.0, 2023, "A", "CORE");
        when(conferenceScoringService.getScore(any(ScoringPublicationReadModel.class), any(Indicator.class))).thenReturn(conferenceScore);

        ScoringPublication publication = publication("p-2", null, null, "cp");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(8.0, score.getScore());
        verify(conferenceScoringService, times(1)).getScore(any(ScoringPublicationReadModel.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, bookScoringService);
    }

    @Test
    void journalAggregationOverridesConferenceSubtypeForPublicationDispatch() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Score journalScore = score(4.0, 2023, "B", "Q2");
        when(journalScoringService.getScore(any(ScoringPublicationReadModel.class), any(Indicator.class))).thenReturn(journalScore);

        ScoringPublication publication = publication("p-journal-1", "forum-1", "cp", "cp");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(4.0, score.getScore());
        verify(journalScoringService, times(1)).getScore(any(ScoringPublicationReadModel.class), any(Indicator.class));
        verifyNoInteractions(conferenceScoringService, bookScoringService);
    }

    @Test
    void conferenceAggregationOverridesArticleSubtypeForPublicationDispatch() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Score conferenceScore = score(2.0, 2023, "C", "NOT_FOUND");
        when(conferenceScoringService.getScore(any(ScoringPublicationReadModel.class), any(Indicator.class))).thenReturn(conferenceScore);

        ScoringPublication publication = publication("p-conf-1", "forum-2", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-2")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(2.0, score.getScore());
        verify(conferenceScoringService, times(1)).getScore(any(ScoringPublicationReadModel.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, bookScoringService);
    }

    @Test
    void nonResearchSubtypeInJournalForumIsRejectedAndScoresNothing() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        // An editorial ("ed") sitting in a Journal forum must NOT inherit the journal
        // scoring path — only original contributions (ar/re/cp/ch/bk) are scored.
        ScoringPublication publication = publication("p-ed-1", "forum-ed", "ed", "ed");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        // Rejected before forum lookup or any delegate is consulted.
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    @Test
    void lncsBookSeriesKeepsConferenceDispatchForConferencePaperSubtype() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Score conferenceScore = score(4.0, 2023, "B", "NOT_FOUND");
        when(conferenceScoringService.getScore(any(ScoringPublicationReadModel.class), any(Indicator.class))).thenReturn(conferenceScore);

        ScoringPublication publication = publication("p-lncs-cp-1", "forum-lncs", "cp", "cp");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Book Series");
        when(cacheService.getForum("forum-lncs")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(4.0, score.getScore());
        verify(conferenceScoringService, times(1)).getScore(any(ScoringPublicationReadModel.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, bookScoringService);
    }

    @Test
    void scopusChapterLabelDoesNotMaskACrossrefConferencePaperOnABookSeriesForum() {
        // H99 item 2 (Florin's 44-vs-36): Scopus labels proceedings papers in book-series venues
        // (LNDECT/LNCS/CCIS) "ch", and the scopus-first crosswalk masked the Crossref "conference-paper"
        // type — so a not-yet-restamped proceedings paper dropped to NON_RANK under the combined CS
        // strategy while CS_CONFERENCE ranked it B on the same page. Either vocabulary saying
        // "conference paper" must reach the conference scorer.
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );
        Score conferenceScore = score(4.0, 2026, "B", "NOT_FOUND");
        when(conferenceScoringService.getScore(any(ScoringPublicationReadModel.class), any(Indicator.class))).thenReturn(conferenceScore);

        ScoringPublication publication = publication("p-lndect-1", "forum-lndect", "conference-paper", "ch");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Book Series");
        when(cacheService.getForum("forum-lndect")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(4.0, score.getScore());
        verify(conferenceScoringService, times(1)).getScore(any(ScoringPublicationReadModel.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, bookScoringService);
    }

    @Test
    void genuineBookChapterOnABookSeriesForumStaysOutOfTheFramework() {
        // Both vocabularies agree it is a chapter — it must keep yielding an empty score here (it belongs
        // to the SENSE book indicator), not ride the new conference-paper escape.
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        ScoringPublication publication = publication("p-chapter-1", "forum-lndect", "book-chapter", "ch");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Book Series");
        when(cacheService.getForum("forum-lndect")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    @Test
    void nullActivityFallsBackToEmptyScore() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Score score = service.getScore((ActivityInstance) null, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    @Test
    void unknownActivityForumTypeFallsBackToEmptyScore() {
        ComputerScienceScoringService service = activityAwareService("Magazine");

        Score score = service.getScore(new ActivityInstance(), new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    private ComputerScienceScoringService activityAwareService(String aggregationType) {
        return new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        ) {
            @Override
            protected ScholardexForumView getForumFromActivity(ActivityInstance activity) {
                ScholardexForumView forum = new ScholardexForumView();
                forum.setAggregationType(aggregationType);
                return forum;
            }
        };
    }

    private ScoringPublication publication(String id, String forumId, String subtype, String scopusSubtype) {
        return new ScoringPublication(
                id,
                "eid-" + id,
                forumId,
                "2023-01-01",
                subtype,
                scopusSubtype,
                java.util.List.of("a1"),
                1,
                "10.1000/" + id,
                null,
                id,
                0,
                java.util.Set.of()
        );
    }

    private Score score(double value, int year, String category, String quarter) {
        Score score = new Score();
        score.setScore(value);
        score.setYear(year);
        score.setCoreRankingEquivalent(category);
        score.setQuarter(quarter);
        return score;
    }
}
