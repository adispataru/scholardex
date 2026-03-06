package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;

class RankingServiceTest {

    @Test
    void loadRankingsFromExcelHandlesEmptyFolderDeterministically() throws Exception {
        RankingService service = new RankingService();
        RankingRepository rankingRepository = mock(RankingRepository.class);
        WosImportEventIngestionService ingestionService = mock(WosImportEventIngestionService.class);
        WosFactBuilderService factBuilderService = mock(WosFactBuilderService.class);
        when(ingestionService.ingestDirectory(anyString(), isNull())).thenReturn(new ImportProcessingResult(5));
        when(factBuilderService.buildFactsFromImportEvents()).thenReturn(new ImportProcessingResult(5));
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
        ReflectionTestUtils.setField(service, "wosImportEventIngestionService", ingestionService);
        ReflectionTestUtils.setField(service, "wosFactBuilderService", factBuilderService);

        Path dir = Files.createTempDirectory("ranking-empty");
        service.loadRankingsFromExcel(dir.toString(), "pwd");

        verify(ingestionService).ingestDirectory(anyString(), isNull());
        verify(factBuilderService).buildFactsFromImportEvents();
    }

    @Test
    void loadRankingsFromExcelIgnoresJifFiles() throws Exception {
        RankingService service = new RankingService();
        RankingRepository rankingRepository = mock(RankingRepository.class);
        WosImportEventIngestionService ingestionService = mock(WosImportEventIngestionService.class);
        WosFactBuilderService factBuilderService = mock(WosFactBuilderService.class);
        when(ingestionService.ingestDirectory(anyString(), isNull())).thenReturn(new ImportProcessingResult(5));
        when(factBuilderService.buildFactsFromImportEvents()).thenReturn(new ImportProcessingResult(5));
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
        ReflectionTestUtils.setField(service, "wosImportEventIngestionService", ingestionService);
        ReflectionTestUtils.setField(service, "wosFactBuilderService", factBuilderService);

        Path dir = Files.createTempDirectory("ranking-jif-only");
        Path jifFile = dir.resolve("JIF_2023.xlsx");
        writeMinimalWorkbook(jifFile);

        service.loadRankingsFromExcel(dir.toString(), "pwd");

        verify(ingestionService).ingestDirectory(anyString(), isNull());
        verify(factBuilderService).buildFactsFromImportEvents();
    }

    private void writeMinimalWorkbook(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("title");
            workbook.write(out);
        }
    }
}
