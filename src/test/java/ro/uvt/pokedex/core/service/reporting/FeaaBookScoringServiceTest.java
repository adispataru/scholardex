package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeaaBookScoringServiceTest {

    private final ReportingLookupPort lookupPort = mock(ReportingLookupPort.class);
    private final FeaaAnexa1PublisherService anexa1 = mock(FeaaAnexa1PublisherService.class);
    private final FeaaNationalPublisherService national = mock(FeaaNationalPublisherService.class);
    private final FeaaBookScoringService service = new FeaaBookScoringService(lookupPort, anexa1, national);
    private final Indicator indicator = new Indicator();

    private Indicator indicator2026() {
        Indicator i = new Indicator();
        i.setFeaaBookTiers2026(true);
        return i;
    }

    private ScoringPublicationReadModel pub(String subtype, String publisher, boolean prestige) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublisher(publisher);
        when(lookupPort.getForum(any())).thenReturn(forum);
        when(anexa1.isPrestigePublisher(publisher)).thenReturn(prestige);
        ScoringPublicationReadModel p = mock(ScoringPublicationReadModel.class);
        when(p.getForumId()).thenReturn("forum-1");
        when(p.getScopusSubtype()).thenReturn(subtype);
        return p;
    }

    @Test
    void strategyIsFeaaBook() {
        assertEquals(ScoringStrategy.FEAA_BOOK, service.strategy());
    }

    @Test
    void bookInAnexa1ScoresHalf() {
        assertEquals(0.5, service.getScore(pub("bk", "Elsevier", true), indicator).getScore());
    }

    @Test
    void chapterInAnexa1ScoresQuarter() {
        assertEquals(0.25, service.getScore(pub("ch", "Springer", true), indicator).getScore());
    }

    @Test
    void nationalBookScoresPointTwo() {
        assertEquals(0.2, service.getScore(pub("bk", "Editura Locala", false), indicator).getScore());
    }

    @Test
    void nationalChapterScoresPointOne() {
        assertEquals(0.1, service.getScore(pub("ch", "Editura Locala", false), indicator).getScore());
    }

    @Test
    void isiProceedingsScorePointOne() {
        assertEquals(0.1, service.getScore(pub("cp", "Some Conf", false), indicator).getScore());
    }

    @Test
    void bookScoreResolvesPublisherFromBookRegistryNotForum() {
        // H66B M7-C: a book publication carries a bookId; its publisher comes from the book registry,
        // and the forum is never consulted (book venues set forumId=null).
        ScholardexBookFact book = new ScholardexBookFact();
        book.setPublisher("Springer");
        when(lookupPort.getBook("bk-1")).thenReturn(book);
        when(anexa1.isPrestigePublisher("Springer")).thenReturn(true);
        ScoringPublicationReadModel p = mock(ScoringPublicationReadModel.class);
        when(p.getBookId()).thenReturn("bk-1");
        when(p.getScopusSubtype()).thenReturn("bk");

        assertEquals(0.5, service.getScore(p, indicator).getScore());
        verify(lookupPort, never()).getForum(any());
    }

    @Test
    void journalArticleGetsNoBookScore() {
        // 'ar' (article) is scored by ECONOMICS_JOURNAL_AIS, not here → base score stays 0.
        assertEquals(0.0, service.getScore(pub("ar", "Elsevier", true), indicator).getScore());
    }

    // ── FEAA 2026 five-tier coefficients (feaaBookTiers2026 flag) ──

    private ScoringPublicationReadModel pub2026(String subtype, String publisher, boolean prestige, boolean nationalHit) {
        ScoringPublicationReadModel p = pub(subtype, publisher, prestige);
        when(national.isRecognized(publisher)).thenReturn(nationalHit);
        return p;
    }

    @Test
    void tiers2026IntlBookAndChapter() {
        assertEquals(0.5, service.getScore(pub2026("bk", "Elsevier", true, false), indicator2026()).getScore());
        assertEquals(0.3, service.getScore(pub2026("ch", "Springer", true, false), indicator2026()).getScore());
    }

    @Test
    void tiers2026NationalRecognizedBookAndChapter() {
        assertEquals(0.25, service.getScore(pub2026("bk", "Editura ASE", false, true), indicator2026()).getScore());
        assertEquals(0.15, service.getScore(pub2026("ch", "Editura ASE", false, true), indicator2026()).getScore());
        assertEquals("FEAA_BOOK_NATIONAL",
                service.getScore(pub2026("bk", "Editura ASE", false, true), indicator2026()).getCoreRankingEquivalent());
    }

    @Test
    void tiers2026UnlistedPublisherAndProceedingsDropToPointOne() {
        // 2026 change vs 2016: an unlisted-publisher BOOK is 0.1 (2016 gave 0.2 to any non-Anexa1 book).
        assertEquals(0.1, service.getScore(pub2026("bk", "Complet Necunoscuta", false, false), indicator2026()).getScore());
        assertEquals(0.1, service.getScore(pub2026("ch", "Complet Necunoscuta", false, false), indicator2026()).getScore());
        assertEquals(0.1, service.getScore(pub2026("cp", "Some Conf", false, false), indicator2026()).getScore());
    }

    @Test
    void legacyIndicatorKeeps2016Coefficients() {
        // No flag → the 2016 four-slot table, even when the national register would match.
        assertEquals(0.2, service.getScore(pub2026("bk", "Editura ASE", false, true), indicator).getScore());
    }
}
