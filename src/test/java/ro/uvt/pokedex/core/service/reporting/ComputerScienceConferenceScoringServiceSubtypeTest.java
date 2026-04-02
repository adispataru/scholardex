package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComputerScienceConferenceScoringServiceSubtypeTest {

    @Mock
    private ReportingLookupPort cacheService;

    @Test
    void usesScopusSubtypeFallbackForConferenceBranch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = new Publication();
        publication.setForum("forum-1");
        publication.setCoverDate("2023-10-10");
        publication.setScopusSubtype("cp");
        publication.setSubtype(null);

        Forum forum = new Forum();
        forum.setPublicationName("Test Conference, TCONF");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void resolvesConferenceWhenNormalizedNameMatches() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings of the 46th International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCategory());
    }

    @Test
    void ambiguousAcronymMatchFallsBackToScopus() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("International Conference on Software Engineering, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR),
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS,
                service.diagnoseConferenceMatch(forum.getPublicationName(), 2023).fallbackReason());
    }

    @Test
    void weakSubstringCollisionIsRejected() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Conference on Software Tools, ICST");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICST")).thenReturn(List.of(
                ranking("ICST", "International Conference on Software Testing", CoreConferenceRanking.Rank.A_STAR)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES,
                service.diagnoseConferenceMatch(forum.getPublicationName(), 2023).fallbackReason());
    }

    @Test
    void tryResolveCoreScoreCanRecoverConferenceFromLncsStyleVenueWhenAcronymAndNameMatch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Forum forum = new Forum();
        forum.setPublicationName("Lecture Notes in Computer Science, International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));

        Optional<Score> score = service.tryResolveCoreScore(forum, 2024);

        assertEquals(true, score.isPresent());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.get().getCategory());
    }

    @Test
    void resolvesConferenceWhenExactAcronymMatchesAndNameDiffersBySingularPluralVariant() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2022-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings - 2022 IEEE 46th Annual Computers, Software, and Applications Conference, COMPSAC 2022");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("COMPSAC")).thenReturn(List.of(
                ranking("COMPSAC", "International Computer Software and Applications Conference", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
    }

    @Test
    void resolvesConferenceWhenExactAcronymMatchesAndProceedingsTitleAddsEditionNoise() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2016-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("CCGRID");
        ranking.setName("IEEE International Symposium on Cluster, Cloud and Grid Computing");
        CoreConferenceRanking.YearlyRanking rank2014 = new CoreConferenceRanking.YearlyRanking();
        rank2014.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking rank2017 = new CoreConferenceRanking.YearlyRanking();
        rank2017.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(
                2014, rank2014,
                2017, rank2017,
                2023, rank2023
        ));
        when(cacheService.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCategory());
        assertEquals(2016, score.getYear());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.diagnoseConferenceMatch(forum.getPublicationName(), 2016);
        assertEquals("CCGRID", trace.resolvedAcronym());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void fallsBackToPublicationYearWhenIndicatorHasNoScoreYearRange() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2016-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("CCGRID");
        ranking.setName("IEEE International Symposium on Cluster, Cloud and Grid Computing");
        CoreConferenceRanking.YearlyRanking rank2017 = new CoreConferenceRanking.YearlyRanking();
        rank2017.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(
                2017, rank2017,
                2023, rank2023
        ));
        when(cacheService.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));

        Indicator indicator = new Indicator();
        indicator.setScoreYearRange(null);

        Score score = service.getScore(publication, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCategory());
        assertEquals(2016, score.getYear());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void resolvesConferenceWhenMongoYearlyRankingKeysAreStringified() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2016-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("CCGRID");
        ranking.setName("IEEE International Symposium on Cluster, Cloud and Grid Computing");
        CoreConferenceRanking.YearlyRanking rank2017 = new CoreConferenceRanking.YearlyRanking();
        rank2017.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings((Map) Map.of(
                "2017", rank2017,
                "2023", rank2023
        ));
        when(cacheService.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));

        Indicator indicator = new Indicator();
        indicator.setScoreYearRange("IY");

        Score score = service.getScore(publication, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCategory());
        assertEquals(2016, score.getYear());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE,
                service.diagnoseConferenceMatch(forum.getPublicationName(), 2016).fallbackReason());
    }

    @Test
    void diagnoseConferenceMatchReportsNoClosestYear() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Forum forum = new Forum();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("CCGRID")).thenReturn(List.of(
                ranking("CCGRID", "IEEE International Symposium on Cluster, Cloud and Grid Computing", CoreConferenceRanking.Rank.A)
        ));

        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.diagnoseConferenceMatch(forum.getPublicationName(), 2035);

        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_CLOSEST_YEAR, trace.fallbackReason());
        assertEquals("CCGRID", trace.resolvedAcronym());
    }

    @Test
    void diagnoseConferenceMatchReportsNoCoreCandidates() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.diagnoseConferenceMatch("Totally Unknown Venue, UV2024", 2024);

        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES, trace.fallbackReason());
        assertTrue(trace.acronymCandidates().contains("UV2024") || trace.acronymCandidates().contains("UV"));
    }

    private Publication conferencePublication(String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setScopusSubtype("cp");
        publication.setSubtype(null);
        return publication;
    }

    private CoreConferenceRanking ranking(String acronym, String name, CoreConferenceRanking.Rank rank) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym(acronym);
        ranking.setName(name);
        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(rank);
        ranking.setYearlyRankings(Map.of(2023, yearlyRanking, 2024, yearlyRanking));
        return ranking;
    }
}
