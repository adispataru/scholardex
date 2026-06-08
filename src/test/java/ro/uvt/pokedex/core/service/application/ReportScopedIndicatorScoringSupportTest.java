package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ReportScopedIndicatorScoringSupportTest {

    @Test
    void computeCitationViewMergesDuplicatePublicationTitlesAndBuildsDisplayMetadata() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS");

        ScholardexPublicationView citedA = publication("cited-a", "same-title", List.of("ra"), "forum-a");
        ScholardexPublicationView citedB = publication("cited-b", "same-title", List.of("rb"), null);

        ScholardexPublicationView citing1 = publication("cit-1", "cit-title-1", List.of("x"), "forum-c1");
        ScholardexPublicationView citing2 = publication("cit-2", null, List.of("y"), null);

        Map<String, List<ScholardexPublicationView>> byCited = new LinkedHashMap<>();
        byCited.put("cited-a", List.of(citing1, citing2));
        byCited.put("cited-b", List.of(citing1));
        Map<String, ScholardexPublicationView> byId = Map.of("cit-1", citing1, "cit-2", citing2);
        ReportScopedIndicatorScoringSupport.CitationContext context =
                new ReportScopedIndicatorScoringSupport.CitationContext(byId, byCited, 3);

        when(scientificProductionService.calculateScientificImpactScore(any(), anyList(), any(), anyMap()))
                .thenReturn(Map.of("k1", score(1.5, 1.0, "Q1"), "total", score(0, 0, null)))
                .thenReturn(Map.of("k2", score(2.5, 2.0, "Q2"), "total", score(0, 0, null)));

        ReportScopedIndicatorScoringSupport.CitationViewComputation computation =
                ReportScopedIndicatorScoringSupport.computeCitationView(
                        indicator,
                        List.of(citedA, citedB),
                        Set.of("ra"),
                        context,
                        Map.of(),
                        scientificProductionService
                );

        assertEquals(4.0, computation.totalScore(), 0.0001);
        assertEquals(3, computation.totalCitationCount());
        assertEquals(1, computation.displayScores().size());
        assertTrue(computation.displayScores().get("same-title").containsKey("k1"));
        assertTrue(computation.displayScores().get("same-title").containsKey("k2"));
        assertEquals(1, computation.citationMap().size());
        assertTrue(computation.citationMap().containsKey("cit-title-1"));
        assertEquals(Set.of("forum-a", "forum-c1"), computation.forumIds());
        assertEquals(List.of("Q1", "Q2"), computation.quarterLabels());
        assertEquals(List.of(1, 1), computation.quarterValues());
    }

    @Test
    void computeCitationViewExcludeSelfFiltersCitationsFromResearcherAuthors() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS_EXCLUDE_SELF");

        ScholardexPublicationView cited = publication("cited-a", "paper-a", List.of("ra"), "forum-a");
        ScholardexPublicationView selfCitation = publication("cit-self", "self", List.of("ra"), "forum-self");
        ScholardexPublicationView externalCitation = publication("cit-ext", "ext", List.of("other"), "forum-ext");

        ReportScopedIndicatorScoringSupport.CitationContext context =
                new ReportScopedIndicatorScoringSupport.CitationContext(
                        Map.of("cit-self", selfCitation, "cit-ext", externalCitation),
                        Map.of("cited-a", List.of(selfCitation, externalCitation)),
                        2
                );

        when(scientificProductionService.calculateScientificImpactScore(any(), anyList(), any(), anyMap()))
                .thenReturn(Map.of("ext", score(3.0, 3.0, "Q1"), "total", score(0, 0, null)));

        ReportScopedIndicatorScoringSupport.CitationViewComputation computation =
                ReportScopedIndicatorScoringSupport.computeCitationView(
                        indicator,
                        List.of(cited),
                        Set.of("ra"),
                        context,
                        Map.of(),
                        scientificProductionService
                );

        assertEquals(1, computation.totalCitationCount());
        assertFalse(computation.citationMap().containsKey("self"));
        assertTrue(computation.citationMap().containsKey("ext"));
    }

    @Test
    void computeCitationViewExcludeSelfWithEmptyResearcherSetDoesNotFilterAnything() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        Indicator indicator = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(indicator, "CITATIONS_EXCLUDE_SELF");

        ScholardexPublicationView cited = publication("cited-a", "paper-a", List.of("ra"), "forum-a");
        ScholardexPublicationView citationA = publication("cit-a", "a", List.of("ra"), "f1");
        ScholardexPublicationView citationB = publication("cit-b", "b", List.of("other"), "f2");

        ReportScopedIndicatorScoringSupport.CitationContext context =
                new ReportScopedIndicatorScoringSupport.CitationContext(
                        Map.of("cit-a", citationA, "cit-b", citationB),
                        Map.of("cited-a", List.of(citationA, citationB)),
                        2
                );

        when(scientificProductionService.calculateScientificImpactScore(any(), anyList(), any(), anyMap()))
                .thenReturn(Map.of("k", score(1.0, 1.0, "Q1"), "total", score(0, 0, null)));

        ReportScopedIndicatorScoringSupport.CitationViewComputation computation =
                ReportScopedIndicatorScoringSupport.computeCitationView(
                        indicator,
                        List.of(cited),
                        Set.of(),
                        context,
                        Map.of(),
                        scientificProductionService
                );

        assertEquals(2, computation.totalCitationCount());
        assertTrue(computation.citationMap().containsKey("a"));
        assertTrue(computation.citationMap().containsKey("b"));
    }

    @Test
    void prepareCitationContextSkipsNullAndUnresolvableCitations() {
        ScholardexProjectionReadService projectionReadService = mock(ScholardexProjectionReadService.class);
        ScholardexPublicationView cited = publication("cited-1", "cited", List.of("a"), "forum-a");
        ScholardexPublicationView citing = publication("cit-1", "valid-citing", List.of("x"), "forum-x");

        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView valid =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView();
        valid.setCitedId("cited-1");
        valid.setCitingId("cit-1");
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView missingCiting =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView();
        missingCiting.setCitedId("cited-1");
        missingCiting.setCitingId("unknown");
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView missingCited =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView();
        missingCited.setCitedId(null);
        missingCited.setCitingId("cit-1");

        when(projectionReadService.findAllCitationsByCitedIdIn(List.of("cited-1")))
                .thenReturn(new ArrayList<>(java.util.Arrays.asList(valid, missingCiting, missingCited)));
        when(projectionReadService.findAllPublicationsByIdIn(List.of("cit-1", "unknown", "cit-1")))
                .thenReturn(List.of(citing));

        ReportScopedIndicatorScoringSupport.CitationContext context =
                ReportScopedIndicatorScoringSupport.prepareCitationContext(List.of(cited), projectionReadService);

        assertEquals(3, context.citationFactsCount());
        assertEquals(1, context.citingPublicationsById().size());
        assertTrue(context.citingPublicationsByCitedPublicationId().containsKey("cited-1"));
        assertEquals(1, context.citingPublicationsByCitedPublicationId().get("cited-1").size());
    }

    @Test
    void precomputeCitationBaseScoresByIndicatorIncludesOnlyCitationIndicatorsAndSkipsNull() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        ScholardexPublicationView citing = publication("cit-1", "t", List.of("a"), null);
        ReportScopedIndicatorScoringSupport.CitationContext context =
                new ReportScopedIndicatorScoringSupport.CitationContext(
                        Map.of("cit-1", citing),
                        Map.of(),
                        1
                );

        Indicator citation = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citation, "CITATIONS");
        Indicator citationExclude = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citationExclude, "CITATIONS_EXCLUDE_SELF");
        Indicator nonCitation = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(nonCitation, "PUBLICATIONS");

        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(citation))).thenReturn(Map.of("x", score(1, 1, null)));
        when(scientificProductionService.precomputeCitationBaseScores(anyList(), eq(citationExclude))).thenReturn(Map.of("y", score(2, 2, null)));

        Map<Indicator, Map<String, Score>> out = ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                new ArrayList<>(java.util.Arrays.asList(citation, nonCitation, null, citationExclude)),
                context,
                scientificProductionService
        );

        assertEquals(2, out.size());
        assertTrue(out.containsKey(citation));
        assertTrue(out.containsKey(citationExclude));
        assertFalse(out.containsKey(nonCitation));
    }

    @Test
    void precomputeCitationBaseScoresByIndicatorPassesUniqueCitingPublications() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        ScholardexPublicationView citing = publication("cit-1", "t", List.of("a"), null);
        ReportScopedIndicatorScoringSupport.CitationContext context =
                new ReportScopedIndicatorScoringSupport.CitationContext(
                        Map.of("cit-1", citing),
                        Map.of(),
                        1
                );
        Indicator citation = new Indicator();
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setOutputType(citation, "CITATIONS");

        doAnswer(invocation -> {
            List<ScoringPublicationReadModel> pubs = invocation.getArgument(0);
            return Map.of("size", score(pubs.size(), pubs.size(), null));
        }).when(scientificProductionService).precomputeCitationBaseScores(anyList(), eq(citation));

        Map<Indicator, Map<String, Score>> out = ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                List.of(citation),
                context,
                scientificProductionService
        );

        assertEquals(1.0, out.get(citation).get("size").getAuthorScore(), 0.0001);
    }

    @Test
    void precomputeCitationBaseScoresByIndicatorReturnsEmptyForNullIndicatorsAndNoCitingPublications() {
        ScientificProductionService scientificProductionService = mock(ScientificProductionService.class);
        ReportScopedIndicatorScoringSupport.CitationContext emptyContext =
                new ReportScopedIndicatorScoringSupport.CitationContext(Map.of(), Map.of(), 0);

        assertTrue(ReportScopedIndicatorScoringSupport
                .precomputeCitationBaseScoresByIndicator(null, emptyContext, scientificProductionService).isEmpty());
        assertTrue(ReportScopedIndicatorScoringSupport
                .precomputeCitationBaseScoresByIndicator(List.of(), emptyContext, scientificProductionService).isEmpty());
        assertTrue(ReportScopedIndicatorScoringSupport
                .precomputeCitationBaseScoresByIndicator(List.of(new Indicator()), emptyContext, scientificProductionService).isEmpty());
    }

    @Test
    void mergeCitationScoresGuardsAndMetadataPropagation() throws Exception {
        Method merge = ReportScopedIndicatorScoringSupport.class.getDeclaredMethod("mergeCitationScores", Map.class, Map.class);
        merge.setAccessible(true);

        Map<String, Score> target = new LinkedHashMap<>();
        Score existing = score(2.0, 3.0, "Q1");
        existing.setCoreRankingEquivalent("A");
        target.put("k", existing);
        Map<String, Score> delta = new LinkedHashMap<>();
        Score incoming = score(4.0, 5.0, "Q2");
        incoming.setCoreRankingEquivalent("B");
        delta.put("k", incoming);

        merge.invoke(null, target, delta);

        assertEquals(6.0, target.get("k").getAuthorScore(), 0.0001);
        assertEquals(8.0, target.get("k").getScore(), 0.0001);
        assertEquals("Q1", target.get("k").getQuarter());
        assertEquals("A", target.get("k").getCoreRankingEquivalent());

        merge.invoke(null, target, null);
        assertEquals(1, target.size());
    }

    private static ScholardexPublicationView publication(String id, String title, List<String> authorIds, String forumId) {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(id);
        publication.setTitle(title);
        publication.setAuthors(authorIds);
        publication.setForum(forumId);
        return publication;
    }

    private static Score score(double authorScore, double scoreValue, String quarter) {
        Score score = new Score();
        score.setAuthorScore(authorScore);
        score.setScore(scoreValue);
        score.setQuarter(quarter);
        return score;
    }
}
