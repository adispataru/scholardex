package ro.uvt.pokedex.core.service.importing;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SenseRankingServiceTest {

    @Mock SenseRankingRepository bookRankingRepository;
    @Mock CoreConferenceRankingRepository coreConferenceRankingRepository;
    @TempDir Path tempDir;

    private SenseRankingService service() {
        return new SenseRankingService(coreConferenceRankingRepository, bookRankingRepository);
    }

    @Test
    void importBookRankingsFromExcelSync_ioError_noSave() {
        service().importBookRankingsFromExcelSync("/no/such/file.xlsx");
        verify(bookRankingRepository, never()).save(any());
    }

    @Test
    void importBookRankingsFromExcelSync_validRanking_savedCorrectly() throws Exception {
        Path file = createExcelFile("Book Title", "A");

        service().importBookRankingsFromExcelSync(file.toString());

        ArgumentCaptor<SenseBookRanking> captor = ArgumentCaptor.forClass(SenseBookRanking.class);
        verify(bookRankingRepository).save(captor.capture());
        SenseBookRanking saved = captor.getValue();
        assertEquals("Book Title", saved.getName());
        assertEquals(SenseBookRanking.Rank.A, saved.getRanking());
    }

    @Test
    void importBookRankingsFromExcelSync_invalidRanking_savedWithoutRanking() throws Exception {
        Path file = createExcelFile("Unknown Book", "INVALID_RANK");

        service().importBookRankingsFromExcelSync(file.toString());

        ArgumentCaptor<SenseBookRanking> captor = ArgumentCaptor.forClass(SenseBookRanking.class);
        verify(bookRankingRepository).save(captor.capture());
        assertNull(captor.getValue().getRanking());
    }

    @Test
    void importBookRankingsFromExcelSync_nullRow_isSkipped() throws Exception {
        Path file = createExcelFileWithNullRow();

        service().importBookRankingsFromExcelSync(file.toString());

        verify(bookRankingRepository, never()).save(any());
    }

    private Path createExcelFile(String name, String rank) throws Exception {
        Path file = tempDir.resolve("sense.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(name);
            data.createCell(1).setCellValue(rank);
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }
        return file;
    }

    private Path createExcelFileWithNullRow() throws Exception {
        Path file = tempDir.resolve("sense_null.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header only — row 1 is physically absent
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }
        return file;
    }
}
