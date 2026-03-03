package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CNFISScoringService2025Test {

    @Mock
    private CacheService cacheService;

    private CNFISScoringService2025 service;
    private Domain allDomain;

    @BeforeEach
    void setUp() {
        service = new CNFISScoringService2025(cacheService);
        allDomain = new Domain();
        allDomain.setName("ALL");
        when(cacheService.getUniversityAuthorIds()).thenReturn(Set.of("u1"));
    }

    @Test
    void fallsBackToSubtypeWhenScopusSubtypeMissingForCp() {
        Publication publication = basePublication();
        publication.setScopusSubtype(null);
        publication.setSubtype("cp");

        Forum forum = baseForum("IEEE International Conference on Something");
        when(cacheService.getCachedForums(publication.getForum())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIeeeProceedings(), "cp fallback should activate IEEE proceedings branch");
        assertFalse(report.isIsiProceedings());
        assertEquals(2, report.getNumarAutori());
        assertEquals(1, report.getNumarAutoriUniversitate());
    }

    @Test
    void prefersScopusSubtypeOverSubtypeWhenBothPresent() {
        Publication publication = basePublication();
        publication.setScopusSubtype(" ar ");
        publication.setSubtype("cp");

        Forum forum = baseForum("IEEE International Conference on Something");
        when(cacheService.getCachedForums(publication.getForum())).thenReturn(forum);
        when(cacheService.getCachedRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(cacheService.getCachedRankingsByIssn("8765-4321")).thenReturn(List.of());

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIeeeProceedings(), "scopusSubtype=ar should bypass cp-specific IEEE branch");
        assertFalse(report.isIsiProceedings());
    }

    @Test
    void handlesCategoryWithoutDelimiterUsingResilientParsing() {
        Publication publication = basePublication();
        publication.setScopusSubtype("ar");

        Forum forum = baseForum("Journal of Testing");
        when(cacheService.getCachedForums(publication.getForum())).thenReturn(forum);
        when(cacheService.getCachedRankingsByIssn("1234-5678")).thenReturn(List.of(ranking("SCIE", WoSRanking.Quarter.Q1)));

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertTrue(report.isIsiQ1(), "category without delimiter should still be processed");
    }

    @Test
    void cpNonIeeeWithWosIdMarksIsiProceedings() {
        Publication publication = basePublication();
        publication.setScopusSubtype("cp");
        publication.setWosId("WOS:123");

        Forum forum = baseForum("International Computing Conference");
        when(cacheService.getCachedForums(publication.getForum())).thenReturn(forum);

        CNFISReport2025 report = service.getReport(publication, allDomain);

        assertFalse(report.isIeeeProceedings());
        assertTrue(report.isIsiProceedings());
    }

    private Publication basePublication() {
        Publication publication = new Publication();
        publication.setForum("forum-1");
        publication.setTitle("Test publication");
        publication.setDoi("10.1000/test");
        publication.setCoverDate("2023-01-15");
        publication.setAuthors(List.of("u1", "u2"));
        return publication;
    }

    private Forum baseForum(String publicationName) {
        Forum forum = new Forum();
        forum.setPublicationName(publicationName);
        forum.setIssn("1234-5678");
        forum.setEIssn("8765-4321");
        return forum;
    }

    private WoSRanking ranking(String category, WoSRanking.Quarter quarter) {
        WoSRanking ranking = new WoSRanking();
        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(2023, quarter));
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
