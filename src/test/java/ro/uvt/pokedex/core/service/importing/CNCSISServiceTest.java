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
import ro.uvt.pokedex.core.model.CNCSISPublisher;
import ro.uvt.pokedex.core.repository.reporting.CNCSISPublisherRepository;

import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CNCSISServiceTest {

    @Mock CNCSISPublisherRepository repository;
    @TempDir Path tempDir;

    private CNCSISService service() {
        return new CNCSISService(repository);
    }

    @Test
    void importPublisherListFromExcelSync_repoNotEmpty_skipsImport() {
        when(repository.count()).thenReturn(3L);
        service().importPublisherListFromExcelSync("/irrelevant");
        verify(repository, never()).save(any());
    }

    @Test
    void importPublisherListFromExcelSync_repoEmpty_ioError_noSave() {
        when(repository.count()).thenReturn(0L);
        service().importPublisherListFromExcelSync("/no/such/file.xlsx");
        verify(repository, never()).save(any());
    }

    @Test
    void importPublisherListFromExcelSync_validFile_savesPublisher() throws Exception {
        when(repository.count()).thenReturn(0L);
        Path file = createExcelFile();

        service().importPublisherListFromExcelSync(file.toString());

        ArgumentCaptor<CNCSISPublisher> captor = ArgumentCaptor.forClass(CNCSISPublisher.class);
        verify(repository).save(captor.capture());
        CNCSISPublisher saved = captor.getValue();
        assertEquals(42L, saved.getCncsisId());
        assertEquals("Polirom", saved.getName());
        assertEquals("Iasi", saved.getCity());
        assertEquals("www.polirom.ro", saved.getWebpage());
    }

    @Test
    void importPublisherListFromExcelSync_nullRow_isSkipped() throws Exception {
        when(repository.count()).thenReturn(0L);
        Path file = createExcelFileWithNullRow();

        service().importPublisherListFromExcelSync(file.toString());

        verify(repository, never()).save(any());
    }

    private Path createExcelFile() throws Exception {
        Path file = tempDir.resolve("cncsis.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header
            Row data = sheet.createRow(1);
            data.createCell(1).setCellValue(42.0); // cncsisId
            data.createCell(2).setCellValue("Polirom"); // name
            data.createCell(3).setCellValue("Iasi"); // city
            data.createCell(4).setCellValue("www.polirom.ro"); // webpage
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }
        return file;
    }

    private Path createExcelFileWithNullRow() throws Exception {
        Path file = tempDir.resolve("cncsis_null.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0); // header only — row 1 is physically absent (null)
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }
        return file;
    }
}
