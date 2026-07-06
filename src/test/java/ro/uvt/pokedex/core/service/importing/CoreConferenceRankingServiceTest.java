package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoreConferenceRankingServiceTest {

    @Mock CoreConferenceRankingRepository repository;
    @Mock CacheService cacheService;

    @TempDir Path tempDir;

    private CoreConferenceRankingService service;
    private List<CoreConferenceRanking> cache;

    @BeforeEach
    void setUp() {
        service = new CoreConferenceRankingService(repository, cacheService, org.mockito.Mockito.mock(ro.uvt.pokedex.core.service.application.ReportingDataEpochService.class));
        cache = new ArrayList<>();
        lenient().when(cacheService.getCachedConfRankings(any())).thenReturn(cache);
    }

    // --- directory handling ---

    @Test
    void nonExistentDirectory_returnsWithoutCacheInteraction() {
        service.loadRankingsFromCSVSync("/no/such/path/xyz999");
        verify(cacheService, never()).syncCoreConferenceRankingCacheToDb();
    }

    @Test
    void emptyDirectory_noFilesProcessed() {
        service.loadRankingsFromCSVSync(tempDir.toString());
        verify(cacheService, never()).syncCoreConferenceRankingCacheToDb();
    }

    @Test
    void nonMatchingFilename_isIgnored() throws Exception {
        writeFile("ranking.csv", header5() + "\n" + row2010("1", "Conf", "ACR", "SRC", "A") + "\n");
        service.loadRankingsFromCSVSync(tempDir.toString());
        verify(cacheService, never()).syncCoreConferenceRankingCacheToDb();
    }

    // --- async delegation ---

    @Test
    void loadRankingsFromCSV_delegatesToSyncVersion() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Intl Conf", "ICSE", "SCOPUS", "A", "SE") + "\n");
        service.loadRankingsFromCSV(tempDir.toString());
        verify(cacheService).syncCoreConferenceRankingCacheToDb();
    }

    // --- year 2023 ---

    @Test
    void year2023_conferenceAddedToCacheWithCorrectFields() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("123", "Intl Conf on SE", "ICSE", "SCOPUS", "A*", "SE;AI") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        CoreConferenceRanking r = cache.get(0);
        assertEquals("ICSE", r.getAcronym());
        assertEquals("Intl Conf on SE", r.getName());
        assertEquals("SCOPUS", r.getSource());
        assertEquals(CoreConferenceRanking.Rank.A_STAR, r.getYearlyRankings().get(2023).getRank());
        verify(cacheService).syncCoreConferenceRankingCacheToDb();
    }

    @Test
    void year2023_fieldsOfResearchParsed() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "ACR", "SRC", "B", "SE;AI") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        String[] fields = cache.get(0).getYearlyRankings().get(2023).getFieldsOfResearch();
        assertNotNull(fields);
        assertEquals(2, fields.length);
    }

    // --- year 2021, 2020 ---

    @Test
    void year2021_conferenceAdded() throws Exception {
        writeFile("CORE2021-all.csv", header7() + "\n" + row2023("9", "Very Large DB", "VLDB", "DBLP", "A", "DB") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.A, cache.get(0).getYearlyRankings().get(2021).getRank());
    }

    @Test
    void year2020_conferenceAdded() throws Exception {
        writeFile("CORE2020-all.csv", header7() + "\n" + row2023("5", "European Conf OOP", "ECOOP", "SCOPUS", "B", "PL") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.B, cache.get(0).getYearlyRankings().get(2020).getRank());
    }

    // --- years 2018, 2017, 2014, 2013, 2010 ---

    @Test
    void year2018_conferenceAdded() throws Exception {
        writeFile("CORE2018-all.csv", header5() + "\n" + row2010("1", "Conf 2018", "C18", "SRC", "B") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.B, cache.get(0).getYearlyRankings().get(2018).getRank());
    }

    @Test
    void year2017_conferenceAdded() throws Exception {
        writeFile("CORE2017-all.csv", header5() + "\n" + row2010("1", "Conf 2017", "C17", "SRC", "A") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.A, cache.get(0).getYearlyRankings().get(2017).getRank());
    }

    @Test
    void year2014_conferenceAdded() throws Exception {
        writeFile("CORE2014-all.csv", header5() + "\n" + row2010("1", "Conf 2014", "C14", "SRC", "C") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.C, cache.get(0).getYearlyRankings().get(2014).getRank());
    }

    @Test
    void year2013_conferenceAdded() throws Exception {
        writeFile("CORE2013-all.csv", header5() + "\n" + row2010("1", "Conf 2013", "C13", "SRC", "A") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
    }

    @Test
    void year2010_conferenceAdded() throws Exception {
        writeFile("CORE2010-all.csv", header5() + "\n" + row2010("2", "Conf 2010", "C10", "ACM", "B") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals(CoreConferenceRanking.Rank.B, cache.get(0).getYearlyRankings().get(2010).getRank());
    }

    // --- year 2008 (different column layout, empty sourceId) ---

    @Test
    void year2008_conferenceAddedWithEmptySourceId() throws Exception {
        writeFile("CORE2008-all.csv", header5() + "\n" + row2008("Conf 2008", "C08", "SRC", "A") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals("", cache.get(0).getSourceId());
        assertEquals(CoreConferenceRanking.Rank.A, cache.get(0).getYearlyRankings().get(2008).getRank());
    }

    // --- unsupported year ---

    @Test
    void unsupportedYear_doesNotCrashAndNothingAddedToCache() throws Exception {
        writeFile("CORE2099-all.csv", header5() + "\n" + row2010("x", "Unknown Conf", "UNK", "SRC", "A") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertTrue(cache.isEmpty());
        verify(cacheService).syncCoreConferenceRankingCacheToDb();
    }

    // --- error handling ---

    @Test
    void unreadableFile_ioExceptionCaughtAndSyncNotCalled() throws Exception {
        Path file = tempDir.resolve("CORE2023-all.csv");
        Files.writeString(file, header7() + "\n" + row2023("1", "Conf", "ACR", "SRC", "A", "X") + "\n");
        assumeTrue(file.toFile().setReadable(false), "Cannot revoke read permission (running as root?); skipping");

        service.loadRankingsFromCSVSync(tempDir.toString());

        verify(cacheService, never()).syncCoreConferenceRankingCacheToDb();
        assertTrue(cache.isEmpty());
    }

    @Test
    void malformedRow_isSkippedAndValidRowStillProcessed() throws Exception {
        writeFile("CORE2023-all.csv",
                header7() + "\n" +
                "too,few\n" +
                row2023("1", "Intl Conf", "ICSE", "SCOPUS", "A", "SE") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        verify(cacheService).syncCoreConferenceRankingCacheToDb();
    }

    // --- rank parsing ---

    @Test
    void parseRank_starSuffix_mapsToStarVariant() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "ACR", "SRC", "A*", "X") + "\n");
        service.loadRankingsFromCSVSync(tempDir.toString());
        CoreConferenceRanking.YearlyRanking yr = cache.get(0).getYearlyRankings().get(2023);
        assertEquals(CoreConferenceRanking.Rank.A_STAR, yr.getRank());
        assertEquals("A*", yr.getRankString());
    }

    @Test
    void parseRank_nationalKeyword_mapsToNationalTier() throws Exception {
        // H80/A81: national/regional is preserved as a distinct rank (the scorer maps it to C for 2026 / D for 2016);
        // it is no longer collapsed to D at load.
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "NX", "SRC", "National Importance", "X") + "\n");
        service.loadRankingsFromCSVSync(tempDir.toString());
        assertEquals(CoreConferenceRanking.Rank.National, cache.get(0).getYearlyRankings().get(2023).getRank());
    }

    @Test
    void parseRank_regionalKeyword_mapsToNationalRegionalTier() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "RX", "SRC", "Regional", "X") + "\n");
        service.loadRankingsFromCSVSync(tempDir.toString());
        assertEquals(CoreConferenceRanking.Rank.National_Regional, cache.get(0).getYearlyRankings().get(2023).getRank());
    }

    @Test
    void parseRank_unknownValue_mapsToNonRank() throws Exception {
        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "XX", "SRC", "GARBAGE_RANK", "X") + "\n");
        service.loadRankingsFromCSVSync(tempDir.toString());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK, cache.get(0).getYearlyRankings().get(2023).getRank());
    }

    @Test
    void parseRank_knownValues_mappedDirectly() throws Exception {
        for (String rank : new String[]{"A", "B", "C", "D"}) {
            cache.clear();
            writeFile("CORE2023-all.csv", header7() + "\n" + row2023("1", "Conf", "ACR" + rank, "SRC", rank, "X") + "\n");
            service.loadRankingsFromCSVSync(tempDir.toString());
            assertEquals(CoreConferenceRanking.Rank.valueOf(rank), cache.get(0).getYearlyRankings().get(2023).getRank());
        }
    }

    // --- cache update: existing conference ---

    @Test
    void existingConference_updatedAtPositionRatherThanAdded() throws Exception {
        CoreConferenceRanking existing = new CoreConferenceRanking();
        existing.setId(CoreConferenceRanking.getGeneratedId("ICSE", "Intl Conf on SE"));
        existing.setAcronym("ICSE");
        existing.setName("Intl Conf on SE");
        cache.add(existing);

        writeFile("CORE2023-all.csv", header7() + "\n" + row2023("123", "Intl Conf on SE", "ICSE", "SCOPUS", "A", "SE") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertNotNull(cache.get(0).getYearlyRankings());
        assertEquals(CoreConferenceRanking.Rank.A, cache.get(0).getYearlyRankings().get(2023).getRank());
    }

    // --- multiple rows in one file ---

    @Test
    void multipleRows_allAddedToCache() throws Exception {
        writeFile("CORE2023-all.csv",
                header7() + "\n" +
                row2023("1", "Conf A", "CA", "SRC", "A", "X") + "\n" +
                row2023("2", "Conf B", "CB", "SRC", "B", "X") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(2, cache.size());
    }

    // --- helpers ---

    // --- ICORE2026 + headerless exports (the portal ships CSVs without a header row) ---

    @Test
    void year2026_conferenceAddedFromIcoreExport() throws Exception {
        writeFile("CORE2026-all.csv", row2023("2264", "AAAI Conference on Human Computation", "HCOMP", "ICORE2026", "B", "4608") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(1, cache.size());
        assertEquals("ICORE2026", cache.get(0).getSource());
        assertEquals(CoreConferenceRanking.Rank.B, cache.get(0).getYearlyRankings().get(2026).getRank());
    }

    @Test
    void headerlessFileDoesNotDropTheFirstConference() throws Exception {
        // the real exports have NO header — row 0 is data and must be imported
        writeFile("CORE2023-all.csv",
                row2023("1270", "Web Information Systems", "WEBIST", "CORE2023", "C", "46") + "\n" +
                row2023("2199", "Computational Creativity", "ICCC", "CORE2023", "C", "46") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(2, cache.size());
        assertTrue(cache.stream().anyMatch(r -> "WEBIST".equals(r.getAcronym())));
    }

    @Test
    void australasianAndNationalRankStringsMapToTheirEnumTiers() throws Exception {
        writeFile("CORE2026-all.csv",
                row2023("1", "Aussie Conf", "AUC", "ICORE2026", "Australasian C", "46") + "\n" +
                row2023("2", "Romanian Conf", "ROC", "ICORE2026", "National: Romania", "46") + "\n" +
                row2023("3", "Merged Conf", "MGC", "ICORE2026", "unranked: merged", "46") + "\n");

        service.loadRankingsFromCSVSync(tempDir.toString());

        assertEquals(3, cache.size());
        assertEquals(CoreConferenceRanking.Rank.AustralasianC, findByAcronym("AUC").getYearlyRankings().get(2026).getRank());
        assertEquals(CoreConferenceRanking.Rank.National, findByAcronym("ROC").getYearlyRankings().get(2026).getRank());
        assertEquals(CoreConferenceRanking.Rank.NON_RANK, findByAcronym("MGC").getYearlyRankings().get(2026).getRank());
    }

    private CoreConferenceRanking findByAcronym(String acronym) {
        return cache.stream().filter(r -> acronym.equals(r.getAcronym())).findFirst().orElseThrow();
    }

    private void writeFile(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }

    private static String header5() {
        return "ID,Name,Acronym,Source,Rank";
    }

    private static String header7() {
        return "ID,Name,Acronym,Source,Rank,Extra,FieldsOfResearch";
    }

    // columns: row[0]=sourceId, row[1]=name, row[2]=acronym, row[3]=source, row[4]=rank
    private static String row2010(String sourceId, String name, String acronym, String source, String rank) {
        return sourceId + "," + name + "," + acronym + "," + source + "," + rank;
    }

    // columns: row[0]=unused, row[1]=name, row[2]=acronym, row[3]=source, row[4]=rank (2008 layout)
    private static String row2008(String name, String acronym, String source, String rank) {
        return "unused," + name + "," + acronym + "," + source + "," + rank;
    }

    // columns: row[0]=sourceId, row[1]=name, row[2]=acronym, row[3]=source, row[4]=rank, row[5]=extra, row[6]=fieldsOfResearch
    private static String row2023(String sourceId, String name, String acronym, String source, String rank, String fieldsOfResearch) {
        return sourceId + "," + name + "," + acronym + "," + source + "," + rank + ",extra," + fieldsOfResearch;
    }
}
