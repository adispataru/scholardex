package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CNFISScoringService2025Test {

    @Mock
    private ReportingLookupPort cacheService;

    private CNFISScoringService2025 service;
    private Domain allDomain;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(cacheService.maxAvailableYear()).thenReturn(2023);
        ro.uvt.pokedex.core.testsupport.ReportingLookupTestSupport.delegateForumLookupToIssn(cacheService);
        service = new CNFISScoringService2025(cacheService);
        allDomain = new Domain();
        allDomain.setName("ALL");
        when(cacheService.getUniversityAuthorIds()).thenReturn(Set.of("u1"));
    }

    @Test
    void fallsBackToSubtypeWhenScopusSubtypeMissingForCp() {
        ScoringPublication publication = publication(null, "cp", "2023-01-15", null);

        ScholardexForumView forum = baseForum("IEEE International Conference on Something");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIeeeProceedings(), "cp fallback should activate IEEE proceedings branch");
        assertFalse(report.isIsiProceedings());
        assertEquals(2, report.getNumarAutori());
        assertEquals(1, report.getNumarAutoriUniversitate());
    }

    @Test
    void prefersScopusSubtypeOverSubtypeWhenBothPresent() {
        ScoringPublication publication = publication(" ar ", "cp", "2023-01-15", null);

        ScholardexForumView forum = baseForum("IEEE International Conference on Something");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(cacheService.getRankingsByIssn("8765-4321")).thenReturn(List.of());

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIeeeProceedings(), "scopusSubtype=ar should bypass cp-specific IEEE branch");
        assertFalse(report.isIsiProceedings());
    }

    @Test
    void handlesCategoryWithoutDelimiterUsingResilientParsing() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("SCIE", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIsiQ1(), "category without delimiter should still be processed");
    }

    @Test
    void cpNonIeeeWithWosIdMarksIsiProceedings() {
        ScoringPublication publication = publication("cp", null, "2023-01-15", "WOS:123");

        ScholardexForumView forum = baseForum("International Computing Conference");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIeeeProceedings());
        assertTrue(report.isIsiProceedings());
    }

    @Test
    void cpNonIeeeWithoutWosIdKeepsProceedingsFlagsFalse() {
        ScoringPublication publication = publication("cp", null, "2023-01-15", "");

        ScholardexForumView forum = baseForum("International Computing Conference");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIeeeProceedings());
        assertFalse(report.isIsiProceedings());
    }

    @Test
    void chLectureNotesWithWosIdMarksIsiProceedings() {
        ScoringPublication publication = publication("ch", null, "2023-01-15", "WOS:456");

        ScholardexForumView forum = baseForum("Lecture Notes in Something");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIsiProceedings());
        assertFalse(report.isIeeeProceedings());
    }

    @Test
    void chLectureNotesWithoutWosIdDoesNotMarkIsiProceedings() {
        ScoringPublication publication = publication("ch", null, "2023-01-15", "");

        ScholardexForumView forum = baseForum("Lecture Notes in Something");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIsiProceedings());
    }

    @Test
    void malformedCoverDateFallsBackSafelyToLastYear() {
        ScoringPublication publication = publication("ar", null, "bad-date", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("SCIE", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = assertDoesNotThrow(() -> service.getReport(publication, allDomain));

        assertTrue(report.isIsiQ1());
    }

    @Test
    void missingSubtypeDataDoesNotCrashAndSetsNoProceedingsFlags() {
        ScoringPublication publication = publication(" ", null, "2023-01-15", null);

        ScholardexForumView forum = baseForum("Any forum");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);

        CNFISReport2025 report = assertDoesNotThrow(() -> service.getReport(publication, allDomain));

        assertFalse(report.isIeeeProceedings());
        assertFalse(report.isIsiProceedings());
    }

    @Test
    void nullCategoryInRankingIsHandledSafely() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(rankingWithNullableCategory(null, WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = assertDoesNotThrow(() -> service.getReport(publication, allDomain));

        assertTrue(report.isErihPlus());
    }

    @Test
    void blankCategoryInRankingIsHandledSafely() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(rankingWithNullableCategory(" ", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = assertDoesNotThrow(() -> service.getReport(publication, allDomain));

        assertTrue(report.isErihPlus());
    }

    @Test
    void specificDomainSkipsNonMemberCategory() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        Domain specificDomain = new Domain();
        specificDomain.setName("SPECIFIC");
        specificDomain.setWosCategories(List.of("MATHEMATICS-SCIE"));

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("COMPUTER SCIENCE-SCIE", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = service.getReport(publication, specificDomain);

        assertFalse(report.isIsiQ1());
        assertFalse(report.isIsiQ2());
        assertFalse(report.isIsiQ3());
        assertFalse(report.isIsiQ4());
    }

    @Test
    void missingForumReturnsSafeReportWithoutThrowing() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        when(cacheService.getForum(publication.getForumId())).thenReturn(null);

        CNFISReport2025 report = assertDoesNotThrow(() -> service.getReport(publication, allDomain));

        assertEquals(publication.getTitle(), report.getTitlu());
        assertEquals(publication.getDoi(), report.getDoi());
        assertEquals(2, report.getNumarAutori());
        assertEquals(1, report.getNumarAutoriUniversitate());
    }

    @Test
    void repeatedEvaluationReturnsStableReport() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("SCIE", WoSRanking.Quarter.Q1)));

        CNFISReport2025 first = service.getReport(publication, allDomain);
        CNFISReport2025 second = service.getReport(publication, allDomain);

        assertEquals(first.isIsiQ1(), second.isIsiQ1());
        assertEquals(first.isIsiQ2(), second.isIsiQ2());
        assertEquals(first.isIsiQ3(), second.isIsiQ3());
        assertEquals(first.isIsiQ4(), second.isIsiQ4());
        assertEquals(first.isIeeeProceedings(), second.isIeeeProceedings());
        assertEquals(first.isIsiProceedings(), second.isIsiProceedings());
    }

    @Test
    void categoryEvaluationDoesNotConsumePublicationYearAcrossCategories() {
        ScoringPublication publication = publication("ar", null, "2022-01-15", null);

        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(
                rankingWithCategoryYears("FIRST-SCIE", Map.of(2023, WoSRanking.Quarter.Q1)),
                rankingWithCategoryYears("SECOND-SCIE", Map.of(2022, WoSRanking.Quarter.Q2))
        ));

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIsiQ2(), "second category should still evaluate against 2022 publication year");
        assertFalse(report.isIsiQ1(), "category should not silently drift to LAST_YEAR after first category");
    }

    @Test
    void setsQ3ForScieCategory() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("X-SCIE", WoSRanking.Quarter.Q3)));

        CNFISReport2025 report = service.getReport(publication, allDomain);
        assertTrue(report.isIsiQ3());
        assertFalse(report.isIsiQ4());
    }

    @Test
    void setsQ4ForScieCategory() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("X-SCIE", WoSRanking.Quarter.Q4)));

        CNFISReport2025 report = service.getReport(publication, allDomain);
        assertTrue(report.isIsiQ4());
        assertFalse(report.isIsiQ3());
    }

    @Test
    void setsEsciFlagWhenCategoryIndexContainsEsci() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("TOPIC-ESCI", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = service.getReport(publication, allDomain);
        assertTrue(report.isIsiEmergingSourcesCitationIndex());
        assertFalse(report.isIsiArtsHumanities());
    }

    @Test
    void setsAhciFlagWhenCategoryIndexContainsAhci() {
        ScoringPublication publication = publication("ar", null, "2023-01-15", null);
        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("TOPIC-AHCI", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = service.getReport(publication, allDomain);
        assertTrue(report.isIsiArtsHumanities());
        assertFalse(report.isIsiEmergingSourcesCitationIndex());
    }

    @Test
    void yearCandidatesAdvanceWhenFirstCandidateHasNoQuarter() {
        ScoringPublication publication = publication("ar", null, "2022-01-15", null);
        ScholardexForumView forum = baseForum("Journal of Testing");
        when(cacheService.getForum(publication.getForumId())).thenReturn(forum);
        when(cacheService.getRankingsByIssn("1234-5678")).thenReturn(List.of(
                rankingWithCategoryYears("ADV-SCIE", Map.of(2022, WoSRanking.Quarter.Q2))
        ));

        CNFISReport2025 report = service.getReport(publication, allDomain);
        assertTrue(report.isIsiQ2());
    }

    private ScoringPublication publication(String scopusSubtype, String subtype, String coverDate, String wosId) {
        return new ScoringPublication(
                "pub-1",
                "eid-1",
                "forum-1",
                coverDate,
                subtype,
                scopusSubtype,
                List.of("u1", "u2"),
                2,
                "10.1000/test",
                wosId,
                "Test publication",
                0,
                java.util.Set.of()
        );
    }

    private ScholardexForumView baseForum(String publicationName) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName(publicationName);
        forum.setIssn("1234-5678");
        forum.setEIssn("8765-4321");
        return forum;
    }

    private WoSRanking ranking(String category, WoSRanking.Quarter quarter) {
        return rankingWithNullableCategory(category, quarter);
    }

    private WoSRanking rankingWithNullableCategory(String category, WoSRanking.Quarter quarter) {
        return rankingWithCategoryYears(category, Map.of(2023, quarter));
    }

    private WoSRanking rankingWithCategoryYears(String category, Map<Integer, WoSRanking.Quarter> years) {
        WoSRanking ranking = new WoSRanking();
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(years);
        Map<String, WoSRanking.Rank> categories = new HashMap<>();
        categories.put(category, rank);
        ranking.setWebOfScienceCategoryIndex(categories);
        return ranking;
    }
}
