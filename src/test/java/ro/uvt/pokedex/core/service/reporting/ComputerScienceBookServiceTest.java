package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ComputerScienceBookServiceTest {

    @Mock
    private SenseRankingRepository senseRankingRepository;
    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void chapterSubtypeHalvesSenseScoreAndSetsScopusSenseProvenance() {
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Springer"));
        when(senseRankingRepository.findAllByNameIgnoreCase("Springer")).thenReturn(List.of(ranking(SenseBookRanking.Rank.A)));

        Score score = service.getScore(publication("cp", "ch"), indicator());

        assertEquals(8.0, score.getScore());
        assertEquals("SCOPUS+SENSE", score.getScoringSource());
        assertEquals("A", score.getCoreRankingEquivalent());
        // The rank is a SENSE publisher category, flagged via the SENSE quartile sentinel (not CORE / NOT_FOUND).
        assertEquals("SENSE", score.getQuarter());
    }

    @Test
    void lncsSeriesChapterIsNotScoredAsBook() {
        // LNCS/LNAI proceedings (subtype "ch") are conference papers, not books — the book scorer must skip them
        // (otherwise the same paper is counted as both a book chapter and a conference).
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        ScholardexForumView lncs = new ScholardexForumView();
        lncs.setPublicationName("Lecture Notes in Computer Science");
        lncs.setPublisher("Springer");
        when(lookupPort.getForum("forum-1")).thenReturn(lncs);

        Score score = service.getScore(publication("cp", "ch"), indicator());

        assertEquals(0.0, score.getScore());
    }

    @Test
    void bookSubtypeUsesSenseScoreAndCacheAvoidsRepeatedRepoLookups() {
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Elsevier"));
        when(senseRankingRepository.findAllByNameIgnoreCase("Elsevier")).thenReturn(List.of(ranking(SenseBookRanking.Rank.B)));

        Score first = service.getScore(publication("bk", null), indicator());
        Score second = service.getScore(publication("bk", null), indicator());

        assertEquals(8.0, first.getScore());
        assertEquals(8.0, second.getScore());
        verify(senseRankingRepository, times(1)).findAllByNameIgnoreCase("Elsevier");
    }

    @Test
    void chapterFuzzyMatchesPalgraveMacmillanLtdToPalgraveAndScoresB() {
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Palgrave Macmillan Ltd."));
        when(senseRankingRepository.findAllByNameIgnoreCase("Palgrave Macmillan Ltd.")).thenReturn(List.of());
        SenseBookRanking palgrave = new SenseBookRanking();
        palgrave.setName("Palgrave");
        palgrave.setRanking(SenseBookRanking.Rank.B);
        SenseBookRanking distractor = new SenseBookRanking();
        distractor.setName("Some Other Press");
        distractor.setRanking(SenseBookRanking.Rank.D);
        when(senseRankingRepository.findAll()).thenReturn(List.of(distractor, palgrave));

        Score score = service.getScore(publication("ch", "ch"), indicator());

        assertEquals(4.0, score.getScore());
        assertEquals("SCOPUS+SENSE", score.getScoringSource());
        assertEquals("B", score.getCoreRankingEquivalent());
    }

    @Test
    void dRankSenseBookScoresTwoPointsAsCategoryD() {
        // CNATDCU perspective d.i: authored/edited book of SENSE category D/E/unlisted = 2p
        // (a chapter of the same category becomes 1p via the "ch" halving).
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Unlisted Publisher"));
        when(senseRankingRepository.findAllByNameIgnoreCase("Unlisted Publisher")).thenReturn(List.of(ranking(SenseBookRanking.Rank.D)));

        Score score = service.getScore(publication("bk", null), indicator());

        assertEquals(2.0, score.getScore());
        assertEquals("D", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+SENSE", score.getScoringSource());
        assertEquals("D", score.getScoringInfo().get("resolvedRank"));
    }

    @Test
    void dRankSenseChapterScoresOnePointAfterHalving() {
        // The same SENSE category D for a chapter ("ch") halves the book score: 2p -> 1p.
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        when(lookupPort.getForum("forum-1")).thenReturn(forumWithPublisher("Unlisted Publisher"));
        when(senseRankingRepository.findAllByNameIgnoreCase("Unlisted Publisher")).thenReturn(List.of(ranking(SenseBookRanking.Rank.D)));

        Score score = service.getScore(publication("ch", "ch"), indicator());

        assertEquals(1.0, score.getScore());
        assertEquals("D", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+SENSE", score.getScoringSource());
    }

    @Test
    void activityWithoutPublisherDoesNotScore() {
        ComputerScienceBookService service = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2024-01-01");
        activity.setReferenceFields(Map.of(Activity.ReferenceField.FORUM_NAME, "No Publisher"));

        Score score = service.getScore(activity, indicator());

        assertEquals(0.0, score.getScore());
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private ScoringPublication publication(String subtype, String scopusSubtype) {
        return new ScoringPublication(
                "pub-1",
                "eid-1",
                "forum-1",
                "2023-05-01",
                subtype,
                scopusSubtype,
                List.of("a1"),
                1,
                "10.1/x",
                null,
                "Book Title",
                0,
                java.util.Set.of()
        );
    }

    private ScholardexForumView forumWithPublisher(String publisher) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublisher(publisher);
        return forum;
    }

    private SenseBookRanking ranking(SenseBookRanking.Rank rank) {
        SenseBookRanking ranking = new SenseBookRanking();
        ranking.setRanking(rank);
        return ranking;
    }
}
