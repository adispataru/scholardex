package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class ComputerScienceJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;


    @BeforeEach
    void stubMaxAvailableYear() {
        org.mockito.Mockito.lenient().when(lookupPort.maxAvailableYear()).thenReturn(2023);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(lookupPort);
    }
    @Test
    void standardExcludedWseasVenueScoresZero() {
        // The Informatica standard excludes WSEAS/IAENG/DAAAM venues from consideration -> no points, even though
        // the journal is Scopus-indexed (which would otherwise floor it at C).
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);

        ScoringPublication publication = publication("forum-wseas", null, "ar", "ar");
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-wseas");
        forum.setPublicationName("WSEAS Transactions on Signal Processing");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-wseas")).thenReturn(forum);

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        // The zero carries its cause so detail views can explain instead of hiding the item.
        assertEquals("EXCLUDED_VENUE", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void missingAisRankForYearDoesNotThrowAndFallsBackToLowerTier() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);

        ScoringPublication publication = publication("forum-1", null, "ar", null);

        ScholardexForumView forum = new ScholardexForumView();
        forum.setIssn("1234-5678");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, WoSRanking.Quarter.Q3));
        // No rankAis entry for LAST_YEAR on purpose.

        WoSRanking ranking = new WoSRanking();
        ranking.setId("j-1");
        ranking.setWebOfScienceCategoryIndex(Map.of("Computer Science, Theory & Methods - SCIE", rank));

        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("Computer Science, Theory & Methods - SCIE", 2023)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("Q3", score.getQuarter());
        assertEquals(2023, score.getYear());
    }

    @Test
    void journalAggregationAllowsWosScoringWhenScopusSubtypeIsConferencePaper() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = publication("forum-1", "2020-12-01", "cp", "cp");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setEIssn("2045-2322");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2020, WoSRanking.Quarter.Q2));
        rank.setRankAis(Map.of(2020, 50));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-sci-rep");
        ranking.setWebOfScienceCategoryIndex(Map.of("MULTIDISCIPLINARY SCIENCES - SCIE", rank));

        when(lookupPort.getRankingsByIssn("2045-2322")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("MULTIDISCIPLINARY SCIENCES - SCIE", 2020)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(4.0, score.getScore());
        assertEquals("B", score.getCoreRankingEquivalent());
        assertEquals("Q2", score.getQuarter());
        assertEquals("SCOPUS+WOS", score.getScoringSource());
    }

    @Test
    void itemYearScoringCarriesForwardLatestQuartileWhenPaperYearHasNoRanking() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        // Global ranking data reaches 2025, but THIS journal's latest is 2024 — the per-forum carry-forward case.
        when(lookupPort.maxAvailableYear()).thenReturn(2025);

        // A 2025 article whose journal's JCR data only goes to 2024 (rankings lag ~1–2 years).
        ScoringPublication publication = publication("forum-1", "2025-12-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setEIssn("2045-2322");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2024, WoSRanking.Quarter.Q2)); // quartile only for 2024, not the paper's 2025
        rank.setRankAis(Map.of(2024, 50));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-sci-rep");
        ranking.setWebOfScienceCategoryIndex(Map.of("MULTIDISCIPLINARY SCIENCES - SCIE", rank));

        when(lookupPort.getRankingsByIssn("2045-2322")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("MULTIDISCIPLINARY SCIENCES - SCIE", 2024)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        // Carries the 2024 Q2 forward to the 2025 paper instead of scoring 0.
        assertEquals(4.0, score.getScore());
        assertEquals("Q2", score.getQuarter());
        assertEquals(2024, score.getYear());
    }

    @Test
    void openAlexJournalPaperWithoutEidScoresCViaForumScopusMembership() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        // OpenAlex-sourced article: no Scopus eid, no WoS quartile — but the journal is in Scopus.
        ScoringPublication publication = new ScoringPublication(
                "pub-1", null, "forum-1", "2026-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, "10.2196/84296", null, "JMIR Formative Research", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setAggregationType("Journal");
        forum.setEIssn("2561-326X");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn(any())).thenReturn(List.of()); // no WoS ranking
        when(lookupPort.isForumInScopus("forum-1")).thenReturn(true);    // journal indexed in Scopus

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore()); // C-tier
        assertEquals("C", score.getCoreRankingEquivalent());
    }

    @Test
    void esciOnlyJournalNotInScopusScoresCAndIsReportedAsEsci() {
        // WoS ESCI has no JIF quartile but is a recognized WoS index — an ESCI journal not in Scopus now floors at
        // C (was 0) and the provenance tells it apart from a plain Scopus journal.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = new ScoringPublication(
                "pub-esci", null, "forum-esci", "2024-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, null, null, "An ESCI Journal", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-esci");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-esci")).thenReturn(forum);
        when(lookupPort.isForumInScopus("forum-esci")).thenReturn(false);
        when(lookupPort.isForumInEsci("forum-esci", 2024)).thenReturn(true);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("ESCI", score.getScoringSource());
        assertEquals(WoSRanking.Quarter.ESCI.toString(), score.getQuarter());
    }

    @Test
    void scopusJournalAlsoInEsciIsReportedAsScopusPlusEsci() {
        // A journal in both Scopus and ESCI still scores C, but the source flags the ESCI membership too.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = new ScoringPublication(
                "pub-both", null, "forum-both", "2024-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, null, null, "A Scopus And ESCI Journal", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-both");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-both")).thenReturn(forum);
        when(lookupPort.isForumInScopus("forum-both")).thenReturn(true);
        when(lookupPort.isForumInEsci("forum-both", 2024)).thenReturn(true);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS+ESCI", score.getScoringSource());
    }

    @Test
    void ahciOnlyJournalNotInScopusScoresCAndIsReportedAsAhci() {
        // WoS AHCI (Arts & Humanities) also carries no JIF quartile; an AHCI journal not in Scopus now floors at C.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = new ScoringPublication(
                "pub-ahci", null, "forum-ahci", "2024-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, null, null, "An AHCI Journal", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-ahci");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-ahci")).thenReturn(forum);
        when(lookupPort.isForumInScopus("forum-ahci")).thenReturn(false);
        when(lookupPort.isForumInAhci("forum-ahci", 2024)).thenReturn(true);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("AHCI", score.getScoringSource());
        assertEquals(WoSRanking.Quarter.AHCI.toString(), score.getQuarter());
    }

    @Test
    void esciQuartileScoresAsCategoryPlacementFromUnifiedRankingEra() {
        // From the 2023 metrics year, ESCI journals are ranked in the SAME per-category list as SCIE/SSCI —
        // an ESCI quartile is a real category placement, not just "indexed". Q2 at rank 39 of a 100-Q1
        // category -> B (4p), reported as a WoS quartile match instead of the ESCI floor-at-C.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = publication("forum-esci", "2023-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-esci");
        forum.setAggregationType("Journal");
        forum.setEIssn("2561-326X");
        when(lookupPort.getForum("forum-esci")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, WoSRanking.Quarter.Q2));
        rank.setRankAis(Map.of(2023, 39));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-jmir-formative");
        ranking.setWebOfScienceCategoryIndex(Map.of("MEDICAL INFORMATICS - ESCI", rank));

        when(lookupPort.getRankingsByIssn("2561-326X")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("MEDICAL INFORMATICS - ESCI", 2023)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(4.0, score.getScore());
        assertEquals("B", score.getCoreRankingEquivalent());
        assertEquals("Q2", score.getQuarter());
        assertEquals("SCOPUS+WOS", score.getScoringSource());
    }

    @Test
    void preUnifiedEsciQuartileStillFloorsAtC() {
        // Before the 2023 metrics year an ESCI quartile is computed against a tiny edition-only cohort —
        // not comparable to a category placement. The placement is ignored and the journal takes the
        // recognized-index floor at C instead.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = publication("forum-esci", "2022-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-esci");
        forum.setAggregationType("Journal");
        forum.setEIssn("2561-326X");
        when(lookupPort.getForum("forum-esci")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2022, WoSRanking.Quarter.Q2)); // Q2 of a ~10-journal ESCI cohort: ignored

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-jmir-formative");
        ranking.setWebOfScienceCategoryIndex(Map.of("MEDICAL INFORMATICS - ESCI", rank));

        when(lookupPort.getRankingsByIssn("2561-326X")).thenReturn(List.of(ranking));
        when(lookupPort.isForumInAhci("forum-esci", 2022)).thenReturn(false);
        when(lookupPort.isForumInEsci("forum-esci", 2022)).thenReturn(true);

        Score score = service.getScore(publication, indicator);

        // The paper's own Scopus eid puts SCOPUS first in the recognized-index floor; ESCI is reported too.
        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS", score.getQuarter());
        assertEquals("SCOPUS+ESCI", score.getScoringSource());
    }

    @Test
    void journalNotInScopusAndWithoutEidScoresZero() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        ScoringPublication publication = new ScoringPublication(
                "pub-1", null, "forum-1", "2026-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, "10.0/x", null, "Some Journal", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setAggregationType("Journal");
        forum.setEIssn("9999-9999");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn(any())).thenReturn(List.of());
        when(lookupPort.isForumInScopus("forum-1")).thenReturn(false);

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        // A real journal article (candidate) that resolved no rank/index is NOT a venue-type mismatch.
        assertNull(score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void bookChapterInForumMislabeledAsJournalIsNotScoredAsJournal() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        // A book chapter (OpenAlex "book-chapter") sitting in a book-series forum that OpenAlex mislabels
        // as a "Journal". Even with Scopus membership it must NOT be counted as a journal article.
        ScoringPublication publication = new ScoringPublication(
                "pub-1", null, "forum-1", "2022-01-01", "book-chapter", null,
                java.util.List.of("a1"), 1, "10.0/ch", null, "Studies in Big Data", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setAggregationType("Journal"); // unreliable: it is really a book series
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        // Rejected as a journal candidate before any ranking/Scopus lookup happens.

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        // Not a journal article → the zero is explained as a venue-type mismatch.
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void springerIsbnDoiLabeledAsArticleIsNotScoredAsJournal() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, "IY");

        // OpenAlex mislabels this LNCS proceedings paper as subtype "ar", and the forum as a Journal — but
        // the Springer ISBN DOI (10.1007/978…) is authoritative: it is not a journal article.
        ScoringPublication publication = new ScoringPublication(
                "pub-1", null, "forum-1", "2018-01-01", "ar", "ar",
                java.util.List.of("a1"), 1, "https://doi.org/10.1007/978-3-319-99999-1_7", null,
                "Lecture Notes In Artificial Intelligence", 0, java.util.Set.of());

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-1");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        assertEquals("VENUE_TYPE_MISMATCH", score.getScoringInfo().get("zeroReason"));
    }

    @Test
    void activityJournalFallsBackToScopusWhenNoWosMatch() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort) {
            @Override
            protected ScholardexForumView getForumFromActivity(ActivityInstance activity) {
                ScholardexForumView forum = super.getForumFromActivity(activity);
                forum.setAggregationType("Journal");
                return forum;
            }
        };
        Indicator indicator = indicator("IY");
        when(lookupPort.getRankingsByIssn("1111-2222")).thenReturn(List.of());

        ActivityInstance activity = new ActivityInstance();
        activity.setDate("2023-01-01");
        activity.setReferenceFields(Map.of(
                Activity.ReferenceField.FORUM_NAME, "Journal of No Matches",
                Activity.ReferenceField.FORUM_ISSN, "1111-2222"
        ));

        Score score = service.getScore(activity, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        assertEquals("SCOPUS", score.getScoringSource());
        assertNotNull(score.getScoringInfo());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void descriptionIsNonEmpty() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        assertNotNull(service.getDescription());
        assertEquals(false, service.getDescription().isBlank());
    }

    @Test
    void publicationJournalWithoutWosFallsBackToScopus() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Indicator indicator = indicator("IY");
        ScoringPublication publication = publication("forum-1", "2023-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setIssn("1000-1000");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1000-1000")).thenReturn(List.of());

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("SCOPUS", score.getScoringSource());
        assertEquals("SCOPUS", score.getScoringInfo().get("matchSource"));
    }

    @Test
    void q1AtTopThresholdUsesNonTopBranch() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Indicator indicator = indicator("IY");
        ScoringPublication publication = publication("forum-1", "2023-06-01", "ar", "ar");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setAggregationType("Journal");
        forum.setIssn("1000-1000");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, WoSRanking.Quarter.Q1));
        rank.setRankAis(Map.of(2023, 20)); // numTop when top=100
        WoSRanking ranking = new WoSRanking();
        ranking.setWebOfScienceCategoryIndex(Map.of("C1-SCIE", rank));
        when(lookupPort.getRankingsByIssn("1000-1000")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("C1-SCIE", 2023)).thenReturn(100);

        Score score = service.getScore(publication, indicator);
        assertEquals(8.0, score.getScore());
    }

    @Test
    void esciQuartilePlacementDoesNotScoreQuartilePoints() {
        // ESCI cohorts carry quartiles in the data (AIS files + JCR 2023+ JIF), but the standard treats
        // ESCI as "indexed, not core": the journal floors at C via the ESCI membership path instead.
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setJournalBestQuartile2026(true);

        ScoringPublication publication = publication("forum-esci", null, "ar", null);
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("forum-esci");
        forum.setIssn("2624-599X");
        forum.setAggregationType("Journal");
        when(lookupPort.getForum("forum-esci")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2024, WoSRanking.Quarter.Q1));
        rank.setRankAis(Map.of(2024, 1));
        rank.setQIF(Map.of(2024, WoSRanking.Quarter.Q1));
        rank.setRankIF(Map.of(2024, 1));
        WoSRanking ranking = new WoSRanking();
        ranking.setId("j-esci");
        ranking.setWebOfScienceCategoryIndex(Map.of("ACOUSTICS - ESCI", rank));
        when(lookupPort.getRankingsByIssn("2624-599X")).thenReturn(List.of(ranking));
        when(lookupPort.isForumInEsci(org.mockito.ArgumentMatchers.eq("forum-esci"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCoreRankingEquivalent());
        // the fixture publication has a Scopus eid, so SCOPUS leads the reported index list; ESCI is included
        assertEquals("SCOPUS", score.getQuarter());
        assertEquals("SCOPUS+ESCI", score.getScoringSource());
    }

    // --- 2026 rule: journal category = BEST of AIS and JIF quartile placements ---

    private Score scoreWithQuartiles(WoSRanking.Quarter qAis, WoSRanking.Quarter qIf, boolean bestOf2026) {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setJournalBestQuartile2026(bestOf2026);

        ScoringPublication publication = publication("forum-1", null, "ar", null);
        ScholardexForumView forum = new ScholardexForumView();
        forum.setIssn("1234-5678");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        if (qAis != null) {
            rank.setQAis(Map.of(2019, qAis));
            rank.setRankAis(Map.of(2019, 999)); // outside any top-20% boundary
        }
        if (qIf != null) {
            rank.setQIF(Map.of(2019, qIf));
            rank.setRankIF(Map.of(2019, 999));
        }
        WoSRanking ranking = new WoSRanking();
        ranking.setId("j-1");
        ranking.setWebOfScienceCategoryIndex(Map.of("ACOUSTICS - SCIE", rank));
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));
        org.mockito.Mockito.lenient().when(lookupPort.getTopRankings("ACOUSTICS - SCIE", 2019)).thenReturn(10);
        org.mockito.Mockito.lenient().when(lookupPort.getTopRankingsIf("ACOUSTICS - SCIE", 2019)).thenReturn(10);

        return service.getScore(publication, indicator);
    }

    @Test
    void bestQuartile2026TakesTheIfPlacementWhenBetterThanAis() {
        Score score = scoreWithQuartiles(WoSRanking.Quarter.Q3, WoSRanking.Quarter.Q1, true);
        assertEquals(8.0, score.getScore());
        assertEquals("Q1", score.getQuarter());
        assertEquals("A", score.getCoreRankingEquivalent());
    }

    @Test
    void bestQuartile2026KeepsAisOnTies() {
        Score score = scoreWithQuartiles(WoSRanking.Quarter.Q2, WoSRanking.Quarter.Q2, true);
        assertEquals(4.0, score.getScore());
        assertEquals("Q2", score.getQuarter());
    }

    @Test
    void bestQuartile2026FallsBackToIfWhenAisMissingForTheYear() {
        Score score = scoreWithQuartiles(null, WoSRanking.Quarter.Q2, true);
        assertEquals(4.0, score.getScore());
        assertEquals("Q2", score.getQuarter());
    }

    @Test
    void legacyIndicatorsClassifyByAisAloneEvenWhenIfIsBetter() {
        Score score = scoreWithQuartiles(WoSRanking.Quarter.Q3, WoSRanking.Quarter.Q1, false);
        assertEquals(2.0, score.getScore());
        assertEquals("Q3", score.getQuarter());
    }

    private ScoringPublication publication(String forumId, String coverDate, String subtype, String scopusSubtype) {
        return new ScoringPublication(
                "pub-1",
                "2-s2.0-85090497285",
                forumId,
                coverDate,
                subtype,
                scopusSubtype,
                java.util.List.of("a1"),
                1,
                "10.1000/pub-1",
                null,
                "Computer Science Journal",
                0,
                java.util.Set.of()
        );
    }

    private Indicator indicator(String yearRange) {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        ro.uvt.pokedex.core.testsupport.IndicatorTestFixtures.setScoreYearRange(indicator, yearRange);
        return indicator;
    }
}
