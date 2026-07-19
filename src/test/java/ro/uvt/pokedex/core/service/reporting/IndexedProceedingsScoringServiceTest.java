package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexedProceedingsScoringServiceTest {

    private final ReportingLookupPort lookupPort = mock(ReportingLookupPort.class);
    private final IndexedProceedingsScoringService service = new IndexedProceedingsScoringService(lookupPort);
    private final Indicator indicator = new Indicator();

    private ScoringPublicationReadModel pub(String subtype) {
        ScoringPublicationReadModel p = mock(ScoringPublicationReadModel.class);
        when(p.getScopusSubtype()).thenReturn(subtype);
        return p;
    }

    @Test
    void strategyIsIndexedProceedings() {
        assertEquals(ScoringStrategy.INDEXED_PROCEEDINGS, service.strategy());
    }

    @Test
    void proceedingsPaperScoresFlatOne() {
        Score s = service.getScore(pub("cp"), indicator);
        assertEquals(1.0, s.getScore());
        assertEquals("BDI", s.getCoreRankingEquivalent());
    }

    @Test
    void journalArticleScoresZero() {
        assertEquals(0.0, service.getScore(pub("ar"), indicator).getScore());
    }

    @Test
    void bookScoresZero() {
        assertEquals(0.0, service.getScore(pub("bk"), indicator).getScore());
    }

    @Test
    void nullPublicationScoresZero() {
        assertEquals(0.0, service.getScore((ScoringPublicationReadModel) null, indicator).getScore());
    }
}
