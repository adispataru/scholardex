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
    private final WosMasterBookListService masterBookList = mock(WosMasterBookListService.class);
    private final PsychologyBookScoringService service =
            new PsychologyBookScoringService(lookupPort, publishers, masterBookList);
    private final Indicator indicator = new Indicator();

    private static Indicator indicator2026() {
        Indicator i = new Indicator();
        i.setPsihologie2026(true);
        return i;
    }

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
        Score s = service.getScore(pub("ar", "Editura Polirom", "A2"), indicator);
        assertEquals(0.0, s.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", s.getScoringInfo().get("zeroReason"));
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

    // ── Psihologie 2026 (OM 3019/2025, Comisia 28) — psihologie2026 flag ──

    @Test
    void rules2026ReadTheTierFromThe2026ListNotThe2016One() {
        ScoringPublicationReadModel p = pub("bk", "Editura Humanitas", null); // not on the 2016 list
        when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Humanitas")).thenReturn("B");

        Score s = service.getScore(p, indicator2026());

        assertEquals(0.5, s.getScore());
        assertEquals("B", s.getCoreRankingEquivalent());
        verify(publishers, never()).tierFor(any());
    }

    @Test
    void rules2026DropAPublisherThatOnlyThe2016ListKnows() {
        // Editura All was tier B in 2016 and is on neither 2026 list.
        ScoringPublicationReadModel p = pub("bk", "Editura All", "B");
        when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura All")).thenReturn(null);
        when(masterBookList.isRecognized("Editura All")).thenReturn(false);

        assertEquals(0.0, service.getScore(p, indicator2026()).getScore());
    }

    @Test
    void rules2026ClassifyAMasterBookListPublisherAsIndicativeA1() {
        ScoringPublicationReadModel p = pub("ch", "Routledge", null);
        when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Routledge")).thenReturn(null);
        when(masterBookList.isRecognized("Routledge")).thenReturn(true);

        Score s = service.getScore(p, indicator2026());

        assertEquals(3.0, s.getScore());
        assertEquals("A1", s.getCoreRankingEquivalent());
        assertEquals("WOS_MASTER_BOOK_LIST", s.getScoringInfo().get("tierBasis"));
    }

    @Test
    void rules2026KeepTheListedTierEvenWhenThePublisherIsAlsoOnTheMasterBookList() {
        ScoringPublicationReadModel p = pub("bk", "Editura Polirom", null);
        when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Polirom")).thenReturn("A2");

        Score s = service.getScore(p, indicator2026());

        assertEquals(1.0, s.getScore());
        assertEquals("A2", s.getCoreRankingEquivalent());
        verify(masterBookList, never()).isRecognized(any());
    }

    @Test
    void the2016RulesNeverConsultTheMasterBookList() {
        service.getScore(pub("bk", "Routledge", null), indicator);
        verify(masterBookList, never()).isRecognized(any());
        verify(publishers, never()).tierFor2026(any(), any());
    }

    // ── Sociologie 2026 (OM 3019/2025, Comisia 25) — sociologie2026 flag ──

    private static Indicator indicatorComisia25() {
        Indicator i = new Indicator();
        i.setSociologie2026(true);
        return i;
    }

    @Test
    void comisia25ReadsTheA2ListOfItsGroupAndReturnsOneForEveryTier() {
        ScoringPublicationReadModel p = pub("ch", "Editura Polirom", null);
        when(publishers.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), "Editura Polirom")).thenReturn("A2");

        Score s = service.getScore(p, indicatorComisia25());

        assertEquals(1.0, s.getScore(), "the points are in the formula; the tier only reaches it as category");
        assertEquals("A2", s.getCoreRankingEquivalent());
        verify(masterBookList, never()).isRecognized(any());
        verify(publishers, never()).tierFor(any());
    }

    @Test
    void comisia25TakesAnInternationalPublisherAsA1() {
        ScoringPublicationReadModel p = pub("bk", "Routledge", null);
        when(publishers.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), "Routledge")).thenReturn(null);
        when(masterBookList.isRecognized("Routledge")).thenReturn(true);

        Score s = service.getScore(p, indicatorComisia25());

        assertEquals(1.0, s.getScore());
        assertEquals("A1", s.getCoreRankingEquivalent());
        assertEquals("WOS_MASTER_BOOK_LIST", s.getScoringInfo().get("tierBasis"));
    }

    @Test
    void comisia25CountsTheEarlierListForABookThatAppearedBeforeOctober2026() {
        String earlier = Comisia25Rules.SOCIOLOGIE.earlierPublisherList();
        ScoringPublicationReadModel old = pub("bk", "Lumina Lex", null);
        when(old.getCoverDate()).thenReturn("2018-03-01");
        when(publishers.tierFromList(earlier, "Lumina Lex")).thenReturn("A2");

        Score s = service.getScore(old, indicatorComisia25());

        assertEquals(1.0, s.getScore());
        assertEquals("A2", s.getCoreRankingEquivalent());
        assertEquals("EARLIER_LIST", s.getScoringInfo().get("tierBasis"));

        ScoringPublicationReadModel recent = pub("bk", "Lumina Lex", null);
        when(recent.getCoverDate()).thenReturn("2026-11-15");
        assertEquals(0.0, service.getScore(recent, indicatorComisia25()).getScore(), "the current list applies");
    }

    @Test
    void comisia25DoesNotCountAPublisherOnNeitherList() {
        ScoringPublicationReadModel p = pub("bk", "Random Press", null);
        when(publishers.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), "Random Press")).thenReturn(null);
        when(masterBookList.isRecognized("Random Press")).thenReturn(false);

        assertEquals(0.0, service.getScore(p, indicatorComisia25()).getScore());
    }

    @Test
    void anInternationalListOrRankingClassifiesA1WhereTheMasterBookListIsSilent() {
        InternationalPublisherSupport.register(
                new InternationalPublisherListService(InternationalPublisherListServiceTest.senseRankings()));
        try {
            ScoringPublicationReadModel harmattan = pub("bk", "L'Harmattan", null);
            when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "L'Harmattan")).thenReturn(null);
            when(masterBookList.isRecognized("L'Harmattan")).thenReturn(false);
            Score s = service.getScore(harmattan, indicator2026());
            assertEquals(3.0, s.getScore());
            assertEquals("A1", s.getCoreRankingEquivalent());
            assertEquals("UEFISCDI_STIINTE_SOCIALE", s.getScoringInfo().get("tierBasis"));

            ScoringPublicationReadModel brill = pub("ch", "Brill", null);
            when(publishers.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), "Brill")).thenReturn(null);
            when(masterBookList.isRecognized("Brill")).thenReturn(false);
            Score b = service.getScore(brill, indicatorComisia25());
            assertEquals("A1", b.getCoreRankingEquivalent());
            assertEquals("CNCS_STIINTE_SOCIALE", b.getScoringInfo().get("tierBasis"), "«Lista A1, în vigoare»");

            ScoringPublicationReadModel curzon = pub("bk", "Curzon Press", null);
            when(publishers.tierFromList(Comisia25Rules.SOCIOLOGIE.publisherList(), "Curzon Press")).thenReturn(null);
            when(masterBookList.isRecognized("Curzon Press")).thenReturn(false);
            assertEquals("SENSE", service.getScore(curzon, indicatorComisia25()).getScoringInfo().get("tierBasis"));

            ScoringPublicationReadModel economica = pub("bk", "Editura Economica", null);
            when(publishers.tierFor2026(Comisia28Rules.PSIHOLOGIE, "Editura Economica")).thenReturn(null);
            assertEquals(0.0, service.getScore(economica, indicator2026()).getScore(),
                    "a Romanian house is not the French Economica of Anexa 7c");
        } finally {
            InternationalPublisherSupport.reset();
        }
    }

    @Test
    void nullPublicationScoresZeroWithoutSettingCategory() {
        Score s = service.getScore((ScoringPublicationReadModel) null, indicator);
        assertEquals(0.0, s.getScore());
        assertNull(s.getCoreRankingEquivalent());
    }
}
