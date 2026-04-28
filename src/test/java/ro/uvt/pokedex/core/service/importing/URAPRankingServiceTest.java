package ro.uvt.pokedex.core.service.importing;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class URAPRankingServiceTest {

    @Mock URAPUniversityRankingRepository repository;

    @TempDir Path tempDir;

    private URAPRankingService service;

    @BeforeEach
    void setUp() {
        service = new URAPRankingService(repository);
        when(repository.count()).thenReturn(0L);
    }

    // --- early return ---

    @Test
    void repositoryAlreadyPopulated_skipsLoadingEntirely() {
        when(repository.count()).thenReturn(5L);
        service.loadRankingsFromFolder(tempDir.toString());
        verify(repository, never()).saveAll(anyList());
    }

    // --- file name filter ---

    @Test
    void nonMatchingFilename_isIgnored() throws Exception {
        createExcelFile("other_ranking.xlsx", "MIT", "USA", "1", 90.0, 80.0, 70.0, 60.0, 50.0, 40.0, 95.0);
        service.loadRankingsFromFolder(tempDir.toString());
        verify(repository).saveAll(argThat(list -> ((List<?>) list).isEmpty()));
    }

    @Test
    void emptyDirectory_savesEmptyList() {
        service.loadRankingsFromFolder(tempDir.toString());
        verify(repository).saveAll(anyList());
    }

    // --- valid file processing ---

    @Test
    void validFile_universityAddedWithCorrectNameAndCountry() throws Exception {
        when(repository.findByNameIgnoreCase("MIT")).thenReturn(List.of());
        createExcelFile("URAP_WR_2023.xlsx", "MIT", "USA", "1", 90.0, 80.0, 70.0, 60.0, 50.0, 40.0, 95.0);

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking saved = captureSingle();
        assertEquals("MIT", saved.getName());
        assertEquals("USA", saved.getCountry());
    }

    @Test
    void validFile_scoreYearExtractedFromFilename() throws Exception {
        when(repository.findByNameIgnoreCase(any())).thenReturn(List.of());
        createExcelFile("URAP_WR_2019.xlsx", "Oxford", "UK", "2", 85.0, 75.0, 65.0, 55.0, 45.0, 35.0, 88.0);

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking saved = captureSingle();
        assertTrue(saved.getScores().containsKey(2019));
    }

    @Test
    void validFile_scoreFieldsPopulatedCorrectly() throws Exception {
        when(repository.findByNameIgnoreCase("Cambridge")).thenReturn(List.of());
        createExcelFile("URAP_WR_2022.xlsx", "Cambridge", "UK", "3", 91.0, 82.0, 73.0, 64.0, 55.0, 46.0, 97.0);

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking.Score score = captureSingle().getScores().get(2022);
        assertNotNull(score);
        assertEquals(3, score.getRank());
        assertEquals(91.0, score.getArticle());
        assertEquals(82.0, score.getCitation());
        assertEquals(73.0, score.getTotalDocument());
        assertEquals(64.0, score.getAIT());
        assertEquals(55.0, score.getCIT());
        assertEquals(46.0, score.getCollaboration());
        assertEquals(97.0, score.getTotal());
    }

    @Test
    void validFile_multipleUniversities_allSaved() throws Exception {
        when(repository.findByNameIgnoreCase(any())).thenReturn(List.of());
        createExcelFile("URAP_WR_2023.xlsx",
                new String[]{"MIT", "Stanford"},
                new String[]{"USA", "USA"},
                new String[]{"1", "2"});

        service.loadRankingsFromFolder(tempDir.toString());

        List<URAPUniversityRanking> saved = captureAll();
        assertEquals(2, saved.size());
    }

    // --- existing university ---

    @Test
    void existingUniversity_scoreAddedWithoutCreatingNewEntry() throws Exception {
        URAPUniversityRanking existing = new URAPUniversityRanking();
        existing.setName("Harvard");
        existing.setCountry("USA");
        existing.setScores(new java.util.HashMap<>());
        when(repository.findByNameIgnoreCase("Harvard")).thenReturn(List.of(existing));
        createExcelFile("URAP_WR_2021.xlsx", "Harvard", "USA", "1", 99.0, 88.0, 77.0, 66.0, 55.0, 44.0, 99.9);

        service.loadRankingsFromFolder(tempDir.toString());

        List<URAPUniversityRanking> saved = captureAll();
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).getScores().containsKey(2021));
    }

    // --- malformed row ---

    @Test
    void malformedRank_rowSkippedAndNoScoreStored() throws Exception {
        when(repository.findByNameIgnoreCase("MIT")).thenReturn(List.of());
        createExcelFileWithBadRankRow("URAP_WR_2023.xlsx", "MIT", "USA", "NOT_A_NUMBER");

        service.loadRankingsFromFolder(tempDir.toString());

        // computeIfAbsent creates the entry before rank parsing throws, but score is never added
        List<URAPUniversityRanking> saved = captureAll();
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).getScores().isEmpty());
    }

    // --- null cells ---

    @Test
    void nullScoreCells_treatedAsZero() throws Exception {
        when(repository.findByNameIgnoreCase("Sparse")).thenReturn(List.of());
        createExcelFileWithNullScores("URAP_WR_2023.xlsx", "Sparse", "RO", "5");

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking.Score score = captureSingle().getScores().get(2023);
        assertNotNull(score);
        assertEquals(0.0, score.getArticle());
        assertEquals(0.0, score.getTotal());
    }

    // --- string-valued numeric cells (getDoubleValue fallback path) ---

    @Test
    void stringNumericCells_parsedAsDouble() throws Exception {
        when(repository.findByNameIgnoreCase("ETH")).thenReturn(List.of());
        createExcelFileWithStringScores("URAP_WR_2023.xlsx", "ETH", "CH", "10", "78.5");

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking.Score score = captureSingle().getScores().get(2023);
        assertNotNull(score);
        assertEquals(78.5, score.getArticle());
    }

    @Test
    void blankStringScoreCell_treatedAsZero() throws Exception {
        when(repository.findByNameIgnoreCase("ETH")).thenReturn(List.of());
        createExcelFileWithStringScores("URAP_WR_2023.xlsx", "ETH", "CH", "10", "");

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking.Score score = captureSingle().getScores().get(2023);
        assertNotNull(score);
        assertEquals(0.0, score.getArticle());
    }

    // --- isRegularFile filter ---

    @Test
    void directoryWithMatchingName_notProcessedAsFile() throws Exception {
        // directory named URAP_WR_2023.xlsx — isRegularFile must filter it out
        Files.createDirectory(tempDir.resolve("URAP_WR_2023.xlsx"));
        assertDoesNotThrow(() -> service.loadRankingsFromFolder(tempDir.toString()));
        verify(repository).saveAll(argThat(list -> ((List<?>) list).isEmpty()));
    }

    // --- IO error path ---

    @Test
    void unreadableFile_ioExceptionCaughtAndRethrown() throws Exception {
        Path file = tempDir.resolve("URAP_WR_2023.xlsx");
        createExcelFile("URAP_WR_2023.xlsx", "MIT", "USA", "1", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        assumeTrue(file.toFile().setReadable(false), "Cannot revoke read permission; skipping");
        assertThrows(RuntimeException.class, () -> service.loadRankingsFromFolder(tempDir.toString()));
    }

    // --- null cell in getStringValue ---

    @Test
    void missingCountryCell_savedWithEmptyCountry() throws Exception {
        when(repository.findByNameIgnoreCase("MIT")).thenReturn(List.of());
        createExcelFileWithNoCountryCell("URAP_WR_2023.xlsx", "MIT", "1");

        service.loadRankingsFromFolder(tempDir.toString());

        URAPUniversityRanking saved = captureSingle();
        assertEquals("MIT", saved.getName());
        assertEquals("", saved.getCountry());
    }

    // --- numeric country cell (tests setCellType conversion in getStringValue) ---

    @Test
    void numericCountryCell_convertedToStringByGetStringValue() throws Exception {
        when(repository.findByNameIgnoreCase("MIT")).thenReturn(List.of());
        createExcelFileWithNumericCountry("URAP_WR_2023.xlsx", "MIT", 1);

        service.loadRankingsFromFolder(tempDir.toString());

        // setCellType converts numeric cell to its string representation; without it getStringCellValue throws
        URAPUniversityRanking saved = captureSingle();
        assertNotNull(saved.getScores().get(2023));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private URAPUniversityRanking captureSingle() {
        ArgumentCaptor<List<URAPUniversityRanking>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().get(0);
    }

    @SuppressWarnings("unchecked")
    private List<URAPUniversityRanking> captureAll() {
        ArgumentCaptor<List<URAPUniversityRanking>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void createExcelFile(String fileName, String name, String country, String rank,
                                  double article, double citation, double totalDoc,
                                  double ait, double cit, double collab, double total) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(rank);
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue(country);
            data.createCell(3).setCellValue(article);
            data.createCell(4).setCellValue(citation);
            data.createCell(5).setCellValue(totalDoc);
            data.createCell(6).setCellValue(ait);
            data.createCell(7).setCellValue(cit);
            data.createCell(8).setCellValue(collab);
            data.createCell(9).setCellValue(total);
            write(wb, fileName);
        }
    }

    private void createExcelFile(String fileName, String[] names, String[] countries, String[] ranks) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            for (int i = 0; i < names.length; i++) {
                Row data = sheet.createRow(i + 1);
                data.createCell(0).setCellValue(ranks[i]);
                data.createCell(1).setCellValue(names[i]);
                data.createCell(2).setCellValue(countries[i]);
                for (int c = 3; c <= 9; c++) data.createCell(c).setCellValue(0.0);
            }
            write(wb, fileName);
        }
    }

    private void createExcelFileWithBadRankRow(String fileName, String name, String country, String badRank) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(badRank); // not a number → NumberFormatException
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue(country);
            write(wb, fileName);
        }
    }

    private void createExcelFileWithNullScores(String fileName, String name, String country, String rank) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(rank);
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue(country);
            // cells 3-9 intentionally absent (null)
            write(wb, fileName);
        }
    }

    private void createExcelFileWithStringScores(String fileName, String name, String country, String rank, String scoreValue) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(rank);
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue(country);
            // STRING cells for scores — triggers getDoubleValue fallback path
            for (int c = 3; c <= 9; c++) data.createCell(c).setCellValue(scoreValue);
            write(wb, fileName);
        }
    }

    private void createExcelFileWithNoCountryCell(String fileName, String name, String rank) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(rank);
            data.createCell(1).setCellValue(name);
            // cell(2) intentionally absent → getStringValue(null) → ""
            for (int c = 3; c <= 9; c++) data.createCell(c).setCellValue(0.0);
            write(wb, fileName);
        }
    }

    private void createExcelFileWithNumericCountry(String fileName, String name, int numericCountry) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("5"); // string rank — avoids "1.0" parse issue
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue((double) numericCountry); // NUMERIC cell — setCellType converts it
            for (int c = 3; c <= 9; c++) data.createCell(c).setCellValue(0.0);
            write(wb, fileName);
        }
    }

    private void createExcelFileWithNumericRank(String fileName, String name, String country, int rank) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(rank); // NUMERIC cell — requires setCellType conversion
            data.createCell(1).setCellValue(name);
            data.createCell(2).setCellValue(country);
            for (int c = 3; c <= 9; c++) data.createCell(c).setCellValue(0.0);
            write(wb, fileName);
        }
    }

    private void write(XSSFWorkbook wb, String fileName) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(tempDir.resolve(fileName).toFile())) {
            wb.write(fos);
        }
    }
}
