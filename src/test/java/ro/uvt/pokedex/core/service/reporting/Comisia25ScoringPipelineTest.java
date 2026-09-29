package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.reporting.formula.FormulaEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * OM 3.019/2025, Comisia 25, through the real scoring service: the scorers, the formula and the variables the
 * engine binds, together. What the unit tests of the scorers cannot show is that a citation from a source
 * without impact factor reaches the formula at all, and that {@code Coef_m} is bound.
 */
@ExtendWith(MockitoExtension.class)
class Comisia25ScoringPipelineTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private ScientificProductionService service;

    @BeforeEach
    void setUp() {
        // A Spring context started by another test of the same run may have left its resolver registered.
        PublicationCoefficientSupport.register(null);
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
        ScoringFactoryService factory = new ScoringFactoryService(List.of(
                new ImpactFactorJournalScoringService(lookupPort, new SimpleMeterRegistry()),
                new SociologyIndexedJournalScoringService(lookupPort),
                new CitingImpactFactorScoringService(lookupPort)));
        service = new ScientificProductionService(factory, new FormulaEvaluator(), lookupPort,
                mock(PublicationCountryAuthorCountService.class), mock(WosMasterBookListService.class));
    }

    @AfterEach
    void forgetTheResolver() {
        PublicationCoefficientSupport.register(null);
    }

    private static Indicator indicator(String outputType, String strategy, String formula) {
        Domain all = new Domain();
        all.setId("ALL");
        all.setName("ALL");
        all.setWosCategories(List.of("*"));
        Indicator indicator = new Indicator();
        indicator.setName("Soc26_test");
        indicator.setDomain(all);
        indicator.setKind(IndicatorKind.of(outputType, strategy));
        indicator.setFormula(formula);
        indicator.setSociologie2026(true);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private static ScoringPublication publication(String id, String forumId, String subtype, int authors) {
        return new ScoringPublication(id, "eid-" + id, forumId, "2023-01-01", subtype, null,
                List.of("a1"), authors, "10.1000/" + id, null, "Title " + id, 0, Set.of());
    }

    private void journal(String forumId, String issn, Double impactFactor, Set<String> databases) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(forumId);
        forum.setPublicationName("Journal " + forumId);
        forum.setIssn(issn);
        forum.setAggregationType("Journal");
        lenient().when(lookupPort.getForum(forumId)).thenReturn(forum);
        lenient().when(lookupPort.getForumIndexingDatabases(forumId)).thenReturn(databases);
        if (impactFactor == null) {
            lenient().when(lookupPort.getRankingsByIssn(issn)).thenReturn(List.of());
            return;
        }
        WoSRanking.Score score = new WoSRanking.Score();
        score.setIF(Map.of(2023, impactFactor));
        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-" + forumId);
        ranking.setIssn(issn);
        ranking.setWebOfScienceCategoryIndex(Map.of("SOCIAL WORK - ESCI", new WoSRanking.Rank()));
        ranking.setScore(score);
        lenient().when(lookupPort.getRankingsByIssn(issn)).thenReturn(List.of(ranking));
    }

    @Test
    void anArticleScoresAtExactlyOneOfTheTwoJournalIndicators() {
        journal("with-if", "1111-1111", 1.5, Set.of("SCOPUS", "ESCI"));
        journal("scopus-only", "2222-2222", null, Set.of("SCOPUS"));
        List<ScoringPublication> publications = List.of(
                publication("p1", "with-if", "ar", 2), publication("p2", "scopus-only", "ar", 2));

        Map<String, Score> first = service.calculateScientificProductionScore(publications,
                indicator("PUBLICATIONS", "IMPACT_FACTOR", "(2 + 4 * S) * 2 / N"));
        Map<String, Score> second = service.calculateScientificProductionScore(publications,
                indicator("PUBLICATIONS", "SOC_INDEXED_JOURNAL", "S * Coef_m / N"));

        assertEquals((2 + 4 * 1.5) * 2 / 2, first.get("total").getAuthorScore(), 1e-9);
        assertEquals(4.0 / 2, second.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void theCoefficientIsOneWhereNothingIsKnownAndTheResolvedValueOtherwise() {
        journal("scopus-only", "2222-2222", null, Set.of("SCOPUS"));
        Indicator second = indicator("PUBLICATIONS", "SOC_INDEXED_JOURNAL", "S * Coef_m / N");
        List<ScoringPublication> publications = List.of(publication("p2", "scopus-only", "ar", 2));

        Map<String, Score> provisional = service.calculateScientificProductionScore(publications, second);

        assertEquals(2.0, provisional.get("total").getAuthorScore(), 1e-9);
        assertEquals(PublicationCoefficientSupport.NOT_DETERMINED,
                provisional.get("Title p2").getScoringInfo().get(PublicationCoefficientSupport.BASIS_KEY));

        PublicationCoefficientSupport.register(publication ->
                new PublicationCoefficientSupport.Coefficient(2.0, "ABROAD_INTERNATIONAL_LANGUAGE"));
        Map<String, Score> resolved = service.calculateScientificProductionScore(publications, second);

        assertEquals(4.0, resolved.get("total").getAuthorScore(), 1e-9);
        assertEquals("ABROAD_INTERNATIONAL_LANGUAGE",
                resolved.get("Title p2").getScoringInfo().get(PublicationCoefficientSupport.BASIS_KEY));
    }

    @Test
    void aValueOutsideWhatTheAnnexAllowsIsNotApplied() {
        journal("scopus-only", "2222-2222", null, Set.of("SCOPUS"));
        PublicationCoefficientSupport.register(publication ->
                new PublicationCoefficientSupport.Coefficient(3.0, "WRONG"));

        Map<String, Score> scores = service.calculateScientificProductionScore(
                List.of(publication("p2", "scopus-only", "ar", 2)),
                indicator("PUBLICATIONS", "SOC_INDEXED_JOURNAL", "S * Coef_m / N"));

        assertEquals(2.0, scores.get("total").getAuthorScore(), 1e-9);
    }

    @Test
    void everyCitationScoresAndIsCounted() {
        journal("with-if", "1111-1111", 1.5, Set.of("SCOPUS", "ESCI"));
        journal("scopus-only", "2222-2222", null, Set.of("SCOPUS"));
        ScoringPublication cited = publication("mine", "scopus-only", "ar", 2);
        List<ScoringPublication> citing = List.of(
                publication("c1", "with-if", "ar", 5),      // journal with impact factor 1,5
                publication("c2", "scopus-only", "ar", 1),  // journal without impact factor
                publication("c3", "book-series", "ch", 3)); // chapter in a volume

        Map<String, Score> points = service.calculateScientificImpactScore(cited, citing,
                indicator("CITATIONS_EXCLUDE_SELF", "CITING_IMPACT_FACTOR", "S * 2 / N"));
        Map<String, Score> count = service.calculateScientificImpactScore(cited, citing,
                indicator("CITATIONS_EXCLUDE_SELF", "CITING_IMPACT_FACTOR", "1"));

        // n is the number of authors of the CITED work (2), whatever the citing works have.
        assertEquals((0.2 + 4 * 1.5) * 2 / 2 + 0.2 * 2 / 2 + 0.2 * 2 / 2, points.get("total").getAuthorScore(), 1e-9);
        assertEquals(3.0, count.get("total").getAuthorScore(), 1e-9);
    }
}
