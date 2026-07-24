package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ComputerScienceConferenceScoringServiceSubtypeTest {

    @Mock
    private ReportingLookupPort cacheService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(cacheService.maxAvailableYear()).thenReturn(2023);
    }
    @Test
    void resolvesScopusBigDataProceedingsForumToCoreRankViaConcatenatedShortName() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // Real Scopus forum string for "IEEE International Conference on Big Data" (CORE acronym "BigData").
        // The acronym "BigData" appears as two words ("Big Data") and the long name is duplicated in the
        // trailing "<Short Name> <year>" fragment, defeating both acronym and exact-title lookups.
        ScoringPublication publication = conferencePublication("forum-bigdata", "2020-12-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings - 2020 IEEE International Conference on Big Data, Big Data 2020");
        when(cacheService.getForum("forum-bigdata")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("BigData-IEEE International Conference on Big Data");
        ranking.setAcronym("BigData");
        ranking.setName("IEEE International Conference on Big Data");
        CoreConferenceRanking.YearlyRanking rank2021 = new CoreConferenceRanking.YearlyRanking();
        rank2021.setRank(CoreConferenceRanking.Rank.B);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2021, rank2021, 2023, rank2023));
        when(cacheService.getConferenceRankings("BIGDATA")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("BigData", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void usesScopusSubtypeFallbackForConferenceBranch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Test Conference, TCONF");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void cpPaperInUntypedForumScoresZeroNotD() {
        // An unidentifiable venue (forum present but no aggregation type, name doesn't resolve to CORE) earns
        // nothing — a D here would be unverifiable points. Only a positively-typed Conference-Proceeding forum
        // floors at D.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-untyped", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Some Unindexed Venue"); // no aggregationType
        when(cacheService.getForum("forum-untyped")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringSource());
        // A real conference paper (candidate) that resolved no rank is NOT a venue-type mismatch.
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void doesNotApplyConferenceDFloorToCpPaperInJournalForum() {
        // A "cp" paper whose forum is a Journal (proceedings published as a journal special issue) is scored by
        // CS_JOURNAL; the conference scorer must NOT also add a spurious D, or the paper double-counts into both
        // the journal and conference breakdowns. DBLP-known conferences are already re-stamped onto conf/X forums,
        // so a paper still resident in a Journal forum is journal output here.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-jrnl", "2023-10-10");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Procedia Computer Science");
        forum.setAggregationType("Journal");
        when(cacheService.getForum("forum-jrnl")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringSource());
    }

    @Test
    void bookChapterWithSpringerDoiInRealBookSeriesIsNotScoredAsConference() {
        // A book chapter ("ch") with a Springer ISBN DOI (10.1007/978…) in a REAL book series ("Studies in Big
        // Data" — a Book-Series forum, NOT "Lecture Notes in…") is genuine book output, scored by CS_SENSE. The
        // 978 DOI alone (also carried by every Springer book chapter) must not pull it into the conference
        // indicator; only the LNCS-series forum name (or a Conference-Proceeding forum) makes a chapter a conference.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = new ScoringPublication(
                "pub-book-ch", null, "forum-book", "2023-01-01", "ch", "ch", List.of(), 0,
                "https://doi.org/10.1007/978-3-031-12345-6_7", null, "Evaluating Distributed Systems", 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Studies in Big Data");
        forum.setAggregationType("Book Series");
        when(cacheService.getForum("forum-book")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringSource());
    }

    @Test
    void directConferenceStrategyDoesNotApplyScopusFallbackToArticleSubtype() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = new ScoringPublication(
                "spub_d7b93a16fa805380cf901fef",
                null,
                "forum-journal",
                "2025-01-01",
                "ar",
                "ar",
                List.of(),
                0,
                null,
                null,
                "Development of a Job Satisfaction Index Based on Employees' Online Reviews of Their Employers: A Large Language Model Approach",
                0,
                Set.of()
        );
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("European Journal of Psychological Assessment");
        forum.setAggregationType("Journal");
        when(cacheService.getForum("forum-journal")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(0.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        assertNull(score.getScoringSource());
        // Not a conference contribution → the zero is explained as a venue-type mismatch.
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void resolvesViaDblpStreamAcronymWhenEvidenceCarriesOnlyTheConfStreamKey() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-cp-iccs", null, "forum-x", "2016-01-01", null, "cp",
                List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science"); // venue yields no usable acronym
        when(cacheService.getForum("forum-x")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICCS");
        ranking.setName("International Conference on Computational Science");
        CoreConferenceRanking.YearlyRanking rank2016 = new CoreConferenceRanking.YearlyRanking();
        rank2016.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2016, rank2016));
        when(cacheService.getConferenceRankings("ICCS")).thenReturn(List.of(ranking));

        // Evidence carries ONLY the conf/X stream key — no conferenceName/booktitle. Resolution must come from it.
        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-cp-iccs");
        evidence.setSeries("conf/iccs");
        when(dblpEvidenceRepository.findByPublicationId("pub-cp-iccs")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("ICCS", service.getLastTraceForTests().dblpConferenceTitle());
    }

    @Test
    void dblpAtSignWorkshopVolumeScoresAsWorkshopOfTheStreamParent2016() {
        // The ARMS-CC@PODC case: DBLP names co-located workshop volumes "X@Y" under the parent's conf/X
        // stream. The workshop's own name shares no words with the parent's CORE entry, so the generic
        // title match can never resolve it (it used to fall through to LNCS-C/2p) — the @-marker must
        // resolve the PARENT by stream acronym and force the workshop reduction: 2016 → category A, 6p.
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-armscc", null, "forum-armscc", "2023-01-01", null, "cp",
                List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("ARMS-CC@PODC");
        forum.setAggregationTypes(List.of("Conference Proceeding"));
        when(cacheService.getForum("forum-armscc")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("PODC")).thenReturn(List.of(
                ranking("PODC", "ACM Symposium on Principles of Distributed Computing", CoreConferenceRanking.Rank.A_STAR)));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-armscc");
        evidence.setSeries("conf/podc");
        evidence.setConferenceName("ARMS-CC@PODC");
        when(dblpEvidenceRepository.findByPublicationId("pub-armscc")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(6.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE(WS)", score.getScoringSource());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void dblpAtSignWorkshopVolumeScoresAsWorkshopOfTheStreamParent2026() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-armscc", null, "forum-armscc", "2023-01-01", null, "cp",
                List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("ARMS-CC@PODC");
        forum.setAggregationTypes(List.of("Conference Proceeding"));
        when(cacheService.getForum("forum-armscc")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("PODC")).thenReturn(List.of(
                ranking("PODC", "ACM Symposium on Principles of Distributed Computing", CoreConferenceRanking.Rank.A_STAR)));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-armscc");
        evidence.setSeries("conf/podc");
        evidence.setConferenceName("ARMS-CC@PODC");
        when(dblpEvidenceRepository.findByPublicationId("pub-armscc")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator2026("IY"));

        // 2026 (id_parA82): A* workshop → category C, points stay 6.
        assertEquals(6.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE(WS)", score.getScoringSource());
    }

    @Test
    void dblpMainTrackVolumeWithoutAtSignIsNotWorkshopReduced() {
        // Counter-case: a plain main-track DBLP record ("PODC") must keep full parent points.
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-podc", null, "forum-podc", "2023-01-01", null, "cp",
                List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("PODC");
        forum.setAggregationTypes(List.of("Conference Proceeding"));
        when(cacheService.getForum("forum-podc")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("PODC")).thenReturn(List.of(
                ranking("PODC", "ACM Symposium on Principles of Distributed Computing", CoreConferenceRanking.Rank.A_STAR)));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-podc");
        evidence.setSeries("conf/podc");
        evidence.setConferenceName("PODC");
        when(dblpEvidenceRepository.findByPublicationId("pub-podc")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", score.getScoringSource());
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
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICSE", score.getScoringInfo().get("matchedAcronym"));
        // CORE-resolved conferences carry the CORE quartile sentinel (not the NOT_FOUND default).
        assertEquals(WoSRanking.Quarter.CORE.toString(), score.getQuarter());
    }

    @Test
    void ambiguousAcronymMatchFallsBackToScopus() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("International Conference on Software Engineering, ICSE 2023");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR),
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS,
                service.diagnoseConferenceMatch(forum.getPublicationName(), 2023).fallbackReason());
    }

    @Test
    void weakSubstringCollisionIsRejected() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Conference on Software Tools, ICST");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICST")).thenReturn(List.of(
                ranking("ICST", "International Conference on Software Testing", CoreConferenceRanking.Rank.A_STAR)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.get().getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, null);

        Score score = service.getScore(publication, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
        assertEquals(2016, score.getYear());
    }

    @Test
    void resolvesConferenceWhenTrailingTitleCasedTokenMatchesConferenceInitials() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void resolvesConferenceByNormalizedTitleWhenAcronymCandidatesAreMissing() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
        assertEquals("TITLE", score.getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertTrue(trace.titleLookupConsulted());
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.TITLE, trace.resolvedLookupStrategy());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void workshopOfAStarConferenceScoresCategoryAWithSixPoints() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 45th International Conference on Software Engineering Workshops, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, indicator("IY"));

        // A* workshop → standard ladder: reported category A, 6 points (one rank down from A*).
        assertEquals(6.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void workshopOfAStarConference2026ScoresCategoryCWithSixPoints() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 45th International Conference on Software Engineering Workshops, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, indicator2026("IY"));

        // 2026 (id_parA82): A* workshop → category C (not A), points unchanged at 6.
        assertEquals(6.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void workshopOfAConference2026ScoresCategoryCWithFourPoints() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("2023 IEEE International Conference on Pervasive Computing and Communications Workshops and Other Affiliated Events Percom Workshops 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("PERCOM")).thenReturn(List.of(
                ranking("PERCOM", "IEEE International Conference on Pervasive Computing and Communications", CoreConferenceRanking.Rank.A)));

        Score score = service.getScore(publication, indicator2026("IY"));

        // 2026: A workshop → category C (2016 would say B); points stay 4.
        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE(WS)", score.getScoringSource());
    }

    // ── H80/A81: CORE national/regional conferences — category C in 2026, D in 2016 ──

    @Test
    void nationalConference2026ScoresCategoryCTwoPoints() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the ACM Information Technology Education Conference, SIGITE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("SIGITE")).thenReturn(List.of(
                ranking("SIGITE", "ACM Information Technology Education", CoreConferenceRanking.Rank.National)));

        Score score = service.getScore(publication, indicator2026("IY"));

        // 2026 (id_parA81): CORE national/regional → category C, 2 points.
        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void nationalConference2016ScoresCategoryDOnePoint() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the ACM Information Technology Education Conference, SIGITE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("SIGITE")).thenReturn(List.of(
                ranking("SIGITE", "ACM Information Technology Education", CoreConferenceRanking.Rank.National)));

        Score score = service.getScore(publication, indicator("IY")); // 2016 indicator

        // version-never-mutate: national stays category D, 1 point, under FV Info 2016.
        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
    }

    // ── H80/C1: posters & system demonstrations (id_parA82) — title-detected, 2026-gated ──

    @Test
    void posterOfAStarConference2026ScoresCategoryCWithSixPoints() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // Forum is the MAIN ICSE proceedings (no "workshop" token) — the poster signal is the TITLE, not the forum.
        ScoringPublication publication = posterPublication("forum-1", "2023-01-01", "Poster: Filtering Code Smells Detection Results");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 45th International Conference on Software Engineering, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, indicator2026("IY"));

        // 2026 (id_parA82): a poster at an A* conference is category C, 6 points — reduced like a workshop.
        assertEquals(6.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted")); // topAB reads this → dropped from top A*/A/B
    }

    @Test
    void posterOfAStarConference2016ScoresFullParentUnchanged() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = posterPublication("forum-1", "2023-01-01", "Poster: Filtering Code Smells Detection Results");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 45th International Conference on Software Engineering, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, indicator("IY")); // 2016 indicator — no poster reduction

        // version-never-mutate: posters keep scoring at full parent (A*, 12) under FV Info 2016.
        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void demonstrationResearchPaperIsNotTreatedAsADemoTrackItem() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // "Demonstration of …" is a real research paper — the loose word must NOT trigger the reduction.
        ScoringPublication publication = posterPublication("forum-1", "2023-01-01", "Demonstration of quantum synchronization based on second-order coherence");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 45th International Conference on Software Engineering, ICSE 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, indicator2026("IY"));

        assertEquals(12.0, score.getScore()); // full A* — not reduced
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
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
        // Workshop downgrade: parent A → reported category B (points stay the standard's 4).
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE(WS)", score.getScoringSource());
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals(true, trace.workshopAdjusted());
    }

    @Test
    void workshopNamedCoreConferenceDoesNotHalveResolvedConferenceScore() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
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
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());
        when(cacheService.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(
                ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B),
                ranking("PPW", "ACM International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.C)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS,
                service.getLastTraceForTests().fallbackReason());
    }

    @Test
    void titleFallbackDoesNotResolveNearMatchesWithoutExactNormalizedTitleKey() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void rejectsTrailingTitleCasedTokenWhenItDoesNotMatchConferenceInitials() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Nope");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void trailingTitleCasedAcronymStillFallsBackWhenSeveralCoreRowsShareTheAcronym() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B),
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.A)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
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
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Score score = service.getScore(publication, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals("AINA (6)", trace.dblpConferenceTitle());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void dblpEvidenceOverridesWorkshopNamedForumForMainTrackPaper() {
        // AINA 2021+ publishes workshop and main-track papers in ONE merged proceedings; our conf/aina stream
        // forum was accidentally named "AINA Workshops" after the first swept volume. The paper's OWN DBLP
        // booktitle ("AINA (5)") is the per-paper truth and must win: main conference, CORE B, NO reduction.
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-aina-main", null, "forum-1", "2024-04-01", null, "cp", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("AINA Workshops");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2024 = new CoreConferenceRanking.YearlyRanking();
        rank2024.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2024, rank2024));
        when(cacheService.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-aina-main");
        evidence.setConferenceName("AINA (5)");
        evidence.setSeries("conf/aina");
        when(dblpEvidenceRepository.findByPublicationId("pub-aina-main")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", score.getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals("AINA (5)", trace.dblpConferenceTitle());
        assertEquals(false, trace.workshopAdjusted());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void dblpEvidenceKeepsWorkshopReductionWhenPaperOwnBooktitleSaysWorkshops() {
        // The flip side of per-paper truth: a WAINA-era paper whose OWN DBLP booktitle is "AINA Workshops"
        // stays workshop-reduced even though it shares the stream forum with main-track papers.
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-waina", null, "forum-1", "2019-03-01", null, "cp", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("AINA");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2019 = new CoreConferenceRanking.YearlyRanking();
        rank2019.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2019, rank2019));
        when(cacheService.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-waina");
        evidence.setConferenceName("AINA Workshops");
        evidence.setSeries("conf/aina");
        when(dblpEvidenceRepository.findByPublicationId("pub-waina")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        // 2016 workshop downgrade: parent B → reported category C, workshop point ladder B→2.
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE(WS)", score.getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals("AINA Workshops", trace.dblpConferenceTitle());
        assertEquals(true, trace.workshopAdjusted());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
    }

    @Test
    void lncsConferencePaperUsesHyphenatedDblpWorkshopTitleToResolveParentConference() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication("pub-europar-1", null, "forum-1", "2020-01-01", null, "cp", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("EuroPar-International European Conference on Parallel and Distributed Computing");
        ranking.setAcronym("EuroPar");
        ranking.setName("International European Conference on Parallel and Distributed Computing");
        CoreConferenceRanking.YearlyRanking rank2020 = new CoreConferenceRanking.YearlyRanking();
        rank2020.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2020, rank2020));
        when(cacheService.getConferenceRankings("EUROPAR")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-europar-1");
        evidence.setConferenceName("Euro-Par Workshops");
        when(dblpEvidenceRepository.findByPublicationId("pub-europar-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        // Workshop downgrade: parent A → reported category B (points stay the standard's 4).
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE(WS)", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        assertEquals(true, score.getScoringInfo().get("workshopAdjusted"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertTrue(trace.acronymCandidates().contains("EUROPAR"));
        assertEquals("EuroPar", trace.resolvedAcronym());
        assertEquals("Euro-Par Workshops", trace.dblpConferenceTitle());
        assertEquals(true, trace.workshopAdjusted());
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
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
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
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
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
    void lncsChapterWithoutDblpEvidenceStillResolvesFromScopusVenueName() {
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
        when(dblpEvidenceRepository.findByPublicationId("pub-3")).thenReturn(Optional.empty());
        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.getLastTraceForTests();
        // per-paper truth: the evidence lookup ALWAYS runs first for conference candidates; with no
        // evidence the paper falls back to (and resolves from) the forum name exactly as before
        assertTrue(!trace.dblpConsulted());
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.SCOPUS, trace.resolvedSource());
        verify(dblpEvidenceRepository).findByPublicationId("pub-3");
    }

    @Test
    void activityWithoutForumNameReturnsDefaultScore() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ActivityInstance activity = activity("a-1", "2024-01-01", Map.of());

        Score score = service.getScore(activity, indicator("IY"));

        assertEquals(0.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        assertEquals(0, score.getYear());
        assertNull(score.getScoringSource());
    }

    @Test
    void activityLncsForumFallsBackToLncsSpecialCase() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ActivityInstance activity = activity(
                "a-2",
                "2024-01-01",
                Map.of(Activity.ReferenceField.FORUM_NAME, "Lecture Notes in Computer Science")
        );

        Score score = service.getScore(activity, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals(2023, score.getYear());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("LNCS_SPECIAL_CASE", score.getScoringInfo().get("fallbackReason"));
    }

    @Test
    void activityConferenceUsesResolvedCoreScoreAcrossAllowedYears() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ActivityInstance activity = activity(
                "a-3",
                "2022-01-01",
                Map.of(Activity.ReferenceField.EVENT_NAME, "International Conference on Software Engineering, ICSE 2022")
        );
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking rank2022 = new CoreConferenceRanking.YearlyRanking();
        rank2022.setRank(CoreConferenceRanking.Rank.B);
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2022, rank2022, 2023, rank2023));
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking));

        // H52 slice 11d.1: was "IY->IY+1" (relative grammar). Activity is 2022,
        // ranking covers 2022+2023; absolute range gives the same coverage.
        Indicator indicator = indicator("2022->2023");
        Score score = service.getScore(activity, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
        assertEquals(2023, score.getYear());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ACRONYM", score.getScoringInfo().get("coreLookupStrategy"));
    }

    @Test
    void decoratedAcronymOnlyTitleResolvesCoreMatch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2024-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("ICSE 2024 Proceedings");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICSE", score.getScoringInfo().get("matchedAcronym"));
        // CORE-resolved conferences carry the CORE quartile sentinel (not the NOT_FOUND default).
        assertEquals(WoSRanking.Quarter.CORE.toString(), score.getQuarter());
    }

    @Test
    void lncsNonConferenceSubtypeDoesNotConsultDblpFallback() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = new ScoringPublication(
                "pub-ar-1", null, "forum-1", "2024-05-10", "ar", "ar", List.of(), 0, null, null, null, 0, Set.of()
        );
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(0.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK.toString(), score.getCoreRankingEquivalent());
        assertNull(score.getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals(false, trace.dblpConsulted());
        verifyNoInteractions(dblpEvidenceRepository);
    }

    @Test
    void diagnoseConferenceMatchWithBlankTitleReportsNoForumName() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.diagnoseConferenceMatch("   ", 2024);

        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_FORUM_NAME, trace.fallbackReason());
    }

    @Test
    void lncsChapterUsesDblpBooktitleWhenConferenceNameIsBlank() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2024-05-10", "pub-booktitle-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-booktitle-1");
        evidence.setConferenceName("   ");
        evidence.setBooktitle("Proceedings of the International Conference on Software Engineering, ICSE 2024");
        when(dblpEvidenceRepository.findByPublicationId("pub-booktitle-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", score.getScoringSource());
        assertEquals("DBLP", score.getScoringInfo().get("matchSource"));
        assertEquals("ICSE", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void lncsChapterWithBlankDblpTitlesKeepsLncsFallback() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        ScoringPublication publication = lncsChapterPublication("forum-1", "2024-05-10", "pub-blank-1");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-1")).thenReturn(forum);

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-blank-1");
        evidence.setConferenceName(" ");
        evidence.setBooktitle(" ");
        when(dblpEvidenceRepository.findByPublicationId("pub-blank-1")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("LNCS_SPECIAL_CASE", score.getScoringInfo().get("fallbackReason"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = service.getLastTraceForTests();
        assertEquals(true, trace.dblpConsulted());
        assertEquals(true, trace.dblpEvidenceFound());
        assertEquals(null, trace.dblpConferenceTitle());
    }

    @Test
    void activityUnknownNonLncsForumUsesScopusFallback() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ActivityInstance activity = activity(
                "a-4",
                "2024-01-01",
                Map.of(Activity.ReferenceField.FORUM_NAME, "Unlisted Conference on Practical Systems")
        );

        Score score = service.getScore(activity, indicator("IY"));

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
        assertEquals(2023, score.getYear());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("NO_ACRONYM_CANDIDATES", score.getScoringInfo().get("fallbackReason"));
    }

    @Test
    void diagnoseConferenceMatchWithPunctuationOnlyTitleReportsNoForumName() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                service.diagnoseConferenceMatch(" --- , ... ", 2024);

        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_FORUM_NAME, trace.fallbackReason());
    }

    @Test
    void trailingTitleCasedAcronymBeforeIgnorableSuffixStillResolves() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp Workshops 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        // Workshop downgrade: parent B → reported category C (points stay the standard's 2).
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE(WS)", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void trailingTitleCasedAcronymWithRomanNumeralSuffixStillResolves() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp XIV");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));
        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
    }

    @Test
    void confidenceDisambiguatesAcronymCandidatesByNameFit() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Network Protocols, ICNP 2023");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B),
                ranking("ICNP", "Intercontinental Conference on Nuclear Physics", CoreConferenceRanking.Rank.A)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
        assertEquals("International Conference on Network Protocols", score.getScoringInfo().get("matchedTitle"));
    }

    @Test
    void exactAcronymDecoratedWithProceedingsSuffixStillResolves() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("ICNP Proceedings");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void exactAcronymDecoratedWithRomanNumeralSuffixStillResolves() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("ICNP XIV");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(4.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.B.toString(), score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", score.getScoringSource());
        assertEquals("ICNP", score.getScoringInfo().get("matchedAcronym"));
    }

    @Test
    void activityLectureNotesSpecialCaseFallsBackToLncs() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ActivityInstance activity = activity(
                "act-lncs",
                "2024-02-01",
                Map.of(Activity.ReferenceField.FORUM_NAME, "Lecture Notes in Computer Science")
        );

        Score score = service.getScore(activity, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
        assertEquals(2023, score.getYear());
    }

    @Test
    void internalConfidenceAndOutrankPathsAreCovered() throws Exception {
        Class<?> confidenceClass = Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence");
        Method fromScore = confidenceClass.getDeclaredMethod("fromScore", int.class);
        fromScore.setAccessible(true);

        Object none = Enum.valueOf((Class<Enum>) confidenceClass.asSubclass(Enum.class), "NONE");
        Object exactName = Enum.valueOf((Class<Enum>) confidenceClass.asSubclass(Enum.class), "EXACT_NORMALIZED_NAME");

        assertEquals(exactName, fromScore.invoke(null, 5));
        assertEquals(none, fromScore.invoke(null, 999));

        Class<?> acronymSourceClass = Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$AcronymSource");
        Object general = Enum.valueOf((Class<Enum>) acronymSourceClass.asSubclass(Enum.class), "GENERAL");
        Object comma = Enum.valueOf((Class<Enum>) acronymSourceClass.asSubclass(Enum.class), "LAST_COMMA_FRAGMENT");

        Class<?> acronymCandidateClass = Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$AcronymCandidate");
        Constructor<?> acCtor = acronymCandidateClass.getDeclaredConstructor(String.class, acronymSourceClass);
        acCtor.setAccessible(true);
        Object candidateGeneral = acCtor.newInstance("ICNP", general);
        Object candidateComma = acCtor.newInstance("ICNP", comma);

        Class<?> candidateMatchClass = Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$CandidateMatch");
        Constructor<?> cmCtor = candidateMatchClass.getDeclaredConstructor(acronymCandidateClass, confidenceClass);
        cmCtor.setAccessible(true);
        Object matchLow = cmCtor.newInstance(candidateGeneral, none);
        Object matchHigh = cmCtor.newInstance(candidateComma, exactName);
        assertNotNull(matchLow);
        assertNotNull(matchHigh);

        Method outranks = candidateMatchClass.getDeclaredMethod("outranks", candidateMatchClass);
        outranks.setAccessible(true);
        assertEquals(true, outranks.invoke(matchHigh, matchLow));
        assertEquals(false, outranks.invoke(matchLow, matchHigh));
    }

    @Test
    void exposesNonEmptyDescriptionContract() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        assertTrue(service.getDescription().contains("A* = 12p"));
    }

    @Test
    void helperBranchSweepCoversAcronymAndTokenUtilities() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        assertEquals(true, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "Icnp", "ICNP"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "ICNP", "ICNP"));

        assertEquals(true, invokePrivate(service, "isHyphenatedTitleCasedToken",
                new Class[]{String.class}, "Net-Work"));
        assertEquals(false, invokePrivate(service, "isHyphenatedTitleCasedToken",
                new Class[]{String.class}, "NET-work"));

        assertEquals("network", invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "Network"));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "2024"));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "Proceedings"));

        assertEquals(true, invokePrivate(service, "isIgnorableAcronymSuffixToken",
                new Class[]{String.class}, "xiv"));
        assertEquals(true, invokePrivate(service, "isIgnorableAcronymSuffixToken",
                new Class[]{String.class}, "workshops"));
        assertEquals(false, invokePrivate(service, "isIgnorableAcronymSuffixToken",
                new Class[]{String.class}, "protocols"));

        assertEquals("policy", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "policies"));
        assertEquals("classe", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "classes"));
        assertEquals("network", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "networks"));

        assertEquals(true, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("international", "conference", "network", "protocol"),
                Set.of("international", "conference", "network", "protocol")));
        assertEquals(false, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("network"), Set.of("international", "conference", "network", "protocol")));
    }

    @Test
    void dblpTitleResolutionAndCoreScorePrivatePathAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setConferenceName("Conference Name");
        evidence.setBooktitle("Booktitle");
        evidence.setSeries("Series");

        assertEquals("Conference Name", invokePrivate(service, "resolveDblpConferenceTitle",
                new Class[]{ScholardexPublicationDblpEvidence.class}, evidence));

        evidence.setConferenceName("   ");
        assertEquals("Booktitle", invokePrivate(service, "resolveDblpConferenceTitle",
                new Class[]{ScholardexPublicationDblpEvidence.class}, evidence));

        evidence.setBooktitle("   ");
        assertEquals(null, invokePrivate(service, "resolveDblpConferenceTitle",
                new Class[]{ScholardexPublicationDblpEvidence.class}, evidence));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("International Conference on Network Protocols, ICNP 2023");
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(
                ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B)
        ));
        Optional<Score> score = (Optional<Score>) invokePrivate(service, "computeCOREScore",
                new Class[]{ScholardexForumView.class, int.class}, forum, 2023);
        assertTrue(score.isPresent());
        assertEquals(4.0, score.get().getScore());
    }

    @Test
    void confidenceAndDecorationHelpersCoverRemainingBranches() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        CoreConferenceRanking ranking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);

        Object exactAcronymOnly = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "icnp", "ICNP", ranking);
        assertEquals("EXACT_ACRONYM_ONLY", ((Enum<?>) exactAcronymOnly).name());

        Object decorated = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "icnp proceedings", "ICNP", ranking);
        assertEquals("EXACT_ACRONYM_DECORATED", ((Enum<?>) decorated).name());

        Object splitDecorated = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "i c n p proceedings", "ICNP", ranking);
        assertEquals("NONE", ((Enum<?>) splitDecorated).name());

        Object exactNormalized = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "international conference on network protocols", "", ranking);
        assertEquals("EXACT_NORMALIZED_NAME", ((Enum<?>) exactNormalized).name());

        Object normalizedContains = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "the international conference on network protocols extended", "", ranking);
        assertEquals("NORMALIZED_CONTAINS", ((Enum<?>) normalizedContains).name());

        assertEquals(true, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp proceedings", "ICNP"));
        assertEquals(false, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp protocols", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "i c n p proceedings", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "i c x p proceedings", "ICNP"));
    }

    @Test
    void workshopAndRankingKeyBranchesAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        CoreConferenceRanking plainRanking = ranking("PERCOM", "IEEE International Conference on Pervasive Computing and Communications", CoreConferenceRanking.Rank.A);
        CoreConferenceRanking workshopRanking = ranking("WOPP", "IEEE International Conference on Parallel Processing Workshops", CoreConferenceRanking.Rank.B);

        assertEquals(true, invokePrivate(service, "isWorkshopVariant",
                new Class[]{String.class, CoreConferenceRanking.class},
                "PerCom Workshops 2023", plainRanking));
        assertEquals(false, invokePrivate(service, "isWorkshopVariant",
                new Class[]{String.class, CoreConferenceRanking.class},
                "Parallel Processing Workshops", workshopRanking));

        assertEquals("", invokePrivate(service, "conferenceRankingKey",
                new Class[]{CoreConferenceRanking.class}, (Object) null));
        assertEquals("PERCOM-IEEE International Conference on Pervasive Computing and Communications",
                invokePrivate(service, "conferenceRankingKey",
                        new Class[]{CoreConferenceRanking.class}, plainRanking));

        plainRanking.setId("core-123");
        assertEquals("core-123", invokePrivate(service, "conferenceRankingKey",
                new Class[]{CoreConferenceRanking.class}, plainRanking));
    }

    @Test
    void titleLookupAndAcronymHeuristicEdgeBranchesAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // isLikelyAcronymToken: uppercase-only, mixed-case, and rejected stopword.
        assertEquals(true, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "ICNP", "ICNP"));
        assertEquals(true, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "IcNp", "ICNP"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "Conference", "CONFERENCE"));

        // resolveConferenceMatchByTitle unresolved path (no candidates)
        Object trace = invokeTraceFactory("forPublication", null, null, null, "Some Venue", List.of(2023));
        Object unresolved = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "some venue", trace, ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES);
        Method resolvedMethod = unresolved.getClass().getDeclaredMethod("resolved");
        resolvedMethod.setAccessible(true);
        assertEquals(false, resolvedMethod.invoke(unresolved));

        // title lookup ambiguous path to touch winner-confidence/fallback branch.
        CoreConferenceRanking r1 = ranking("X1", "Very Similar Venue Name", CoreConferenceRanking.Rank.B);
        CoreConferenceRanking r2 = ranking("X2", "Very Similar Venue Name", CoreConferenceRanking.Rank.B);
        when(cacheService.getConferenceRankingsByNormalizedTitle("very similar venue name")).thenReturn(List.of(r1, r2));
        Object trace2 = invokeTraceFactory("forPublication", null, null, null, "Very Similar Venue Name", List.of(2023));
        Object ambiguous = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace2.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "very similar venue name", trace2, ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES);
        assertEquals(false, resolvedMethod.invoke(ambiguous));
    }

    @Test
    void exhaustiveHelperGuardBranchesAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // isTitleCasedAcronymLikeToken guard matrix
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "A", "A"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "VeryLongTokenName", "VERYLONGTOKENNAME"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "Conference", "CONFERENCE"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "", "ICNP"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "...", "ICNP"));

        // isHyphenatedTitleCasedToken extra branches
        assertEquals(false, invokePrivate(service, "isHyphenatedTitleCasedToken",
                new Class[]{String.class}, "Single"));
        assertEquals(false, invokePrivate(service, "isHyphenatedTitleCasedToken",
                new Class[]{String.class}, "Net--Work"));

        // normalizeInitialismSourceToken guard matrix
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "ICNP 2024"));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "21st"));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "ieee"));

        // isLikelyAcronymToken guard matrix
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "A", "A"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "2024", "2024"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "21ST", "21ST"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "Conference", "CONFERENCE"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "VeryVeryLongAcronym", "VERYVERYLONGACRONYM"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "", "ICNP"));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "Title", "TITLE"));

        // Decorated acronym helpers additional false branches
        assertEquals(false, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp", "ICNP"));
        assertEquals(true, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp x", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "i c n x proceedings", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "i c n p x", "ICNP"));

        // buildScoringInfo with ResolutionSource.NONE and no forum title
        Object trace = invokeTraceFactory("forPublication", null, null, null, null, List.of(2023));
        Object info = invokePrivate(service, "buildScoringInfo",
                new Class[]{trace.getClass(), Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource"), Integer.class, CoreConferenceRanking.Rank.class, boolean.class},
                trace,
                Enum.valueOf((Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource").asSubclass(Enum.class), "NONE"),
                0,
                null,
                false);
        assertTrue(((Map<?, ?>) info).isEmpty());
    }

    @Test
    void traceProvenanceAndTitleFallbackThresholdBranchesAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // applyTraceProvenance should replace previous scoringInfo content.
        Method init = AbstractForumScoringService.class.getDeclaredMethod("initializeScoreResult");
        init.setAccessible(true);
        Object scoreResult = init.invoke(service);
        var scoreResultClass = scoreResult.getClass();
        var scoringSourceField = scoreResultClass.getDeclaredField("scoringSource");
        scoringSourceField.setAccessible(true);
        var scoringInfoField = scoreResultClass.getDeclaredField("scoringInfo");
        scoringInfoField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> scoringInfo = (Map<String, Object>) scoringInfoField.get(scoreResult);
        scoringInfo.put("stale", "value");

        Object trace = invokeTraceFactory("forPublication", "pub-1", "cp", "2023-01-01", "Forum", List.of(2023));
        Method withResolvedConference = trace.getClass().getDeclaredMethod("withResolvedConference",
                String.class, String.class, String.class,
                Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence"));
        withResolvedConference.setAccessible(true);
        Object matchConfidence = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence").asSubclass(Enum.class),
                "EXACT_NORMALIZED_NAME"
        );
        trace = withResolvedConference.invoke(trace, "id-1", "Resolved Name", "ICNP", matchConfidence);

        Method withFallbackReason = trace.getClass().getDeclaredMethod("withFallbackReason",
                ComputerScienceConferenceScoringService.FallbackReason.class);
        withFallbackReason.setAccessible(true);
        trace = withFallbackReason.invoke(trace, ComputerScienceConferenceScoringService.FallbackReason.SCOPUS_FALLBACK);

        Method applyTrace = ComputerScienceConferenceScoringService.class.getDeclaredMethod("applyTraceProvenance",
                scoreResultClass, trace.getClass(), String.class);
        applyTrace.setAccessible(true);
        applyTrace.invoke(service, scoreResult, trace, "SCOPUS");

        @SuppressWarnings("unchecked")
        var sourceRef = (java.util.concurrent.atomic.AtomicReference<String>) scoringSourceField.get(scoreResult);
        assertEquals("SCOPUS", sourceRef.get());
        assertTrue(!scoringInfo.containsKey("stale"));
        assertEquals("SCOPUS_FALLBACK", scoringInfo.get("fallbackReason"));

        // resolveConferenceMatchByTitle below-threshold branch (candidate exists but confidence NONE).
        CoreConferenceRanking weak = ranking("W", "Completely Different Venue", CoreConferenceRanking.Rank.B);
        when(cacheService.getConferenceRankingsByNormalizedTitle("small venue")).thenReturn(List.of(weak));
        Object trace2 = invokeTraceFactory("forPublication", null, null, null, "Small Venue", List.of(2023));
        Object unresolved = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace2.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "small venue", trace2, ComputerScienceConferenceScoringService.FallbackReason.BELOW_THRESHOLD);
        Method traceAccessor = unresolved.getClass().getDeclaredMethod("trace");
        traceAccessor.setAccessible(true);
        Object resultTrace = traceAccessor.invoke(unresolved);
        Method fallbackAccessor = resultTrace.getClass().getDeclaredMethod("fallbackReason");
        fallbackAccessor.setAccessible(true);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.BELOW_THRESHOLD, fallbackAccessor.invoke(resultTrace));
    }

    @Test
    void reflectedConferenceMatchResolversCoverResolvedAndUnresolvedReturns() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // resolveConferenceMatch unresolved (blank forum title)
        Object trace = invokeTraceFactory("forPublication", null, null, null, "   ", List.of(2023));
        Object unresolved = invokePrivate(service, "resolveConferenceMatch",
                new Class[]{String.class, trace.getClass()}, "   ", trace);
        Method resolvedMethod = unresolved.getClass().getDeclaredMethod("resolved");
        Method rankingMethod = unresolved.getClass().getDeclaredMethod("ranking");
        resolvedMethod.setAccessible(true);
        rankingMethod.setAccessible(true);
        assertEquals(false, resolvedMethod.invoke(unresolved));
        assertEquals(null, rankingMethod.invoke(unresolved));

        // resolveConferenceMatchByTitle resolved branch (single exact normalized match)
        CoreConferenceRanking winner = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        when(cacheService.getConferenceRankingsByNormalizedTitle("international conference on network protocols"))
                .thenReturn(List.of(winner));
        Object trace2 = invokeTraceFactory("forPublication", null, null, null, "International Conference on Network Protocols", List.of(2023));
        Object resolved = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace2.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "international conference on network protocols",
                trace2,
                ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES);
        assertEquals(true, resolvedMethod.invoke(resolved));
        assertNotNull(rankingMethod.invoke(resolved));
    }

    @Test
    void helperTrueBranchesAndNullGuardsAreCovered() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        assertEquals(true, invokePrivate(service, "isTitleCasedAcronymLikeToken",
                new Class[]{String.class, String.class}, "Icnp", "ICNP"));
        assertEquals(true, invokePrivate(service, "isHyphenatedTitleCasedToken",
                new Class[]{String.class}, "Net-Work"));
        assertEquals(true, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "ICNP", "ICNP"));
        assertEquals(true, invokePrivate(service, "isIgnorableAcronymSuffixToken",
                new Class[]{String.class}, "proceedings"));
        assertEquals("",
                invokePrivate(service, "conferenceRankingKey", new Class[]{CoreConferenceRanking.class}, (Object) null));
        assertEquals(null,
                invokePrivate(service, "normalizeInitialismSourceToken", new Class[]{String.class}, "2024"));
    }

    @Test
    void scoringSelectsBestAcrossMultipleAllowedYears() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-1", "2023-10-10");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("International Conference on Software Engineering, ICSE 2023");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking y2023 = new CoreConferenceRanking.YearlyRanking();
        y2023.setRank(CoreConferenceRanking.Rank.B);
        CoreConferenceRanking.YearlyRanking y2024 = new CoreConferenceRanking.YearlyRanking();
        y2024.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2023, y2023, 2024, y2024));
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "2023->2024"); // H52 11d.1: was "IY,IY+1" (legacy relative grammar dropped in v1)

        Score score = service.getScore(publication, indicator);

        assertEquals(8.0, score.getScore());
        assertEquals(2024, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void reflectiveResolversAndConfidencePathsCoverNullReturnGuardedBranches() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        Object trace = invokeTraceFactory("forPublication", null, null, null, "ICNP proceedings", List.of(2023));

        Object unresolved = invokePrivate(service, "resolveConferenceMatch",
                new Class[]{String.class, trace.getClass()}, "ICNP proceedings", trace);
        Method traceAccessor = unresolved.getClass().getDeclaredMethod("trace");
        traceAccessor.setAccessible(true);
        Object unresolvedTrace = traceAccessor.invoke(unresolved);
        Method fallbackAccessor = unresolvedTrace.getClass().getDeclaredMethod("fallbackReason");
        fallbackAccessor.setAccessible(true);
        assertNotNull(fallbackAccessor.invoke(unresolvedTrace));

        CoreConferenceRanking ranking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        Object confidence = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "icnp proceedings", "ICNP", ranking);
        assertNotNull(confidence);
    }

    @Test
    void scoreMatchConfidenceCoversTokenSetAndSupersetVariants() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        CoreConferenceRanking ranking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);

        Object tokenSetEqual = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "network protocol international conference", "", ranking);
        assertEquals("TOKEN_SET_EQUAL", ((Enum<?>) tokenSetEqual).name());

        Object tokenSuperset = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "international security conference on network protocols", "", ranking);
        assertEquals("TOKEN_SUPERSET", ((Enum<?>) tokenSuperset).name());
    }

    @Test
    void normalizeComparableTokenAndOverlapThresholdBoundaries() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        assertEquals("buse", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "buses")); // protected 'ses' path keeps trailing 'e'
        assertEquals("classe", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "classes")); // protected 'sses' path

        assertEquals(false, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("international", "conference"),
                Set.of("international", "conference", "network", "protocol")));
        assertEquals(true, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("international", "conference", "network", "protocol", "systems"),
                Set.of("international", "conference", "network", "protocol")));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void buildScoringInfoPopulatesDblpAndTraceFields() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        Object trace = invokeTraceFactory("forPublication", "pub-1", "cp", "2024-01-01", "Forum", List.of(2023, 2024));

        Method withResolvedConference = trace.getClass().getDeclaredMethod("withResolvedConference",
                String.class, String.class, String.class,
                Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence"));
        withResolvedConference.setAccessible(true);
        Object matchConfidence = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence").asSubclass(Enum.class),
                "EXACT_ACRONYM_DECORATED"
        );
        trace = withResolvedConference.invoke(trace, "core-1", "Resolved Forum", "ICNP", matchConfidence);

        Method withResolvedLookupStrategy = trace.getClass().getDeclaredMethod("withResolvedLookupStrategy",
                Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$CoreLookupStrategy"));
        withResolvedLookupStrategy.setAccessible(true);
        Object lookupStrategy = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$CoreLookupStrategy").asSubclass(Enum.class),
                "TITLE"
        );
        trace = withResolvedLookupStrategy.invoke(trace, lookupStrategy);

        Method withDblpConsulted = trace.getClass().getDeclaredMethod("withDblpConsulted", boolean.class);
        withDblpConsulted.setAccessible(true);
        trace = withDblpConsulted.invoke(trace, true);
        Method withDblpEvidenceFound = trace.getClass().getDeclaredMethod("withDblpEvidenceFound", boolean.class);
        withDblpEvidenceFound.setAccessible(true);
        trace = withDblpEvidenceFound.invoke(trace, true);
        Method withDblpConferenceTitle = trace.getClass().getDeclaredMethod("withDblpConferenceTitle", String.class);
        withDblpConferenceTitle.setAccessible(true);
        trace = withDblpConferenceTitle.invoke(trace, "DBLP Conference");
        Method withFallbackReason = trace.getClass().getDeclaredMethod("withFallbackReason",
                ComputerScienceConferenceScoringService.FallbackReason.class);
        withFallbackReason.setAccessible(true);
        trace = withFallbackReason.invoke(trace, ComputerScienceConferenceScoringService.FallbackReason.NO_CLOSEST_YEAR);

        Object resolutionSource = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource").asSubclass(Enum.class),
                "DBLP"
        );

        Object info = invokePrivate(service, "buildScoringInfo",
                new Class[]{
                        trace.getClass(),
                        Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource"),
                        Integer.class,
                        CoreConferenceRanking.Rank.class,
                        boolean.class
                },
                trace, resolutionSource, 2024, CoreConferenceRanking.Rank.A, true);

        Map<?, ?> map = (Map<?, ?>) info;
        assertEquals("DBLP", map.get("matchSource"));
        assertEquals("Resolved Forum", map.get("matchedTitle"));
        assertEquals("ICNP", map.get("matchedAcronym"));
        assertEquals("core-1", map.get("matchedConferenceId"));
        assertEquals("TITLE", map.get("coreLookupStrategy"));
        assertEquals(true, map.get("workshopAdjusted"));
        assertEquals(2024, map.get("resolvedYear"));
        assertEquals("A", map.get("resolvedRank"));
        assertEquals("NO_CLOSEST_YEAR", map.get("fallbackReason"));
        assertEquals(List.of("SCOPUS", "DBLP", "CORE"), map.get("sourcesConsulted"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void routingBooleanAndAcronymHelperNoCoverageBranches() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // scoreMatchConfidence: blank ranking name guard + exact acronym strong overlap tail
        CoreConferenceRanking blankName = new CoreConferenceRanking();
        blankName.setAcronym("ICNP");
        blankName.setName("   ");
        assertEquals("NONE", ((Enum<?>) invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "icnp", "ICNP", blankName)).name());

        CoreConferenceRanking overlapRanking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        Object overlapConfidence = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "international network protocol icnp symposium", "ICNP", overlapRanking);
        assertEquals("TOKEN_SUPERSET", ((Enum<?>) overlapConfidence).name());

        // split/decorated helper NO_COVERAGE guards
        assertEquals(false, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, null, "ICNP"));
        assertEquals(false, invokePrivate(service, "isDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, null, "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "icnp", "ICNP"));
        assertEquals(false, invokePrivate(service, "isSplitDecoratedAcronymOnlyTitle",
                new Class[]{String.class, String.class}, "i c n p", "ICNP"));

        // helper guard/terminal branches
        assertEquals(false, invokePrivate(service, "isIgnorableAcronymSuffixToken",
                new Class[]{String.class}, (Object) null));
        assertEquals(false, invokePrivate(service, "isLikelyAcronymToken",
                new Class[]{String.class, String.class}, "title", "TITLE"));
        assertEquals(false, invokePrivate(service, "isStrictTitleCaseWord",
                new Class[]{String.class}, "ICNP"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymBeforeIgnorableSuffix",
                new Class[]{String[].class, int.class, String.class},
                (Object) new String[]{"International", "Conference", "Icnp", "Else"},
                2, "ICNP"));
        assertEquals(-1, invokePrivate(service, "findTrailingSignificantTokenIndex",
                new Class[]{String[].class},
                (Object) new String[]{"2024", "21st", "proceedings", "workshops"}));

        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, (Object) null));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "..."));
        assertEquals(null, invokePrivate(service, "normalizeInitialismSourceToken",
                new Class[]{String.class}, "2024"));

        assertEquals(false, invokePrivate(service, "isWorkshopVariant",
                new Class[]{String.class, CoreConferenceRanking.class}, null, overlapRanking));

        assertEquals(null, invokePrivate(service, "resolveDblpConferenceTitle",
                new Class[]{Class.forName("ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence")},
                new Object[]{null}));

        CoreConferenceRanking keyRanking = new CoreConferenceRanking();
        keyRanking.setId(" ");
        keyRanking.setAcronym(null);
        keyRanking.setName(null);
        assertEquals("|", invokePrivate(service, "conferenceRankingKey",
                new Class[]{CoreConferenceRanking.class}, keyRanking));

        // buildScoringInfo DBLP matchedTitle fallback path (line 317 branch)
        Object trace = invokeTraceFactory("forPublication", "pub-2", "cp", "2024-01-01", "Forum Fallback", List.of(2024));
        Method withDblpConferenceTitle = trace.getClass().getDeclaredMethod("withDblpConferenceTitle", String.class);
        withDblpConferenceTitle.setAccessible(true);
        trace = withDblpConferenceTitle.invoke(trace, "DBLP Fallback Title");
        Object resolutionSource = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource").asSubclass(Enum.class),
                "DBLP"
        );
        Map<?, ?> info = (Map<?, ?>) invokePrivate(service, "buildScoringInfo",
                new Class[]{
                        trace.getClass(),
                        Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource"),
                        Integer.class,
                        CoreConferenceRanking.Rank.class,
                        boolean.class
                },
                trace, resolutionSource, 2024, CoreConferenceRanking.Rank.B, false);
        assertEquals("DBLP Fallback Title", info.get("matchedTitle"));

        // routing below-threshold path in resolveConferenceMatch (line 452)
        CoreConferenceRanking weak = ranking("ICNP", "Completely Different Venue", CoreConferenceRanking.Rank.C);
        when(cacheService.getConferenceRankings("ICNPX")).thenReturn(List.of(weak));
        Object trace2 = invokeTraceFactory("forPublication", null, null, null, "ICNPX", List.of(2024));
        Object unresolved = invokePrivate(service, "resolveConferenceMatch",
                new Class[]{String.class, trace2.getClass()}, "ICNPX", trace2);
        Method resolvedMethod = unresolved.getClass().getDeclaredMethod("resolved");
        resolvedMethod.setAccessible(true);
        assertEquals(false, resolvedMethod.invoke(unresolved));
        Method traceAccessor = unresolved.getClass().getDeclaredMethod("trace");
        traceAccessor.setAccessible(true);
        Object unresolvedTrace = traceAccessor.invoke(unresolved);
        Method fallbackAccessor = unresolvedTrace.getClass().getDeclaredMethod("fallbackReason");
        fallbackAccessor.setAccessible(true);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NO_CORE_CANDIDATES, fallbackAccessor.invoke(unresolvedTrace));
    }

    @Test
    void activityScorePrefersHighestYearAndDoesNotDowngradeOnLaterYear() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ActivityInstance activity = activity("act-1", "2023-06-01",
                Map.of(Activity.ReferenceField.EVENT_NAME, "International Conference on Software Engineering, ICSE 2023"));

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking y2023 = new CoreConferenceRanking.YearlyRanking();
        y2023.setRank(CoreConferenceRanking.Rank.A);
        CoreConferenceRanking.YearlyRanking y2024 = new CoreConferenceRanking.YearlyRanking();
        y2024.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, y2023, 2024, y2024));

        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking));

        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "2023->2024"); // H52 11d.1: was "IY,IY+1" (legacy relative grammar dropped in v1)

        Score score = service.getScore(activity, indicator);
        assertEquals(8.0, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void resolveConferenceMatchByTitleAtThresholdResolvesInsteadOfFallback() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        CoreConferenceRanking ranking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        when(cacheService.getConferenceRankingsByNormalizedTitle("international conference on network protocols proceedings"))
                .thenReturn(List.of(ranking));

        Object trace = invokeTraceFactory("forPublication", null, null, null,
                "International Conference on Network Protocols Proceedings", List.of(2023));
        Object resolved = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "international conference on network protocols proceedings",
                trace,
                ComputerScienceConferenceScoringService.FallbackReason.BELOW_THRESHOLD);

        Method resolvedMethod = resolved.getClass().getDeclaredMethod("resolved");
        resolvedMethod.setAccessible(true);
        assertEquals(true, resolvedMethod.invoke(resolved));
    }

    @Test
    void resolveConferenceMatchAmbiguousWinnerSetsAmbiguousFallback() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        CoreConferenceRanking r1 = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        CoreConferenceRanking r2 = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);
        r1.setId("r1");
        r2.setId("r2");
        when(cacheService.getConferenceRankings("ICNP")).thenReturn(List.of(r1, r2));

        Object trace = invokeTraceFactory("forPublication", null, null, null, "ICNP proceedings", List.of(2023));
        Object unresolved = invokePrivate(service, "resolveConferenceMatch",
                new Class[]{String.class, trace.getClass()}, "ICNP proceedings", trace);

        Method resolvedMethod = unresolved.getClass().getDeclaredMethod("resolved");
        resolvedMethod.setAccessible(true);
        assertEquals(false, resolvedMethod.invoke(unresolved));

        Method traceAccessor = unresolved.getClass().getDeclaredMethod("trace");
        traceAccessor.setAccessible(true);
        Object unresolvedTrace = traceAccessor.invoke(unresolved);
        Method fallbackAccessor = unresolvedTrace.getClass().getDeclaredMethod("fallbackReason");
        fallbackAccessor.setAccessible(true);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.AMBIGUOUS_WINNERS, fallbackAccessor.invoke(unresolvedTrace));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void buildScoringInfoAddsCoreSourceWhenResolvedConferenceIdExistsWithoutResolutionSource() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        Object trace = invokeTraceFactory("forPublication", "pub-core", "cp", "2024-01-01", "Forum", List.of(2024));

        Method withResolvedConference = trace.getClass().getDeclaredMethod("withResolvedConference",
                String.class, String.class, String.class,
                Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence"));
        withResolvedConference.setAccessible(true);
        Object confidence = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$MatchConfidence").asSubclass(Enum.class),
                "NORMALIZED_CONTAINS");
        trace = withResolvedConference.invoke(trace, "core-42", "Resolved", "ICNP", confidence);

        Object noneSource = Enum.valueOf(
                (Class<Enum>) Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource").asSubclass(Enum.class),
                "NONE");

        Map<?, ?> info = (Map<?, ?>) invokePrivate(service, "buildScoringInfo",
                new Class[]{
                        trace.getClass(),
                        Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ResolutionSource"),
                        Integer.class,
                        CoreConferenceRanking.Rank.class,
                        boolean.class
                },
                trace, noneSource, 2024, null, false);

        assertEquals(List.of("SCOPUS", "CORE"), info.get("sourcesConsulted"));
    }

    @Test
    void publicationLncsFallbackSetsYearAndProvenance() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = conferencePublication("forum-lncs", "2023-01-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(cacheService.getForum("forum-lncs")).thenReturn(forum);

        Score score = service.getScore(publication, indicator("IY"));
        assertEquals(2.0, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("LNCS_SPECIAL_CASE", score.getScoringInfo().get("fallbackReason"));
    }

    @Test
    void publicationScopusFallbackPreservesExistingFallbackReason() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        ScoringPublication publication = new ScoringPublication(
                "pub-lncs-ch", null, "forum-lncs-ch", "2023-01-01", "ch", "ch",
                List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Practical Systems");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-lncs-ch")).thenReturn(forum);
        when(cacheService.getConferenceRankingsByNormalizedTitle(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, indicator("IY"));
        assertEquals(1.0, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals("SCOPUS", score.getScoringSource());
        // should come from unresolved CORE path, not be overwritten by SCOPUS_FALLBACK
        assertEquals("NO_ACRONYM_CANDIDATES", score.getScoringInfo().get("fallbackReason"));
    }

    @Test
    void resolveConferenceMatchByTitleReturnsUnresolvedWhenAllCandidatesHaveNoneConfidence() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        CoreConferenceRanking weak = ranking("W", "Completely Different Venue", CoreConferenceRanking.Rank.C);
        when(cacheService.getConferenceRankingsByNormalizedTitle("tiny venue")).thenReturn(List.of(weak));

        Object trace = invokeTraceFactory("forPublication", null, null, null, "Tiny Venue", List.of(2023));
        Object unresolved = invokePrivate(service, "resolveConferenceMatchByTitle",
                new Class[]{String.class, trace.getClass(), ComputerScienceConferenceScoringService.FallbackReason.class},
                "tiny venue", trace, ComputerScienceConferenceScoringService.FallbackReason.BELOW_THRESHOLD);

        Method resolvedMethod = unresolved.getClass().getDeclaredMethod("resolved");
        resolvedMethod.setAccessible(true);
        assertEquals(false, resolvedMethod.invoke(unresolved));
    }

    @Test
    void scoreMatchConfidencePrefersContainsBeforeStrongOverlap() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        CoreConferenceRanking ranking = ranking("ICNP", "International Conference on Network Protocols", CoreConferenceRanking.Rank.B);

        Object confidence = invokePrivate(service, "scoreMatchConfidence",
                new Class[]{String.class, String.class, CoreConferenceRanking.class},
                "international conference on network protocols icnp", "ICNP", ranking);
        assertEquals("NORMALIZED_CONTAINS", ((Enum<?>) confidence).name());
    }

    @Test
    void boundaryConditionSweepForHelperAndYearSelectionComparators() throws Exception {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // isTitleCasedAcronymBeforeIgnorableSuffix length/trailing index boundaries
        assertEquals(true, invokePrivate(service, "isTitleCasedAcronymBeforeIgnorableSuffix",
                new Class[]{String[].class, int.class, String.class},
                (Object) new String[]{"International", "Conference", "Icn", "2024"},
                2, "ICN"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymBeforeIgnorableSuffix",
                new Class[]{String[].class, int.class, String.class},
                (Object) new String[]{"International", "Conference", "Icn"},
                2, "ICN"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymBeforeIgnorableSuffix",
                new Class[]{String[].class, int.class, String.class},
                (Object) new String[]{"International", "Conference", "Icn", "2024"},
                2, "IC"));
        assertEquals(false, invokePrivate(service, "isTitleCasedAcronymBeforeIgnorableSuffix",
                new Class[]{String[].class, int.class, String.class},
                (Object) new String[]{"International", "Conference", "VeryLongAcronym", "2024"},
                2, "VERYLONGA"));

        // hasStrongTokenOverlap exact threshold checks
        assertEquals(true, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("a", "b", "c", "x"),
                Set.of("a", "b", "c", "d")));
        assertEquals(false, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("a", "b", "x", "y"),
                Set.of("a", "b", "c", "d")));
        assertEquals(false, invokePrivate(service, "hasStrongTokenOverlap",
                new Class[]{Set.class, Set.class},
                Set.of("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                Set.of("a", "b", "c", "d")));

        // normalizeComparableToken boundary branches
        assertEquals("abc", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "abc"));
        assertEquals("fly", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "flies"));
        assertEquals("boat", invokePrivate(service, "normalizeComparableToken",
                new Class[]{String.class}, "boats"));

        // activity getScore comparator boundary: equal score in later year must NOT replace first year
        ActivityInstance activity = activity("act-eq", "2023-06-01",
                Map.of(Activity.ReferenceField.EVENT_NAME, "International Conference on Software Engineering, ICSE 2023"));
        CoreConferenceRanking activityRanking = new CoreConferenceRanking();
        activityRanking.setAcronym("ICSE");
        activityRanking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking a2023 = new CoreConferenceRanking.YearlyRanking();
        a2023.setRank(CoreConferenceRanking.Rank.B);
        CoreConferenceRanking.YearlyRanking a2024 = new CoreConferenceRanking.YearlyRanking();
        a2024.setRank(CoreConferenceRanking.Rank.B);
        activityRanking.setYearlyRankings(Map.of(2023, a2023, 2024, a2024));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(activityRanking));
        Score activityScore = service.getScore(activity, indicator("2023->2024") /* H52 11d.1: was "IY,IY+1" */);
        assertEquals(2023, activityScore.getYear());

        // publication getScore comparator boundary at line 91: equal score must keep first year
        ScoringPublication publication = conferencePublication("forum-eq", "2023-06-01");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("International Conference on Software Engineering, ICSE 2023");        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-eq")).thenReturn(forum);
        CoreConferenceRanking pubRanking = new CoreConferenceRanking();
        pubRanking.setAcronym("ICSE");
        pubRanking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking p2023 = new CoreConferenceRanking.YearlyRanking();
        p2023.setRank(CoreConferenceRanking.Rank.B);
        CoreConferenceRanking.YearlyRanking p2024 = new CoreConferenceRanking.YearlyRanking();
        p2024.setRank(CoreConferenceRanking.Rank.B);
        pubRanking.setYearlyRankings(Map.of(2023, p2023, 2024, p2024));
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(pubRanking));
        Score publicationScore = service.getScore(publication, indicator("2023->2024") /* H52 11d.1: was "IY,IY+1" */);
        assertEquals(2023, publicationScore.getYear());
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private Object invokeTraceFactory(String factoryName, String itemId, String subtype, String coverDate, String forumTitle, List<Integer> years) throws Exception {
        Class<?> traceClass = Class.forName("ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService$ConferenceScoreTrace");
        Method m = traceClass.getDeclaredMethod(factoryName, String.class, String.class, String.class, String.class, List.class);
        m.setAccessible(true);
        return m.invoke(null, itemId, subtype, coverDate, forumTitle, years);
    }

    private ActivityInstance activity(String id, String date, Map<Activity.ReferenceField, String> referenceFields) {
        ActivityInstance activity = new ActivityInstance();
        activity.setId(id);
        activity.setDate(date);
        activity.setReferenceFields(referenceFields);
        return activity;
    }

    @Test
    void consultsDblpForNonLncsConferenceProceedingWhenForumNameMisses() {
        ComputerScienceConferenceScoringService service =
                new ComputerScienceConferenceScoringService(cacheService, dblpEvidenceRepository);

        // A migration-resolved conference-proceeding forum whose opaque name matches nothing in CORE.
        // The authoritative conference identity lives only in the DBLP conf/igarss evidence — which must
        // now be consulted even though this is NOT an LNCS book-series paper.
        ScoringPublication publication = new ScoringPublication("pub-igarss", null, "forum-conf", "2016-06-01",
                null, "cp", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Unlisted Symposium Proceedings");
        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-conf")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("IGARSS");
        ranking.setName("IEEE International Geoscience and Remote Sensing Symposium");
        CoreConferenceRanking.YearlyRanking r2016 = new CoreConferenceRanking.YearlyRanking();
        r2016.setRank(CoreConferenceRanking.Rank.C);
        ranking.setYearlyRankings(Map.of(2016, r2016));
        when(cacheService.getConferenceRankings("IGARSS")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-igarss");
        evidence.setSeries("conf/igarss");
        when(dblpEvidenceRepository.findByPublicationId("pub-igarss")).thenReturn(Optional.of(evidence));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", score.getScoringSource());
    }

    @Test
    void scoresArticleSubtypeInConferenceProceedingForumAsConference() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // OpenAlex labels many proceedings papers as plain "ar"; the conference-proceeding forum still makes
        // it a conference contribution, so it must resolve to CORE rather than be skipped entirely.
        ScoringPublication publication = new ScoringPublication("pub-icse-ar", null, "forum-1", "2023-10-10",
                "ar", "ar", List.of(), 0, null, null, null, 0, Set.of());
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the 46th International Conference on Software Engineering, ICSE 2023");
        forum.setAggregationType("Conference Proceeding");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void lncsSeriesFallbackMatchesCaseInsensitivelyForLnai() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        // Real casing variant from the data: "Lecture Notes In Artificial Intelligence" (capital In), which
        // OpenAlex even mislabels as a Journal. LNAI is Springer conference proceedings, so the chapter must
        // still get the LNCS C fallback rather than going unscored.
        ScoringPublication publication = lncsChapterPublication("forum-lnai", "2018-01-01", "pub-lnai");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes In Artificial Intelligence");
        forum.setAggregationType("Journal");
        when(cacheService.getForum("forum-lnai")).thenReturn(forum);

        Score score = service.getScore(publication, new Indicator());

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.LNCS.toString(), score.getQuarter());
    }

    private ScoringPublication conferencePublication(String forumId, String coverDate) {
        return new ScoringPublication(null, null, forumId, coverDate, null, "cp", List.of(), 0, null, null, null, 0, Set.of());
    }

    private ScoringPublication posterPublication(String forumId, String coverDate, String title) {
        return new ScoringPublication(null, null, forumId, coverDate, null, "cp", List.of("a1"), 1, null, null, title, 0, Set.of());
    }

    private ScoringPublication lncsChapterPublication(String forumId, String coverDate, String id) {
        return new ScoringPublication(id, null, forumId, coverDate, "ch", "ch", List.of(), 0, null, null, null, 0, Set.of());
    }

    // ── H85: 2026 OM amendment — CORE-unranked ACM/EPTCS venues floor to C ─────

    private Indicator acmFloorIndicator2026() {
        Indicator indicator = new Indicator();
        indicator.setAcmEptcsCFloor2026(true);
        return indicator;
    }

    private ScholardexForumView conferenceForum(String name) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName(name);
        forum.setAggregationType("Conference Proceeding");
        return forum;
    }

    @Test
    void ieeeAcmProceedingsFloorToCUnderThe2026Flag() {
        // The UCC-2013+ shape: IEEE/ACM co-published, CORE-Unranked, IEEE-branded DOI (irrelevant —
        // detection is name-based on purpose).
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-ucc13", "2013-12-09");
        when(cacheService.getForum("forum-ucc13")).thenReturn(conferenceForum(
                "Proceedings - 2013 IEEE/ACM 6th International Conference on Utility and Cloud Computing, UCC 2013"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.ACM.toString(), score.getQuarter());
    }

    @Test
    void ieeeAcmProceedingsStayDWithoutTheFlag() {
        // Frozen 2016 behaviour: the 2016 amendment is LNCS-only, so ACM venues keep the D fallback.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-ucc13", "2013-12-09");
        when(cacheService.getForum("forum-ucc13")).thenReturn(conferenceForum(
                "Proceedings - 2013 IEEE/ACM 6th International Conference on Utility and Cloud Computing, UCC 2013"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void ieeeOnlyProceedingsStayDEvenWithTheFlag() {
        // The UCC-2011 shape: IEEE-only era — no ACM token, floor must not fire.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-ucc11", "2011-12-05");
        when(cacheService.getForum("forum-ucc11")).thenReturn(conferenceForum(
                "Proceedings - 2011 4th IEEE International Conference on Utility and Cloud Computing, UCC 2011"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void eptcsProceedingsFloorToCUnderThe2026Flag() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-eptcs", "2020-04-01");
        when(cacheService.getForum("forum-eptcs")).thenReturn(conferenceForum(
                "Electronic Proceedings in Theoretical Computer Science, EPTCS"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(2.0, score.getScore());
        assertEquals(WoSRanking.Quarter.ACM.toString(), score.getQuarter());
    }

    @Test
    void acmSubstringInsideAnotherWordDoesNotTriggerTheFloor() {
        // Whole-word matching: "Macmillan"/"ACME" must not read as ACM.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-acme", "2020-04-01");
        when(cacheService.getForum("forum-acme")).thenReturn(conferenceForum(
                "ACME Workshop on Macmillan Computing Topics"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(1.0, score.getScore()); // plain conference D fallback, no ACM floor
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
    }

    @Test
    void acmPublisherFieldTriggersTheFloorWhenTheNameLacksTheToken() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-acmpub", "2019-06-01");
        ScholardexForumView forum = conferenceForum("Proceedings of the 12th Something Conference");
        forum.setPublisher("Association for Computing Machinery");
        when(cacheService.getForum("forum-acmpub")).thenReturn(forum);
        // no getConferenceRankings stub: this name yields no acronym candidates, CORE is never consulted

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(2.0, score.getScore());
        assertEquals(WoSRanking.Quarter.ACM.toString(), score.getQuarter());
    }

    @Test
    void coreRankedAcmVenueKeepsItsCoreRankNotTheFloor() {
        // The amendment covers only venues NOT in A*/A/B — a CORE-ranked ACM conference keeps its rank.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = conferencePublication("forum-acm-a", "2023-10-10");
        when(cacheService.getForum("forum-acm-a")).thenReturn(conferenceForum(
                "Proceedings of the ACM Great Conference, AGC 2023"));
        when(cacheService.getConferenceRankings(anyString()))
                .thenReturn(List.of(ranking("AGC", "ACM Great Conference", CoreConferenceRanking.Rank.A)));

        Score score = service.getScore(publication, indicator("IY"));

        assertEquals(8.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A.toString(), score.getCoreRankingEquivalent());
    }

    /** H85 durable detection: a DBLP-sweep-stamped pub — assigned to the conf/X stream forum, with the
     *  displaced raw per-year proceedings forum preserved as originalForumId. */
    private ScoringPublication streamStampedPublication(String forumId, String originalForumId, String coverDate) {
        return new ScoringPublication(null, null, forumId, null, originalForumId, coverDate, null, "cp",
                List.of(), 0, null, null, null, 0, Set.of(), 0, 0, 0, List.of(), null);
    }

    @Test
    void streamStampedPaperFloorsToCViaThePreservedOriginalForum() {
        // The post-sweep UCC shape: the assigned stream forum is named "UCC" with no publisher — the
        // ACM signal lives only on the preserved pre-restamp per-year proceedings forum.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = streamStampedPublication("forum-ucc-stream", "forum-ucc13-raw", "2013-12-09");
        when(cacheService.getForum("forum-ucc-stream")).thenReturn(conferenceForum("UCC"));
        when(cacheService.getForum("forum-ucc13-raw")).thenReturn(conferenceForum(
                "Proceedings - 2013 IEEE/ACM 6th International Conference on Utility and Cloud Computing, UCC 2013"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(2.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.C.toString(), score.getCoreRankingEquivalent());
        assertEquals(WoSRanking.Quarter.ACM.toString(), score.getQuarter());
        assertEquals("Proceedings - 2013 IEEE/ACM 6th International Conference on Utility and Cloud Computing, UCC 2013",
                score.getScoringInfo().get("acmEvidenceVenue"));
    }

    @Test
    void streamStampedPaperFromTheIeeeOnlyEraStaysD() {
        // Same stream forum, but the preserved 2011 volume is IEEE-only — the era split stays exact.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = streamStampedPublication("forum-ucc-stream", "forum-ucc11-raw", "2011-12-05");
        when(cacheService.getForum("forum-ucc-stream")).thenReturn(conferenceForum("UCC"));
        when(cacheService.getForum("forum-ucc11-raw")).thenReturn(conferenceForum(
                "Proceedings - 2011 4th IEEE International Conference on Utility and Cloud Computing, UCC 2011"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, acmFloorIndicator2026());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
        assertEquals(null, score.getScoringInfo().get("acmEvidenceVenue"));
    }

    @Test
    void streamStampedPaperWithoutTheFlagIgnoresTheOriginalForum() {
        // 2016 report shape: even with an ACM-signal original forum preserved, the 2016 indicator
        // (no acmEptcsCFloor2026) must never consult it.
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);
        ScoringPublication publication = streamStampedPublication("forum-ucc-stream", "forum-ucc13-raw", "2013-12-09");
        when(cacheService.getForum("forum-ucc-stream")).thenReturn(conferenceForum("UCC"));
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCoreRankingEquivalent());
    }

    private Indicator indicator(String scoreYearRange) {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, scoreYearRange);
        return indicator;
    }

    /** An indicator opted into the 2026 conference-workshop category mapping (id_parA82). */
    private Indicator indicator2026(String scoreYearRange) {
        Indicator indicator = indicator(scoreYearRange);
        indicator.setWorkshopCategory2026(true);
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
