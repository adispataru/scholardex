package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

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

    @Test
    void bkSubtypeFallsBackToEmptyScore() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Publication publication = new Publication();
        publication.setSubtype("bk");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService);
        verifyNoInteractions(bookScoringService);
    }

    @Test
    void chSubtypeFallsBackToEmptyScore() {
        ComputerScienceScoringService service = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                cacheService
        );

        Publication publication = new Publication();
        publication.setSubtype("ch");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService);
        verifyNoInteractions(bookScoringService);
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

        Publication publication = new Publication();
        publication.setSubtype("xx");
        publication.setId("p-1");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
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
        when(conferenceScoringService.getScore(any(Publication.class), any(Indicator.class))).thenReturn(conferenceScore);

        Publication publication = new Publication();
        publication.setScopusSubtype("cp");
        publication.setSubtype(null);
        publication.setId("p-2");

        Score score = service.getScore(publication, new Indicator());

        assertEquals(8.0, score.getScore());
        verify(conferenceScoringService, times(1)).getScore(any(Publication.class), any(Indicator.class));
        verifyNoInteractions(journalScoringService, bookScoringService);
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
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        verifyNoInteractions(journalScoringService, conferenceScoringService, bookScoringService);
    }

    @Test
    void unknownActivityForumTypeFallsBackToEmptyScore() {
        ComputerScienceScoringService service = activityAwareService("Magazine");

        Score score = service.getScore(new ActivityInstance(), new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCategory());
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
            protected Forum getForumFromActivity(ActivityInstance activity) {
                Forum forum = new Forum();
                forum.setAggregationType(aggregationType);
                return forum;
            }
        };
    }

    private Score score(double value, int year, String category, String quarter) {
        Score score = new Score();
        score.setScore(value);
        score.setYear(year);
        score.setCategory(category);
        score.setQuarter(quarter);
        return score;
    }
}
