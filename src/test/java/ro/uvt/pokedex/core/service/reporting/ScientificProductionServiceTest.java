package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScientificProductionServiceTest {

    @Mock
    private ScoringFactoryService scoringFactoryService;

    @Mock
    private ScoringService scoringService;
    @Mock
    private ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;

    @InjectMocks
    private ScientificProductionService scientificProductionService;

    @Test
    void cachedBasePathMatchesLegacyPathForCitationsAndExcludeSelf() {
        Indicator citations = indicator(Indicator.Type.CITATIONS, "S");
        Indicator citationsExcludeSelf = indicator(Indicator.Type.CITATIONS_EXCLUDE_SELF, "S");
        Publication cited = publication("cited-1", List.of("a1", "a2"));
        Publication citingA = publication("cp-1", List.of("b1"));
        Publication citingB = publication("cp-2", List.of("b2"));
        List<Publication> citingPublications = List.of(citingA, citingB);

        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(scoringService);
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
    void cachedBasePathRespectsFormulaUsingAuthorCountN() {
        Indicator indicator = indicator(Indicator.Type.CITATIONS, "S * N");
        Publication cited = publication("cited-1", List.of("a1", "a2", "a3"));
        Publication citing = publication("cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(scoringService);
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
        Indicator indicator = indicator(Indicator.Type.CITATIONS, "S * N");
        Publication citedWithTwoAuthors = publication("cited-1", List.of("a1", "a2"));
        Publication citedWithFourAuthors = publication("cited-2", List.of("a1", "a2", "a3", "a4"));
        Publication citing = publication("cp-1", List.of("b1"));

        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(scoringService);
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
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        Publication publication = publication("pub-1", List.of("a1", "a2", "a3"));
        publication.setForum("forum-1");
        publication.setCoverDate("2016-07-18");
        publication.setScopusSubtype("cp");
        publication.setTitle("Reusing Resource Coalitions for Efficient Scheduling on the Intercloud");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(8.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(8.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A", result.get(publication.getTitle()).getCategory());
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
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        Publication publication = publication("pub-icnp-1", List.of("a1"));
        publication.setForum("forum-1");
        publication.setCoverDate("2023-01-01");
        publication.setScopusSubtype("cp");
        publication.setTitle("Architecture for Confidential Digital Asset Transfer on Blockchain Through Obfuscation");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCategory());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace =
                conferenceScoringService.diagnoseConferenceMatch(forum.getPublicationName(), 2023);
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
        assertEquals("ICNP", trace.resolvedAcronym());
    }

    @Test
    void publicationScoringUsesNormalizedTitleCoreFallbackWhenAcronymIsMissing() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        Publication publication = publication("pub-wopp-1", List.of("a1"));
        publication.setForum("forum-1");
        publication.setCoverDate("2023-01-01");
        publication.setScopusSubtype("cp");
        publication.setTitle("Parallel Workloads in Workshop Proceedings");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCategory());
        assertEquals("SCOPUS+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("TITLE", result.get(publication.getTitle()).getScoringInfo().get("coreLookupStrategy"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.CoreLookupStrategy.TITLE, trace.resolvedLookupStrategy());
        assertEquals(ComputerScienceConferenceScoringService.FallbackReason.NONE, trace.fallbackReason());
    }

    @Test
    void publicationScoringHalvesWorkshopScoreWhenForumIsWorkshopOfParentConference() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService = new ComputerScienceConferenceScoringService(lookupPort);
        ComputerScienceScoringService computerScienceScoringService = new ComputerScienceScoringService(
                journalScoringService,
                conferenceScoringService,
                bookScoringService,
                lookupPort
        );

        Publication publication = publication("pub-percom-ws-1", List.of("a1"));
        publication.setForum("forum-1");
        publication.setCoverDate("2023-01-01");
        publication.setScopusSubtype("cp");
        publication.setTitle("On the Use of Deep Neural Networks for Security Vulnerabilities Detection in Smart Contracts");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS)).thenReturn(computerScienceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("A", result.get(publication.getTitle()).getCategory());
        assertEquals(true, result.get(publication.getTitle()).getScoringInfo().get("workshopAdjusted"));
    }

    @Test
    void publicationScoringUsesDblpConferenceEvidenceForLncsChapter() {
        ReportingLookupPort lookupPort = org.mockito.Mockito.mock(ReportingLookupPort.class);
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        Publication publication = publication("pub-lncs-1", List.of("a1", "a2", "a3"));
        publication.setForum("forum-1");
        publication.setCoverDate("2024-07-18");
        publication.setScopusSubtype("ch");
        publication.setSubtype("ch");
        publication.setTitle("A Chapter Hidden In LNCS");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS_CONFERENCE)).thenReturn(conferenceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoringStrategy(Indicator.Strategy.CS_CONFERENCE);
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(12.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals(12.0, result.get(publication.getTitle()).getAuthorScore(), 0.0001);
        assertEquals("A_STAR", result.get(publication.getTitle()).getCategory());
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
        ComputerScienceJournalScoringService journalScoringService = org.mockito.Mockito.mock(ComputerScienceJournalScoringService.class);
        ComputerScienceBookService bookScoringService = org.mockito.Mockito.mock(ComputerScienceBookService.class);
        ComputerScienceConferenceScoringService conferenceScoringService =
                new ComputerScienceConferenceScoringService(lookupPort, dblpEvidenceRepository);

        Publication publication = publication("pub-aina-1", List.of("a1"));
        publication.setForum("forum-1");
        publication.setCoverDate("2025-01-01");
        publication.setScopusSubtype("ch");
        publication.setSubtype("ch");
        publication.setTitle("AINA LNDECT Chapter");

        Forum forum = new Forum();
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
        when(scoringFactoryService.getScoringService(Indicator.Strategy.CS_CONFERENCE)).thenReturn(conferenceScoringService);

        Indicator indicator = indicator(Indicator.Type.PUBLICATIONS, "S/max(N-2, 1)");
        indicator.setScoringStrategy(Indicator.Strategy.CS_CONFERENCE);
        indicator.setScoreYearRange("IY");

        Map<String, Score> result = scientificProductionService.calculateScientificProductionScore(List.of(publication), indicator);

        assertEquals(4.0, result.get(publication.getTitle()).getScore(), 0.0001);
        assertEquals("B", result.get(publication.getTitle()).getCategory());
        assertEquals("DBLP+CORE", result.get(publication.getTitle()).getScoringSource());
        assertEquals("DBLP", result.get(publication.getTitle()).getScoringInfo().get("matchSource"));
        ComputerScienceConferenceScoringService.ConferenceScoreTrace trace = conferenceScoringService.getLastTraceForTests();
        assertEquals(ComputerScienceConferenceScoringService.ResolutionSource.DBLP, trace.resolvedSource());
        assertEquals("AINA (6)", trace.dblpConferenceTitle());
    }

    private Indicator indicator(Indicator.Type type, String formula) {
        Indicator indicator = new Indicator();
        indicator.setOutputType(type);
        indicator.setFormula(formula);
        indicator.setScoringStrategy(Indicator.Strategy.CS);
        return indicator;
    }

    private Publication publication(String id, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setTitle(id);
        publication.setAuthors(authors);
        return publication;
    }

    private Score score(double value) {
        Score score = new Score();
        score.setScore(value);
        score.setAuthorScore(0.0);
        return score;
    }
}
