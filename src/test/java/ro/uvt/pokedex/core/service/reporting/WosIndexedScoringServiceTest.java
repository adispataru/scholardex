package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosIndexedScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private WosIndexedScoringService service;
    private final Indicator indicator = new Indicator();

    @BeforeEach
    void setUp() {
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        service = new WosIndexedScoringService(lookupPort);
    }

    private static ScoringPublication publication(String forumId, String coverDate) {
        return new ScoringPublication("pub-1", "eid-1", forumId, coverDate, "ar", null,
                List.of("a1"), 1, "10.1000/pub-1", null, "Citing paper", 0, Set.of());
    }

    @Test
    void strategyIsWosIndexed() {
        assertEquals(ScoringStrategy.WOS_INDEXED, service.strategy());
    }

    @Test
    void aSocialSciencesJournalScoresOneInItsOwnYear() {
        when(lookupPort.isForumInSsci("forum-1", 2021)).thenReturn(true);

        Score s = service.getScore(publication("forum-1", "2021-05-01"), indicator);

        assertEquals(1.0, s.getScore());
        assertEquals("SSCI", s.getCoreRankingEquivalent());
        assertEquals(2021, s.getYear());
        assertNull(s.getScoringInfo().get("zeroReason"));
        // Short-circuits: the remaining editions are never asked.
        verify(lookupPort, never()).isForumInScie(anyString(), anyInt());
        verify(lookupPort, never()).isForumInEsci(anyString(), anyInt());
    }

    @Test
    void anEmergingSourcesJournalCountsBecauseTheStandardListsEsciUnderCoreCollection() {
        when(lookupPort.isForumInEsci("forum-1", 2023)).thenReturn(true);

        Score s = service.getScore(publication("forum-1", "2023-01-15"), indicator);

        assertEquals(1.0, s.getScore());
        assertEquals("ESCI", s.getCoreRankingEquivalent());
    }

    @Test
    void anArtsAndHumanitiesJournalCountsToo() {
        when(lookupPort.isForumInAhci("forum-1", 2020)).thenReturn(true);
        assertEquals("AHCI", service.getScore(publication("forum-1", "2020-01-01"), indicator)
                .getCoreRankingEquivalent());
    }

    @Test
    void aJournalOutsideWebOfScienceScoresZeroWithAReason() {
        Score s = service.getScore(publication("forum-1", "2022-01-01"), indicator);

        assertEquals(0.0, s.getScore());
        assertEquals("NOT_WOS_INDEXED", s.getScoringInfo().get("zeroReason"));
    }

    @Test
    void membershipIsAskedForThePublicationYearNotToday() {
        // Indexed from 2022 on; a 2019 citing paper in the same journal does not count.
        lenient().when(lookupPort.isForumInScie("forum-1", 2022)).thenReturn(true);

        assertEquals(0.0, service.getScore(publication("forum-1", "2019-03-01"), indicator).getScore());
        assertEquals(1.0, service.getScore(publication("forum-1", "2022-03-01"), indicator).getScore());
    }

    @Test
    void aPublicationWithoutADateFallsBackToTheLatestRankedYear() {
        when(lookupPort.isForumInScie("forum-1", 2025)).thenReturn(true);

        Score s = service.getScore(publication("forum-1", null), indicator);

        assertEquals(1.0, s.getScore());
        assertEquals(2025, s.getYear());
    }

    @Test
    void aPublicationWithoutAForumScoresZeroWithoutAnyLookup() {
        assertEquals(0.0, service.getScore(publication(null, "2022-01-01"), indicator).getScore());
        assertEquals(0.0, service.getScore((ScoringPublicationReadModel) null, indicator).getScore());
        verify(lookupPort, never()).isForumInSsci(anyString(), anyInt());
    }
}
