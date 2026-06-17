package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ComputerScienceJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
    }
    @Test
    void missingAisRankForYearDoesNotThrowAndFallsBackToLowerTier() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);

        ScoringPublication publication = publication("forum-1", null, "ar", null);

        ScholardexForumView forum = new ScholardexForumView();
        forum.setIssn("1234-5678");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, WoSRanking.Quarter.Q3));
        // No rankAis entry for LAST_YEAR on purpose.

        WoSRanking ranking = new WoSRanking();
        ranking.setId("j-1");
        ranking.setWebOfScienceCategoryIndex(Map.of("Computer Science, Theory & Methods - SCIE", rank));

        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("Computer Science, Theory & Methods - SCIE", 2023)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("Q3", score.getQuarter());
        assertEquals(2023, score.getYear());
    }

    @Test
    void journalAggregationAllowsWosScoringWhenScopusSubtypeIsConferencePaper() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = publication("forum-1", "2020-12-01", "cp", "cp");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setEIssn("2045-2322");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2020, WoSRanking.Quarter.Q2));
        rank.setRankAis(Map.of(2020, 50));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-sci-rep");
        ranking.setWebOfScienceCategoryIndex(Map.of("MULTIDISCIPLINARY SCIENCES - SCIE", rank));

        when(lookupPort.getRankingsByIssn("2045-2322")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("MULTIDISCIPLINARY SCIENCES - SCIE", 2020)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(4.0, score.getScore());
        assertEquals("B", score.getCoreRankingEquivalent());
        assertEquals("Q2", score.getQuarter());
        assertEquals("SCOPUS+WOS", score.getScoringSource());
    }

    @Test
    void activityJournalFallsBackToScopusWhenNoWosMatch() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort) {
            @Override
            protected ScholardexForumView getForumFromActivity(ActivityInstance activity) {
                ScholardexForumView forum = super.getForumFromActivity(activity);
                forum.setAggregationType("Journal");
                return forum;
            }
        };
        Indicator indicator = indicator("IY");
        when(lookupPort.getRankingsByIssn("1111-2222")).thenReturn(List.of());

        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-01-01");
        activity.setReferenceFields(Map.of(
                Activity.ReferenceField.FORUM_NAME, "Journal of No Matches",
                Activity.ReferenceField.FORUM_ISSN, "1111-2222"
        ));

        Score score = service.getScore(activity, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS", score.getScoringSource());
        assertNotNull(score.getScoringInfo());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void descriptionIsNonEmpty() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        assertNotNull(service.getDescription());
        assertEquals(false, service.getDescription().isBlank());
    }

    @Test
    void publicationJournalWithoutWosFallsBackToScopus() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Indicator indicator = indicator("IY");
        ScoringPublication publication = publication("forum-1", "2023-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setIssn("1000-1000");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1000-1000")).thenReturn(List.of());

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void q1AtTopThresholdUsesNonTopBranch() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Indicator indicator = indicator("IY");
        ScoringPublication publication = publication("forum-1", "2023-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setIssn("1000-1000");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, WoSRanking.Quarter.Q1));
        rank.setRankAis(Map.of(2023, 20)); // numTop when top=100
        WoSRanking ranking = new WoSRanking();
        ranking.setWebOfScienceCategoryIndex(Map.of("C1-SCIE", rank));
        when(lookupPort.getRankingsByIssn("1000-1000")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("C1-SCIE", 2023)).thenReturn(100);

        Score score = service.getScore(publication, indicator);
        assertEquals(8.0, score.getScore());
    }

    private ScoringPublication publication(String forumId, String coverDate, String subtype, String scopusSubtype) {
        return new ScoringPublication(
                "pub-1",
                "2-s2.0-85090497285",
                forumId,
                coverDate,
                subtype,
                scopusSubtype,
                java.util.List.of("a1"),
                1,
                "10.1000/pub-1",
                null,
                "Computer Science Journal",
                0,
                java.util.Set.of()
        );
    }

    private Indicator indicator(String yearRange) {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, yearRange);
        return indicator;
    }
}
