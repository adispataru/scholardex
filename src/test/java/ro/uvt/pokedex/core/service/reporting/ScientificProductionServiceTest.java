package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScientificProductionServiceTest {

    @Mock
    private ScoringFactoryService scoringFactoryService;

    @Mock
    private ScoringService scoringService;

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
