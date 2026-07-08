package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdWosEligibilityScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    private PdWosEligibilityScoringService service;

    @BeforeEach
    void setUp() {
        service = new PdWosEligibilityScoringService(lookupPort);
        lenient().when(lookupPort.maxAvailableYear()).thenReturn(2025);
        lenient().when(lookupPort.getForumRankings(any(), anyCollection(), any())).thenReturn(List.of());
    }

    @Test
    void scieMemberWithAisQuartileScoresOnePointAndExposesTheQuartile() {
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2023)).thenReturn(true);
        when(lookupPort.isForumInSsci("forum-1", 2023)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2023)).thenReturn(false);
        when(lookupPort.getForumRankings(eq(forum), eq(List.of(2023)), any()))
                .thenReturn(List.of(rankingWithAis("COMPUTER SCIENCE, INFORMATION SYSTEMS - SCIE", 2023, WoSRanking.Quarter.Q2)));

        Score score = service.getScore(publication("ar", "2023-05-01"), indicator());

        assertEquals(1.0, score.getScore());
        assertEquals("Q2", score.getQuarter());
        assertEquals("SCIE", score.getScoringSource());
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void esciOnlyJournalDoesNotQualifyButStaysAnUnstampedCandidateZero() {
        // The PD standard names SCIE/SSCI/AHCI explicitly — ESCI membership never qualifies,
        // even though the CNATDCU 2026 standard accepts it. The zero is a real "below threshold"
        // (an article at a non-qualifying venue), not a venue-type mismatch.
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2024)).thenReturn(false);
        when(lookupPort.isForumInSsci("forum-1", 2024)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2024)).thenReturn(false);

        Score score = service.getScore(publication("ar", "2024-02-01"), indicator());

        assertEquals(0.0, score.getScore());
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void ahciMemberWithoutAisStillCountsAsOneWorkWithNotFoundQuartile() {
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2022)).thenReturn(false);
        when(lookupPort.isForumInSsci("forum-1", 2022)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2022)).thenReturn(true);

        Score score = service.getScore(publication("ar", "2022-01-01"), indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
        assertEquals("AHCI", score.getScoringSource());
    }

    @Test
    void rankingYearIsCappedAtJcr2024ForNewerPapersWhileMembershipUsesThePubYear() {
        // PD 2026: 2025-2026 papers classify per JCR-2024; membership stays year-true at the pub year.
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2026)).thenReturn(true);
        when(lookupPort.isForumInSsci("forum-1", 2026)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2026)).thenReturn(false);
        when(lookupPort.getForumRankings(eq(forum), eq(List.of(2024)), any()))
                .thenReturn(List.of(rankingWithAis("COMPUTER SCIENCE, INFORMATION SYSTEMS - SCIE", 2024, WoSRanking.Quarter.Q1)));

        Score score = service.getScore(publication("ar", "2026-03-01"), indicator());

        assertEquals("Q1", score.getQuarter());
        verify(lookupPort).getForumRankings(eq(forum), eq(List.of(2024)), any());
    }

    @Test
    void articleInConferenceProceedingForumIsAVenueTypeMismatch() {
        // OpenAlex labels conference papers "article" — the Conference-Proceeding forum is authoritative,
        // and CPCI conferences do not qualify under the PD standard ("reviste indexate").
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-conf");
        forum.setAggregationType("Conference Proceeding");
        when(lookupPort.getForum("forum-conf")).thenReturn(forum);

        ScoringPublication pub = new ScoringPublication(
                "pub-1", null, "forum-conf", "2023-01-01", "ar", "ar",
                List.of("a1"), 1, "10.1109/x", null, "Conf Paper", 0, java.util.Set.of());

        Score score = service.getScore(pub, indicator());

        assertEquals(0.0, score.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void bookChapterIsAVenueTypeMismatch() {
        when(lookupPort.getForum("forum-1")).thenReturn(journalForum());

        Score score = service.getScore(publication("ch", "2023-01-01"), indicator());

        assertEquals(0.0, score.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void journalPublishedProceedingsPaperIsACandidateForTheMentorStandard() {
        // WoS document type "proceedings paper" in a journal (special issue): a PD candidate — the
        // mentor standard accepts it; director formulas exclude it via the docType variable.
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2020)).thenReturn(true);
        when(lookupPort.isForumInSsci("forum-1", 2020)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2020)).thenReturn(false);

        Score score = service.getScore(publication("cp", "2020-06-01"), indicator());

        assertEquals(1.0, score.getScore());
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void esciCategoryPlacementIsIgnoredEvenWhenTheReadDeliversIt() {
        // Post-2023 unified-ranking reads can deliver "CATEGORY - ESCI" keys (accepted by the CNATDCU
        // scorer). The PD extractor must ignore them — quartiles come from SCIE/SSCI placements only.
        ScholardexForumView forum = journalForum();
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.isForumInScie("forum-1", 2024)).thenReturn(true);
        when(lookupPort.isForumInSsci("forum-1", 2024)).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-1", 2024)).thenReturn(false);
        when(lookupPort.getForumRankings(eq(forum), eq(List.of(2024)), any()))
                .thenReturn(List.of(rankingWithAis("MEDICAL INFORMATICS - ESCI", 2024, WoSRanking.Quarter.Q1)));

        Score score = service.getScore(publication("ar", "2024-01-01"), indicator());

        // Member (1 work) but no SCIE/SSCI AIS placement -> quartile NOT_FOUND, not the ESCI Q1.
        assertEquals(1.0, score.getScore());
        assertEquals(WoSRanking.Quarter.NOT_FOUND.toString(), score.getQuarter());
    }

    private Indicator indicator() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");
        return indicator;
    }

    private ScoringPublication publication(String subtype, String coverDate) {
        return new ScoringPublication(
                "pub-1", null, "forum-1", coverDate, subtype, subtype,
                List.of("a1"), 1, "10.1000/x", null, "Some Paper", 0, java.util.Set.of());
    }

    private ScholardexForumView journalForum() {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setAggregationType("Journal");
        forum.setEIssn("1234-5678");
        return forum;
    }

    private WoSRanking rankingWithAis(String categoryKey, int year, WoSRanking.Quarter quarter) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-1");
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.getQAis().put(year, quarter);
        ranking.setWebOfScienceCategoryIndex(Map.of(categoryKey, rank));
        return ranking;
    }
}
