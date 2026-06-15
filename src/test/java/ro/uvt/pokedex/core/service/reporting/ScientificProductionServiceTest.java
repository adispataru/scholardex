package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ScientificProductionServiceTest {

    @Mock
    private ScoringFactoryService scoringFactoryService;

    @Mock
    private ScoringService scoringService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;

    // Real evaluator (no MVEL behavior to mock) — @InjectMocks picks it up via the
    // constructor signature.
    @org.mockito.Spy
    private ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator formulaEvaluator =
            new ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator();

    @InjectMocks
    private ScientificProductionService scientificProductionService;


    @Test
    void productionScoreGenericCountAssignsOnePerPublicationAndTotalSize() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");
        List<ScoringPublicationReadModel> publications = List.of(
                publication("p1", null, null, null, null, "Paper 1", List.of("a1")),
                publication("p2", null, null, null, null, "Paper 2", List.of("a1", "a2"))
        );

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(1.0, result.get("Paper 1").getScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 1").getAuthorScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 2").getScore(), 0.0001);
        assertEquals(1.0, result.get("Paper 2").getAuthorScore(), 0.0001);
        assertEquals(2.0, result.get("total").getAuthorScore(), 0.0001);
        verify(scoringFactoryService, never()).getScoringService(org.mockito.ArgumentMatchers.any(String.class));
    }

    @Test
    void impactScoreGenericCountAssignsOnePerPublicationAndTotalSize() {
        Indicator indicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "GENERIC_COUNT");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1"));
        List<ScoringPublicationReadModel> citingPublications = List.of(
                publication("cp1", null, null, null, null, "Citing 1", List.of("b1")),
                publication("cp2", null, null, null, null, "Citing 2", List.of("b2"))
        );

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(cited, citingPublications, indicator);

        assertEquals(1.0, result.get("Citing 1").getScore(), 0.0001);
        assertEquals(1.0, result.get("Citing 2").getAuthorScore(), 0.0001);
        assertEquals(2.0, result.get("total").getAuthorScore(), 0.0001);
        verify(scoringFactoryService, never()).getScoringService(org.mockito.ArgumentMatchers.any(String.class));
    }

    @Test
    void cachedBasePathMatchesLegacyPathForCitationsAndExcludeSelf() {
        Indicator citations = indicator("CITATIONS", "S");
        Indicator citationsExcludeSelf = indicator("CITATIONS_EXCLUDE_SELF", "S");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2"));
        ScoringPublication citingA = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));
        ScoringPublication citingB = publication("cp-2", null, null, null, null, "cp-2", List.of("b2"));
        List<ScoringPublicationReadModel> citingPublications = List.of(citingA, citingB);

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citingA, citations)).thenReturn(score(2.0));
        when(scoringService.getScore(citingB, citations)).thenReturn(score(3.0));
        when(scoringService.getScore(citingA, citationsExcludeSelf)).thenReturn(score(2.0));
        when(scoringService.getScore(citingB, citationsExcludeSelf)).thenReturn(score(3.0));

        Map<String, Score> legacyCitations =
                scientificProductionService.calculateScientificImpactScore(cited, citingPublications, citations);
        Map<String, Score> cachedCitations =
                scientificProductionService.calculateScientificImpactScore(
                        cited,
                        citingPublications,
                        citations,
                        scientificProductionService.precomputeCitationBaseScores(citingPublications, citations)
                );
        assertEquals(legacyCitations.get("total").getAuthorScore(), cachedCitations.get("total").getAuthorScore(), 0.0001);
        assertEquals(legacyCitations.get("total").getScore(), cachedCitations.get("total").getScore(), 0.0001);

        Map<String, Score> legacyExcludeSelf =
                scientificProductionService.calculateScientificImpactScore(cited, citingPublications, citationsExcludeSelf);
        Map<String, Score> cachedExcludeSelf =
                scientificProductionService.calculateScientificImpactScore(
                        cited,
                        citingPublications,
                        citationsExcludeSelf,
                        scientificProductionService.precomputeCitationBaseScores(citingPublications, citationsExcludeSelf)
                );
        assertEquals(legacyExcludeSelf.get("total").getAuthorScore(), cachedExcludeSelf.get("total").getAuthorScore(), 0.0001);
        assertEquals(legacyExcludeSelf.get("total").getScore(), cachedExcludeSelf.get("total").getScore(), 0.0001);
    }

    @Test
    void universalSubtypeGateExcludesNonResearchPublicationsForAnyStrategy() {
        // Strategy intentionally non-CS ("AIS") to prove the gate lives in the shared
        // orchestrator and applies to every domain's scoring service, not just CS.
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "AIS");

        ScoringPublication article = publication("p-ar", null, null, "ar", "ar", "Real Article", List.of("a1"));
        ScoringPublication editorial = publication("p-ed", null, null, "ed", "ed", "An Editorial", List.of("a1"));

        when(scoringFactoryService.getScoringService("AIS")).thenReturn(scoringService);
        when(scoringService.getScore(article, indicator)).thenReturn(score(8.0));

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(
                List.of(article, editorial), indicator);

        // Editorial is gated before the scorer is consulted; only the article contributes.
        verify(scoringService, never()).getScore(editorial, indicator);
        assertEquals(8.0, result.get("Real Article").getAuthorScore(), 0.0001);
        assertEquals(null, result.get("An Editorial"));
        assertEquals(8.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void cachedBasePathRespectsFormulaUsingAuthorCountN() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication cited = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2", "a3"));
        ScoringPublication citing = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, indicator)).thenReturn(score(2.0));

        Map<String, Score> precomputed = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited,
                List.of(citing),
                indicator,
                precomputed
        );

        assertEquals(6.0, result.get(citing.getTitle()).getAuthorScore(), 0.0001);
        assertEquals(6.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void cachedBaseScoresAreNotMutatedAcrossCalls() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication citedWithTwoAuthors = publication("cited-1", null, null, null, null, "cited-1", List.of("a1", "a2"));
        ScoringPublication citedWithFourAuthors = publication("cited-2", null, null, null, null, "cited-2", List.of("a1", "a2", "a3", "a4"));
        ScoringPublication citing = publication("cp-1", null, null, null, null, "cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        when(scoringService.getScore(citing, indicator)).thenReturn(score(2.0));

        Map<String, Score> precomputed = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Score cachedBefore = precomputed.get("cp-1");
        assertNotNull(cachedBefore);
        assertEquals(0.0, cachedBefore.getAuthorScore(), 0.0001);

        scientificProductionService.calculateScientificImpactScore(citedWithTwoAuthors, List.of(citing), indicator, precomputed);
        scientificProductionService.calculateScientificImpactScore(citedWithFourAuthors, List.of(citing), indicator, precomputed);

        Score cachedAfter = precomputed.get("cp-1");
        assertNotNull(cachedAfter);
        assertEquals(0.0, cachedAfter.getAuthorScore(), 0.0001);
        assertEquals(2.0, cachedAfter.getScore(), 0.0001);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publicationScoringUsesCoreConferenceMatchWhenMongoYearKeysAreStrings() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-1", "forum-1", "2016-07-18", null, "cp",
                "Reusing Resource Coalitions for Efficient Scheduling on the Intercloud", List.of("a1", "a2", "a3"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings - 2016 16th IEEE/ACM International Symposium on Cluster, Cloud, and Grid Computing, CCGrid 2016");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

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
        when(lookupPort.getConferenceRankings("CCGRID")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(8.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(8.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals(2016, result.get(publication.getTitle()).getYear());
        assertEquals(8.0, result.get("total").getAuthorScore(), 0.0001);
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                conferenceScoringService.diagnoseConferenceMatch(forum.getPublicationName(), 2016);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
        assertEquals("CCGRID", trace.resolvedAcronym());
    }

    @Test
    void publicationScoringUsesTrailingTitleCasedConferenceAcronym() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-icnp-1", "forum-1", "2023-01-01", null, "cp",
                "Architecture for Confidential Digital Asset Transfer on Blockchain Through Obfuscation", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings International Conference on Network Protocols Icnp");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICNP");
        ranking.setName("International Conference on Network Protocols");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("ICNP")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                conferenceScoringService.diagnoseConferenceMatch(forum.getPublicationName(), 2023);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
        assertEquals("ICNP", trace.resolvedAcronym());
    }

    @Test
    void publicationScoringUsesNormalizedTitleCoreFallbackWhenAcronymIsMissing() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-wopp-1", "forum-1", "2023-01-01", null, "cp",
                "Parallel Workloads in Workshop Proceedings", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Proceedings of the International Conference on Parallel Processing Workshops");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankingsByNormalizedTitle("proceedings of the international conference on parallel processing workshops"))
                .thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("WOPP-IEEE International Conference on Parallel Processing Workshops");
        ranking.setAcronym("WOPP");
        ranking.setName("IEEE International Conference on Parallel Processing Workshops");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankingsByNormalizedTitle("parallel processing workshops")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("TITLE", result.get(publication.getTitle()).getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.TITLE, trace.resolvedLookupStrategy());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void publicationScoringHalvesWorkshopScoreWhenForumIsWorkshopOfParentConference() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        ScoringPublication publication = publication(
                "pub-percom-ws-1", "forum-1", "2023-01-01", null, "cp",
                "On the Use of Deep Neural Networks for Security Vulnerabilities Detection in Smart Contracts", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("2023 IEEE International Conference on Pervasive Computing and Communications Workshops and Other Affiliated Events Percom Workshops 2023");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("PERCOM-IEEE International Conference on Pervasive Computing and Communications");
        ranking.setAcronym("PERCOM");
        ranking.setName("IEEE International Conference on Pervasive Computing and Communications");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.A);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("PERCOM")).thenReturn(List.of(ranking));
        when(scoringFactoryService.getScoringService("CS")).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("A", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("SCOPUS+CORE(WS)", result.get(publication.getTitle()).getScoringSource());
        assertEquals(true, result.get(publication.getTitle()).getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void publicationScoringUsesDblpConferenceEvidenceForLncsChapter() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        ScoringPublication publication = publication(
                "pub-lncs-1", "forum-1", "2024-07-18", "ch", "ch",
                "A Chapter Hidden In LNCS", List.of("a1", "a2", "a3"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes in Computer Science");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        CoreConferenceRanking.YearlyRanking rank2024 = new CoreConferenceRanking.YearlyRanking();
        rank2024.setRank(CoreConferenceRanking.Rank.A_STAR);
        ranking.setYearlyRankings(Map.of(2024, rank2024));
        when(lookupPort.getConferenceRankings("ICSE")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-lncs-1");
        evidence.setConferenceName("Proceedings of the International Conference on Software Engineering, ICSE 2024");
        when(dblpEvidenceRepository.findByPublicationId("pub-lncs-1")).thenReturn(Optional.of(evidence));
        when(scoringFactoryService.getScoringService("CS_CONFERENCE")).thenReturn(conferenceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS_CONFERENCE");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(12.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(12.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A_STAR", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals(2023, result.get(publication.getTitle()).getYear());
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("DBLP", result.get(publication.getTitle()).getScoringInfo().get("matchSource"));
        assertEquals(12.0, result.get("total").getAuthorScore(), 0.0001);
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void publicationScoringUsesDecoratedDblpAcronymEvidenceForLncsChapter() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        ScoringPublication publication = publication(
                "pub-aina-1", "forum-1", "2025-01-01", "ch", "ch",
                "AINA LNDECT Chapter", List.of("a1"));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("Lecture Notes on Data Engineering and Communications Technologies");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getConferenceRankings(anyString())).thenReturn(List.of());

        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym("AINA");
        ranking.setName("International Conference on Advanced Information Networking and Applications (was ICOIN)");
        CoreConferenceRanking.YearlyRanking rank2023 = new CoreConferenceRanking.YearlyRanking();
        rank2023.setRank(CoreConferenceRanking.Rank.B);
        ranking.setYearlyRankings(Map.of(2023, rank2023));
        when(lookupPort.getConferenceRankings("AINA")).thenReturn(List.of(ranking));

        ScholardexPublicationDblpEvidence evidence = new ScholardexPublicationDblpEvidence();
        evidence.setPublicationId("pub-aina-1");
        evidence.setConferenceName("AINA (6)");
        when(dblpEvidenceRepository.findByPublicationId("pub-aina-1")).thenReturn(Optional.of(evidence));
        when(scoringFactoryService.getScoringService("CS_CONFERENCE")).thenReturn(conferenceScoringService);

        Indicator indicator = indicator("PUBLICATIONS", "S/max(N-2, 1)");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS_CONFERENCE");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCoreRankingEquivalent());
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("DBLP", result.get(publication.getTitle()).getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
        assertEquals("AINA (6)", trace.dblpConferenceTitle());
    }

    @Test
    void productionScoreTop10SelectorSortsAndLimitsByAuthorScore() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.range(0, 11)
                .mapToObj(i -> publication("p" + i, null, null, null, null, "Paper " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < 11; i++) {
            Score score = new Score();
            score.setScore(i == 0 ? 0.0 : (double) i);
            score.setAuthorScore(i == 0 ? 0.0 : (double) i);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(10, result.size() - 1);
        assertTrue(result.containsKey("Paper 10"));
        assertTrue(result.containsKey("Paper 1"));
        assertTrue(!result.containsKey("Paper 0"));
        assertEquals(55.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void precomputeCitationBaseScoresReturnsEmptyForGuardPathsAndSkipsDuplicateIds() {
        Indicator genericIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(genericIndicator, "GENERIC_COUNT");
        Indicator nullStrategyIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(nullStrategyIndicator, null);

        assertTrue(scientificProductionService.precomputeCitationBaseScores(null, genericIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(List.of(), genericIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), null).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), nullStrategyIndicator).isEmpty());
        assertTrue(scientificProductionService.precomputeCitationBaseScores(
                List.of(publication("p1", null, null, null, null, "P1", List.of("a1"))), genericIndicator).isEmpty());

        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        ScoringPublication duplicateA = publication("dup", null, null, null, null, "A", List.of("a1"));
        ScoringPublication duplicateB = publication("dup", null, null, null, null, "B", List.of("a1"));
        ScoringPublication noId = new ScoringPublication(
                null,
                "eid-null",
                null,
                null,
                null,
                null,
                List.of("a1"),
                1,
                null,
                null,
                "NoId",
                0,
                Set.of()
        );
        Score baseScore = score(2.5);
        baseScore.setAuthorScore(9.0);
        when(scoringService.getScore(duplicateA, indicator)).thenReturn(baseScore);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(
                Arrays.asList(duplicateA, duplicateB, noId, null), indicator
        );

        assertEquals(1, cached.size());
        assertTrue(cached.containsKey("dup"));
        assertEquals(2.5, cached.get("dup").getScore(), 0.0001);
        assertEquals(9.0, cached.get("dup").getAuthorScore(), 0.0001);
        verify(scoringService, times(1)).getScore(duplicateA, indicator);
    }

    @Test
    void impactScoreAccumulatesTotalScoreAndAuthorScoreOnPositiveMatches() {
        Indicator indicator = indicator("CITATIONS", "S * N");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1", "a2"));
        ScoringPublication citingA = publication("ca", null, null, null, null, "Citing A", List.of("b1"));
        ScoringPublication citingB = publication("cb", null, null, null, null, "Citing B", List.of("b2"));
        List<ScoringPublicationReadModel> citingPublications = List.of(citingA, citingB);

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score scoreA = new Score();
        scoreA.setScore(2.0);
        scoreA.setAuthorScore(0.0);
        Score scoreB = new Score();
        scoreB.setScore(3.0);
        scoreB.setAuthorScore(0.0);
        when(scoringService.getScore(citingA, indicator)).thenReturn(scoreA);
        when(scoringService.getScore(citingB, indicator)).thenReturn(scoreB);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(cited, citingPublications, indicator);

        assertEquals(4.0, result.get("Citing A").getAuthorScore(), 0.0001);
        assertEquals(6.0, result.get("Citing B").getAuthorScore(), 0.0001);
        assertEquals(10.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals(5.0, result.get("total").getScore(), 0.0001);
    }

    @Test
    void impactScoreUsesCachedBaseByCitingPublicationIdWithoutServiceLookup() {
        Indicator indicator = indicator("CITATIONS", "S");
        ScoringPublication cited = publication("cited", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("cp-cache", null, null, null, null, "Cached", List.of("b1"));

        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        Score cachedBase = new Score();
        cachedBase.setScore(7.0);
        cachedBase.setAuthorScore(13.0);
        // H52 slice 11c: details/errors/extra dropped; multiplier is the only
        // open-bag-replacement we still propagate.
        cachedBase.setMultiplier(5);
        cachedBase.setScoringInfo(new HashMap<>(Map.of("info", "cached")));
        Map<String, Score> cached = new HashMap<>();
        cached.put("cp-cache", cachedBase);

        Map<String, Score> result = scientificProductionService.calculateScientificImpactScore(
                cited,
                List.of(citing),
                indicator,
                cached
        );

        verify(scoringService, never()).getScore(citing, indicator);
        assertEquals(7.0, result.get("Cached").getScore(), 0.0001);
        assertEquals(7.0, result.get("Cached").getAuthorScore(), 0.0001);
        assertEquals(7.0, result.get("total").getScore(), 0.0001);
        assertEquals(7.0, result.get("total").getAuthorScore(), 0.0001);
        assertEquals(13.0, cachedBase.getAuthorScore(), 0.0001);
        assertEquals(5, cachedBase.getMultiplier());
        assertEquals("cached", cachedBase.getScoringInfo().get("info"));
    }

    @Test
    void precomputeCitationBaseScoresCopiesAllRelevantScoreFieldsAndDetachesMaps() {
        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);
        ScoringPublication citing = publication("cp-copy", null, null, null, null, "Copy", List.of("a1"));

        Score base = new Score();
        base.setScore(9.0);
        base.setAuthorScore(4.5);
        base.setYear(2024);
        base.setCoreRankingEquivalent("A");
        base.setQuarter("Q1");
        base.setScoringSource("SOURCE");
        // H52 slice 11c: details/errors/extra dropped; the typed multiplier
        // is now the only open-bag-replacement field copyScore carries forward.
        base.setMultiplier(7);
        base.setScoringInfo(new HashMap<>(Map.of("k1", "v1")));
        when(scoringService.getScore(citing, indicator)).thenReturn(base);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(List.of(citing), indicator);
        Score copy = cached.get("cp-copy");

        assertNotNull(copy);
        assertNotSame(base, copy);
        assertEquals(9.0, copy.getScore(), 0.0001);
        assertEquals(4.5, copy.getAuthorScore(), 0.0001);
        assertEquals(2024, copy.getYear());
        assertEquals("A", copy.getCoreRankingEquivalent());
        assertEquals("Q1", copy.getQuarter());
        assertEquals("SOURCE", copy.getScoringSource());
        assertEquals(7, copy.getMultiplier());
        assertEquals("v1", copy.getScoringInfo().get("k1"));
        assertNotSame(base.getScoringInfo(), copy.getScoringInfo());

        copy.setAuthorScore(1.0);
        copy.getScoringInfo().put("k1", "mutated");

        assertEquals(4.5, base.getAuthorScore(), 0.0001);
        assertEquals("v1", base.getScoringInfo().get("k1"));
        assertFalse(base.getScoringInfo().containsValue("mutated"));
    }

    @Test
    void productionScoreWithSelectorAllKeepsInterResultAndSkipsZeroScores() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "ALL");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        ScoringPublicationReadModel p1 = publication("all-1", null, null, null, null, "All 1", List.of("a1"));
        ScoringPublicationReadModel p2 = publication("all-2", null, null, null, null, "All 2", List.of("a1"));
        ScoringPublicationReadModel p3 = publication("all-3", null, null, null, null, "All 3", List.of("a1"));

        Score s1 = new Score();
        s1.setScore(2.0);
        s1.setAuthorScore(2.0);
        Score s2 = new Score();
        s2.setScore(0.0);
        s2.setAuthorScore(0.0);
        Score s3 = new Score();
        s3.setScore(1.0);
        s3.setAuthorScore(1.0);

        when(scoringService.getScore(p1, indicator)).thenReturn(s1);
        when(scoringService.getScore(p2, indicator)).thenReturn(s2);
        when(scoringService.getScore(p3, indicator)).thenReturn(s3);

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(p1, p2, p3), indicator);

        assertTrue(result.containsKey("All 1"));
        assertFalse(result.containsKey("All 2"));
        assertTrue(result.containsKey("All 3"));
        assertEquals(3.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void productionScoreTop10SelectorWithAtMostTenEntriesKeepsAllPositiveWithoutSortingBranch() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> publication("t" + i, null, null, null, null, "Top " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < publications.size(); i++) {
            Score score = new Score();
            score.setScore(i + 1.0);
            score.setAuthorScore(i + 1.0);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(3, result.size() - 1);
        assertEquals(6.0, result.get("total").getAuthorScore(), 0.0001);
    }

    @Test
    void productionScoreTop10SelectorSortsAndKeepsBestTenWhenMoreThanTenPublications() {
        Indicator indicator = indicator("PUBLICATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setSelector(indicator, "TOP_10");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        List<ScoringPublicationReadModel> publications = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> publication("mx-" + i, null, null, null, null, "Max " + i, List.of("a1")))
                .map(ScoringPublicationReadModel.class::cast)
                .toList();

        for (int i = 0; i < publications.size(); i++) {
            Score score = new Score();
            score.setScore(i + 1.0);
            score.setAuthorScore(i + 1.0);
            when(scoringService.getScore(publications.get(i), indicator)).thenReturn(score);
        }

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        assertEquals(10, result.size() - 1);
        assertEquals(75.0, result.get("total").getAuthorScore(), 0.0001); // 12+...+3
        assertTrue(result.containsKey("Max 12"));
        assertFalse(result.containsKey("Max 1"));
    }

    @Test
    void precomputeCitationBaseScoresGuardPathsDoNotTouchFactoryOrScoringService() {
        Indicator genericIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(genericIndicator, "GENERIC_COUNT");
        Indicator nullStrategyIndicator = indicator("CITATIONS", "S");
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(nullStrategyIndicator, null);
        List<ScoringPublicationReadModel> nonEmpty = List.of(
                publication("guard", null, null, null, null, "Guard", List.of("a1"))
        );

        scientificProductionService.precomputeCitationBaseScores(null, genericIndicator);
        scientificProductionService.precomputeCitationBaseScores(List.of(), genericIndicator);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, null);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, nullStrategyIndicator);
        scientificProductionService.precomputeCitationBaseScores(nonEmpty, genericIndicator);

        verifyNoInteractions(scoringFactoryService);
        verifyNoInteractions(scoringService);
    }

    @Test
    void precomputeCitationBaseScoresDeduplicatesIdsAndReturnsIndependentCopies() {
        Indicator indicator = indicator("CITATIONS", "S");
        when(scoringFactoryService.getScoringService("CS")).thenReturn(scoringService);

        ScoringPublicationReadModel p1 = publication("dup", null, null, null, null, "Dup A", List.of("a1"));
        ScoringPublicationReadModel p2 = publication("dup", null, null, null, null, "Dup B", List.of("a1"));
        Score base = score(2.0);
        when(scoringService.getScore(p1, indicator)).thenReturn(base);

        Map<String, Score> cached = scientificProductionService.precomputeCitationBaseScores(List.of(p1, p2), indicator);

        assertEquals(1, cached.size());
        assertEquals(2.0, cached.get("dup").getScore(), 0.0001);
        assertNotSame(base, cached.get("dup"));
        verify(scoringService, times(1)).getScore(p1, indicator);
    }

    @Test
    void nanosToMillisUsesFloorAndNeverReturnsNegative() throws Exception {
        Method m = ScientificProductionService.class.getDeclaredMethod("nanosToMillis", long.class);
        m.setAccessible(true);

        assertEquals(0L, m.invoke(scientificProductionService, -1L));
        assertEquals(0L, m.invoke(scientificProductionService, 999_999L));
        assertEquals(1L, m.invoke(scientificProductionService, 1_000_000L));
        assertEquals(2L, m.invoke(scientificProductionService, 2_999_999L));
    }

    @Test
    void reflectivePrivateHelpersCoverLegacyCitationAndNullCopyBranches() throws Exception {
        Indicator indicator = indicator("CITATIONS", "S");
        ScoringPublication cited = publication("c-1", null, null, null, null, "Cited", List.of("a1"));
        ScoringPublication citing = publication("x-1", null, null, null, null, "Citing", List.of("b1"));
        when(scoringService.getScore(citing, indicator)).thenReturn(score(3.0));

        Method legacyCitation = ScientificProductionService.class.getDeclaredMethod(
                "calculateCitationScore",
                ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel.class,
                ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel.class,
                Indicator.class,
                ScoringService.class
        );
        legacyCitation.setAccessible(true);
        Score legacy = (Score) legacyCitation.invoke(scientificProductionService, cited, citing, indicator, scoringService);
        assertEquals(3.0, legacy.getScore(), 0.0001);

        Method copyScore = ScientificProductionService.class.getDeclaredMethod("copyScore", Score.class);
        copyScore.setAccessible(true);
        Score copiedFromNull = (Score) copyScore.invoke(scientificProductionService, new Object[]{null});
        assertNotNull(copiedFromNull);
        assertEquals(0.0, copiedFromNull.getScore(), 0.0001);
    }

    private Indicator indicator(String typeName, String formula) {
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, typeName);
        indicator.setFormula(formula);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoringStrategy(indicator, "CS");
        return indicator;
    }

    private ScoringPublication publication(String id,
                                           String forumId,
                                           String coverDate,
                                           String subtype,
                                           String scopusSubtype,
                                           String title,
                                           List<String> authors) {
        return new ScoringPublication(
                id,
                "eid-" + id,
                forumId,
                coverDate,
                subtype,
                scopusSubtype,
                authors,
                authors.size(),
                "10.1000/" + id,
                null,
                title,
                0,
                Set.of()
        );
    }

    private Score score(double value) {
        Score score = new Score();
        score.setScore(value);
        score.setAuthorScore(0.0);
        return score;
    }
}
