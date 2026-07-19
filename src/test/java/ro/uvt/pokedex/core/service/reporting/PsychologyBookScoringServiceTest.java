package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PsychologyBookScoringServiceTest {

    private final ReportingLookupPort lookupPort = mock(ReportingLookupPort.class);
    private final PsihologiePublisherService publishers = mock(PsihologiePublisherService.class);
    private final PsychologyBookScoringService service = new PsychologyBookScoringService(lookupPort, publishers);
    private final Indicator indicator = new Indicator();

    private ScoringPublicationReadModel pub(String subtype, String publisher, String tier) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublisher(publisher);
        when(lookupPort.getForum(any())).thenReturn(forum);
        when(publishers.tierFor(publisher)).thenReturn(tier);
        ScoringPublicationReadModel p = mock(ScoringPublicationReadModel.class);
        when(p.getForumId()).thenReturn("forum-1");
        when(p.getScopusSubtype()).thenReturn(subtype);
        return p;
    }

    @Test
    void strategyIsPsychBook() {
        assertEquals(ScoringStrategy.PSYCH_BOOK, service.strategy());
    }

    @Test
    void bookInTierA1ScoresMultiplierThreeAndExposesTierAsCategory() {
        Score s = service.getScore(pub("bk", "Prestige International", "A1"), indicator);
        assertEquals(3.0, s.getScore());
        assertEquals("A1", s.getCoreRankingEquivalent());
    }

    @Test
    void bookInTierA2ScoresMultiplierOne() {
        Score s = service.getScore(pub("bk", "Editura Polirom", "A2"), indicator);
        assertEquals(1.0, s.getScore());
        assertEquals("A2", s.getCoreRankingEquivalent());
    }

    @Test
    void bookInTierBScoresMultiplierHalf() {
        assertEquals(0.5, service.getScore(pub("bk", "Editura All", "B"), indicator).getScore());
    }

    @Test
    void chapterCarriesTheSameTierMultiplier() {
        // The 12 vs 3 base (book vs chapter) lives in the indicator formula via docType; the scorer
        // returns only the tier multiplier, so a chapter in an A2 publisher also yields S=1.
        Score s = service.getScore(pub("ch", "Editura Trei", "A2"), indicator);
        assertEquals(1.0, s.getScore());
        assertEquals("A2", s.getCoreRankingEquivalent());
    }

    @Test
    void unlistedPublisherScoresZero() {
        assertEquals(0.0, service.getScore(pub("bk", "Random Press", null), indicator).getScore());
    }

    @Test
    void journalArticleGetsNoBookScore() {
        // 'ar' is scored by IMPACT_FACTOR, not here; the publisher list is never consulted.
        assertEquals(0.0, service.getScore(pub("ar", "Editura Polirom", "A2"), indicator).getScore());
    }

    @Test
    void bookResolvesPublisherFromBookRegistryNotForum() {
        ScholardexBookFact book = new ScholardexBookFact();
        book.setPublisher("Editura ASCR");
        when(lookupPort.getBook("bk-1")).thenReturn(book);
        when(publishers.tierFor("Editura ASCR")).thenReturn("A2");
        ScoringPublicationReadModel p = mock(ScoringPublicationReadModel.class);
        when(p.getBookId()).thenReturn("bk-1");
        when(p.getScopusSubtype()).thenReturn("bk");

        assertEquals(1.0, service.getScore(p, indicator).getScore());
        verify(lookupPort, never()).getForum(any());
    }

    @Test
    void nullPublicationScoresZeroWithoutSettingCategory() {
        Score s = service.getScore((ScoringPublicationReadModel) null, indicator);
        assertEquals(0.0, s.getScore());
        assertNull(s.getCoreRankingEquivalent());
    }
}
