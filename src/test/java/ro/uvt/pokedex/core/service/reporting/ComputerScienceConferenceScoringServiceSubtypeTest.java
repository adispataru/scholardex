package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComputerScienceConferenceScoringServiceSubtypeTest {

    @Mock
    private ReportingLookupPort cacheService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;

    @Test
    void usesScopusSubtypeFallbackForConferenceBranch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Test Conference, TCONF");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void resolvesConferenceWhenNormalizedNameMatches() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 46th International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCategory());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICSE", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void ambiguousAcronymMatchFallsBackToScopus() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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

        ScholardexForumView forum = new ScholardexForumView();
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

        ScoringPublication publication = conferencePublication("forum-1", "2022-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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

        ScoringPublication publication = conferencePublication("forum-1", "2016-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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
    void resolvesConferenceWhenTrailingTitleCasedAcronymIsFollowedOnlyByYearNoise() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2017-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings 2017 17th IEEE ACM International Symposium on Cluster Cloud and Grid Computing Ccgrid 2017");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("CCGRID");
        ranking.setName("IEEE International Symposium on Cluster, Cloud and Grid Computing");
        CoreConferenceRanking.YearlyRanking rank2017 = new CoreConferenceRanking.YearlyRanking();
        rank2017.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2017, rank2017));
        when(cacheService.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCategory());
        assertEquals("CCGRID", service.getLastTraceForTests().resolvedAcronym());
    }

    @Test
    void fallsBackToPublicationYearWhenIndicatorHasNoScoreYearRange() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2016-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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
    void resolvesConferenceWhenTrailingTitleCasedTokenMatchesConferenceInitials() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void resolvesConferenceByNormalizedTitleWhenAcronymCandidatesAreMissing() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
        assertEquals("TITLE", score.getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertTrue(trace.titleLookupConsulted());
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.TITLE, trace.resolvedLookupStrategy());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void workshopOfParentConferenceHalvesResolvedConferenceScore() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("2023 IEEE International Conference on Pervasive Computing and Communications Workshops and Other Affiliated Events Percom Workshops 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("PERCOM")).thenReturn(List.of(
                ranking("PERCOM", "IEEE International Conference on Pervasive Computing and Communications", CoreConferenceRanking.Rank.A)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCategory());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals(true, trace.workshopAdjusted());
    }

    @Test
    void workshopNamedCoreConferenceDoesNotHalveResolvedConferenceScore() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
        assertEquals(null, score.getScoringInfo().get("workshopAdjusted"));
        assertEquals(false, service.getLastTraceForTests().workshopAdjusted());
    }

    @Test
    void titleFallbackIsNotUsedWhenAcronymMatchAlreadyResolves() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2024-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals("ACRONYM", score.getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.ACRONYM, trace.resolvedLookupStrategy());
        assertEquals(false, trace.titleLookupConsulted());
    }

    @Test
    void titleFallbackStillFallsBackWhenSeveralCoreRowsShareTheNormalizedTitle() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B),
                ranking("PPW", "ACM International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.C)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS,
                service.getLastTraceForTests().fallbackReason());
    }

    @Test
    void titleFallbackDoesNotResolveNearMatchesWithoutExactNormalizedTitleKey() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void rejectsTrailingTitleCasedTokenWhenItDoesNotMatchConferenceInitials() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Nope");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void trailingTitleCasedAcronymStillFallsBackWhenSeveralCoreRowsShareTheAcronym() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B),
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.A)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS,
                service.getLastTraceForTests().fallbackReason());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void resolvesConferenceWhenMongoYearlyRankingKeysAreStringified() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2016-10-10");
        ScholardexForumView forum = new ScholardexForumView();
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

        ScholardexForumView forum = new ScholardexForumView();
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

    @Test
    void lncsChapterUsesDblpConferenceFallbackWhenScopusVenueDoesNotResolve() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2024-05-10", "pub-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));
        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-1");
        evidence.setConferenceName("Proceedings of the International Conference on Software Engineering, ICSE 2024");
        when(dblpEvidenceRepository.findByPublicationId("pub-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCategory());
        assertEquals(2023, score.getYear());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        assertEquals("ICSE", score.getScoringInfo().get("matchedAcronym"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.getLastTraceForTests();
        assertTrue(trace.dblpConsulted());
        assertTrue(trace.dblpEvidenceFound());
        assertEquals("Proceedings of the International Conference on Software Engineering, ICSE 2024", trace.dblpConferenceTitle());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void lncsConferencePaperUsesDblpConferenceFallbackWhenScopusVenueDoesNotResolve() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-cp-1", null, "forum-1", "2016-01-01", null, "cp", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICA3PP");
        ranking.setName("International Conference on Algorithms and Architectures for Parallel Processing");
        CoreConferenceRanking.YearlyRanking rank2016 = new CoreConferenceRanking.YearlyRanking();
        rank2016.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2016, rank2016));
        when(cacheService.getConferenceRankings("ICA3PP")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-cp-1");
        evidence.setConferenceName("ICA3PP");
        when(dblpEvidenceRepository.findByPublicationId("pub-cp-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.getLastTraceForTests();
        assertTrue(trace.dblpConsulted());
        assertTrue(trace.dblpEvidenceFound());
        assertEquals("ICA3PP", trace.dblpConferenceTitle());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void lncsChapterUsesDecoratedDblpAcronymFallbackWhenScopusVenueDoesNotResolve() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2025-01-01", "pub-aina-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Data Engineering and Communications Technologies");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(cacheService.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-aina-1");
        evidence.setConferenceName("AINA (6)");
        when(dblpEvidenceRepository.findByPublicationId("pub-aina-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCategory());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals("AINA (6)", trace.dblpConferenceTitle());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void decoratedDblpAcronymFallsBackWhenSeveralCoreRowsShareTheAcronym() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2025-01-01", "pub-amb-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("AINA")).thenReturn(List.of(
                ranking("AINA", "International Conference on Advanced Information Networking and Applications (was ICOIN)", CoreConferenceRanking.Rank.B),
                ranking("AINA", "Alternative Network Applications", CoreConferenceRanking.Rank.C)
        ));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-amb-1");
        evidence.setConferenceName("AINA (6)");
        when(dblpEvidenceRepository.findByPublicationId("pub-amb-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.LNCS_SPECIAL_CASE, service.getLastTraceForTests().fallbackReason());
    }

    @Test
    void decoratedNonAcronymDblpTitleDoesNotUpgradeTheMatch() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2025-01-01", "pub-noisy-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-noisy-1");
        evidence.setConferenceName("Advanced Information Networking and Applications (6)");
        when(dblpEvidenceRepository.findByPublicationId("pub-noisy-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.LNCS_SPECIAL_CASE, service.getLastTraceForTests().fallbackReason());
    }

    @Test
    void lncsChapterWithoutDblpEvidenceKeepsLncsFallback() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2024-05-10", "pub-2");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(dblpEvidenceRepository.findByPublicationId("pub-2")).thenReturn(Optional.empty());

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("LNCS_SPECIAL_CASE", score.getScoringInfo().get("fallbackReason"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.getLastTraceForTests();
        assertTrue(trace.dblpConsulted());
        assertTrue(!trace.dblpEvidenceFound());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.LNCS_SPECIAL_CASE, trace.fallbackReason());
    }

    @Test
    void lncsChapterDoesNotUseDblpWhenScopusVenueAlreadyResolves() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2024-05-10", "pub-3");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science, International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));
        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCategory());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.getLastTraceForTests();
        assertTrue(!trace.dblpConsulted());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.SCOPUS, trace.resolvedSource());
        verifyNoInteractions(dblpEvidenceRepository);
    }

    private ScoringPublication conferencePublication(String forumId, String coverDate) {
        return new ScoringPublication(null, null, forumId, coverDate, null, "cp", List.of(), 0, null, null, null, 0, Set.of());
    }

    private ScoringPublication lncsChapterPublication(String forumId, String coverDate, String id) {
        return new ScoringPublication(id, null, forumId, coverDate, "ch", "ch", List.of(), 0, null, null, null, 0, Set.of());
    }

    private Indicator indicator(String scoreYearRange) {
        Indicator indicator = new Indicator();
        indicator.setScoreYearRange(scoreYearRange);
        return indicator;
    }

    private CoreConferenceRanking ranking(String acronym, String name, CoreConferenceRanking.Rank rank) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId(acronym + "-" + name);
        ranking.setAcronym(acronym);
        ranking.setName(name);
        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(rank);
        ranking.setYearlyRankings(Map.of(2023, yearlyRanking, 2024, yearlyRanking));
        return ranking;
    }
}
