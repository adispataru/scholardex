package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

class ComputerScienceScoringPipelineParityTest {

    @Test
    void scoringPipelineMatchesFrozenBaselineForMixedComputerScienceDataset() {
        ReportingLookupPort lookupPort = mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        SenseRankingRepository senseRankingRepository = mock(SenseRankingRepository.class);
        ScoringFactoryService scoringFactoryService = mock(ScoringFactoryService.class);

        ComputerScienceJournalScoringService journalService = new ComputerScienceJournalScoringService(lookupPort);
        ComputerScienceConferenceScoringService conferenceService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceBookService bookService = new ComputerScienceBookService(senseRankingRepository, lookupPort);
        ComputerScienceScoringService computerScienceScoringService =
                new ComputerScienceScoringService(journalService, conferenceService, bookService, lookupPort);
        ScientificProductionService scientificProductionService =
                new ScientificProductionService(scoringFactoryService, new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator());

        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        ScholardexForumView q1JournalForum = forum("Journal", "1111-1111", null,
                "Journal of Regression Safety");
        ScholardexForumView conferenceForum = forum("Conference Proceeding", null, null,
                "Proceedings of the International Conference on Software Engineering, ICSE 2024");
        ScholardexForumView scopusOnlyJournalForum = forum("Journal", "2222-2222", null,
                "Journal of Canonical Views");

        when(lookupPort.getForum("forum-j1")).thenReturn(q1JournalForum);
        when(lookupPort.getForum("forum-c1")).thenReturn(conferenceForum);
        when(lookupPort.getForum("forum-j2")).thenReturn(scopusOnlyJournalForum);

        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());
        when(lookupPort.getConferenceRankings("ICSE")).thenReturn(List.of(conferenceRanking("ICSE", CoreConferenceRanking.Rank.A_STAR, 2024)));

        when(lookupPort.getRankingsByIssn("1111-1111")).thenReturn(List.of(csRanking(
                "Computer Science, Software Engineering - SCIE",
                2023,
                WoSRanking.Quarter.Q1,
                10
        )));
        when(lookupPort.getTopRankings("Computer Science, Software Engineering - SCIE", 2023)).thenReturn(100);
        when(lookupPort.getRankingsByIssn("2222-2222")).thenReturn(List.of());
        when(lookupPort.getRankingsByIssn((String) null)).thenReturn(List.of());

        Indicator indicator = new Indicator();
        indicator.setOutputType("PUBLICATIONS");
        indicator.setScoringStrategy("CS");
        indicator.setScoreYearRange("IY");
        indicator.setFormula("S/max(N-2, 1)");
        Domain domain = new Domain();
        domain.setName("ALL");
        indicator.setDomain(domain);

        ScoringPublication q1Journal = publication(
                "pub-j1", "forum-j1", "2023-02-01", "ar", "ar", "Q1 Journal", List.of("a1"));
        ScoringPublication aStarConference = publication(
                "pub-c1", "forum-c1", "2024-05-01", "cp", "cp", "A* Conference", List.of("a2"));
        ScoringPublication scopusOnlyJournal = publication(
                "pub-j2", "forum-j2", "2023-06-01", "ar", "ar", "Scopus Journal", List.of("a3"));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(q1Journal, aStarConference, scopusOnlyJournal),
                indicator
        );

        assertEquals(12.0, result.get("Q1 Journal").getScore(), 0.0001);
        assertEquals("A_STAR", result.get("Q1 Journal").getCoreRankingEquivalent());
        assertEquals("Q1", result.get("Q1 Journal").getQuarter());

        assertEquals(12.0, result.get("A* Conference").getScore(), 0.0001);
        assertEquals("A_STAR", result.get("A* Conference").getCoreRankingEquivalent());
        assertEquals("NOT_FOUND", result.get("A* Conference").getQuarter());

        assertEquals(2.0, result.get("Scopus Journal").getScore(), 0.0001);
        assertEquals("C", result.get("Scopus Journal").getCoreRankingEquivalent());
        assertEquals("SCOPUS", result.get("Scopus Journal").getQuarter());

        assertEquals(26.0, result.get("total").getAuthorScore(), 0.0001);
    }

    private ScoringPublication publication(String id,
                                           String forumId,
                                           String coverDate,
                                           String subtype,
                                           String scopusSubtype,
                                           String title,
                                           List<String> authorIds) {
        return new ScoringPublication(
                id,
                "eid-" + id,
                forumId,
                coverDate,
                subtype,
                scopusSubtype,
                authorIds,
                authorIds.size(),
                "10.1000/" + id,
                null,
                title,
                0,
                Set.of()
        );
    }

    private ScholardexForumView forum(String aggregationType, String issn, String eIssn, String publicationName) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType(aggregationType);
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        forum.setPublicationName(publicationName);
        return forum;
    }

    private CoreConferenceRanking conferenceRanking(String acronym, CoreConferenceRanking.Rank rank, int year) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym(acronym);
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(rank);
        ranking.setYearlyRankings(Map.of(year, yearlyRanking));
        return ranking;
    }

    private WoSRanking csRanking(String category, int year, WoSRanking.Quarter quarter, int rankAis) {
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(year, quarter));
        rank.setRankAis(Map.of(year, rankAis));
        WoSRanking ranking = new WoSRanking();
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
