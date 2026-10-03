package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** H142 — Comisia 35 (Music) CS 2.1: an article or proceedings paper in an indexed venue. */
@ExtendWith(MockitoExtension.class)
class MusicIndexedJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private MusicIndexedJournalScoringService service;

    @BeforeEach
    void setUp() {
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        service = new MusicIndexedJournalScoringService(lookupPort);
    }

    private static Indicator indicator() {
        Domain domain = new Domain();
        domain.setId("ALL");
        domain.setName("ALL");
        domain.setWosCategories(new ArrayList<>(List.of("*")));
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private static ScoringPublication publication(String subtype, String forumId) {
        return new ScoringPublication("pub-1", "eid-1", forumId, "2023-01-01", subtype, null,
                List.of("a1"), 1, "10.1000/pub-1", null, "Paper", 0, Set.of());
    }

    @Test
    void strategyIsMusicIndexedJournal() {
        assertEquals(ScoringStrategy.MUSIC_INDEXED_JOURNAL, service.strategy());
    }

    @Test
    void anArticleInAWebOfScienceJournalScoresWhateverTheEdition() {
        when(lookupPort.getForumIndexingDatabases("forum-1")).thenReturn(Set.of("AHCI", "SCOPUS"));
        Score score = service.getScore(publication("ar", "forum-1"), indicator());
        assertEquals(1.0, score.getScore());
        assertEquals("WOS", score.getCoreRankingEquivalent());
        assertEquals(2023, score.getYear());
    }

    @Test
    void scopusThenErihPlusThenDoajNameTheDatabase() {
        when(lookupPort.getForumIndexingDatabases("scopus")).thenReturn(Set.of("SCOPUS", "ERIH"));
        when(lookupPort.getForumIndexingDatabases("erih")).thenReturn(Set.of("ERIH", "DOAJ"));
        when(lookupPort.getForumIndexingDatabases("doaj")).thenReturn(Set.of("DOAJ"));
        assertEquals("SCOPUS", service.getScore(publication("ar", "scopus"), indicator()).getCoreRankingEquivalent());
        assertEquals("ERIH PLUS", service.getScore(publication("re", "erih"), indicator()).getCoreRankingEquivalent());
        assertEquals("DOAJ", service.getScore(publication("ar", "doaj"), indicator()).getCoreRankingEquivalent());
    }

    @Test
    void aProceedingsPaperCountsInAScopusOrConferenceIndexedVolumeOnly() {
        when(lookupPort.getForumIndexingDatabases("cpci")).thenReturn(Set.of());
        when(lookupPort.isForumCpciIndexed("cpci")).thenReturn(true);
        when(lookupPort.getForumIndexingDatabases("scopus-volume")).thenReturn(Set.of("SCOPUS"));
        when(lookupPort.isForumCpciIndexed("scopus-volume")).thenReturn(false);
        when(lookupPort.getForumIndexingDatabases("doaj-volume")).thenReturn(Set.of("DOAJ", "ERIH"));
        when(lookupPort.isForumCpciIndexed("doaj-volume")).thenReturn(false);

        assertEquals("WOS", service.getScore(publication("cp", "cpci"), indicator()).getCoreRankingEquivalent());
        assertEquals("SCOPUS", service.getScore(publication("cp", "scopus-volume"), indicator()).getCoreRankingEquivalent());
        Score journalListsDoNotIndexVolumes = service.getScore(publication("cp", "doaj-volume"), indicator());
        assertEquals(0.0, journalListsDoNotIndexVolumes.getScore());
        assertEquals("NOT_INDEXED", journalListsDoNotIndexVolumes.getScoringInfo().get("zeroReason"));
    }

    @Test
    void aJournalNoListCoversIsNotIndexed() {
        when(lookupPort.getForumIndexingDatabases("ceeol-only")).thenReturn(Set.of("CNCS"));
        Score score = service.getScore(publication("ar", "ceeol-only"), indicator());
        assertEquals(0.0, score.getScore());
        assertEquals("NOT_INDEXED", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void aJournalOnTheTitleListOfADatabaseOfTheListCounts() {
        // H142 slice 4: JSTOR, RILM, EBSCO… are memberships of the forum, loaded from their title lists like DOAJ
        when(lookupPort.getForumIndexingDatabases("muzica"))
                .thenReturn(Set.of(JournalDatabases.RILM, JournalDatabases.JSTOR, "CNCS"));

        Score score = service.getScore(publication("ar", "muzica"), indicator());

        assertEquals(1.0, score.getScore());
        assertEquals("JSTOR", score.getCoreRankingEquivalent(), "the first of the list's databases, by name");
    }

    @Test
    void aTitleListDoesNotMakeAProceedingsVolumeIndexed() {
        when(lookupPort.getForumIndexingDatabases("volume")).thenReturn(Set.of(JournalDatabases.EBSCO));

        assertEquals(0.0, service.getScore(publication("cp", "volume"), indicator()).getScore());
    }

    @Test
    void booksAndChaptersAreNotThisItem() {
        Score book = service.getScore(publication("bk", "forum-1"), indicator());
        assertEquals(0.0, book.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", book.getScoringInfo().get("zeroReason"));
        assertEquals(0.0, service.getScore(publication("ch", "forum-1"), indicator()).getScore());
    }

    @Test
    void aPaperWithoutAVenueIsNotIndexed() {
        Score score = service.getScore(publication("ar", null), indicator());
        assertEquals(0.0, score.getScore());
        assertEquals("NOT_INDEXED", score.getScoringInfo().get("zeroReason"));
    }
}
