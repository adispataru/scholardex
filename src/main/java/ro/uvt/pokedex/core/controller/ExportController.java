package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExportController {

    private final ForumExportFacade forumExportFacade;
    private final MeterRegistry meterRegistry;

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportToExcel() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Forums");

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Publication Name");
            headerRow.createCell(1).setCellValue("ISSN");
            headerRow.createCell(2).setCellValue("eISSN");
            headerRow.createCell(3).setCellValue("sourceId");
            headerRow.createCell(4).setCellValue("Aggregation Type");

            ForumExportViewModel viewModel = forumExportFacade.buildBookAndBookSeriesExport();
            int rowNum = 1;
            for (var exportRow : viewModel.rows()) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(exportRow.publicationName());
                row.createCell(1).setCellValue(exportRow.issn());
                row.createCell(2).setCellValue(exportRow.eIssn());
                row.createCell(3).setCellValue(exportRow.sourceId());
                row.createCell(4).setCellValue(exportRow.aggregationType());
            }
            workbook.write(outputStream);
            meterRegistry.counter("core.export.forum.requests", "outcome", "success").increment();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=forums.xlsx")
                    .body(outputStream.toByteArray());
        } catch (IOException ex) {
            meterRegistry.counter("core.export.forum.requests", "outcome", "failure").increment();
            throw new UncheckedIOException("Forum export generation failed.", ex);
        } catch (RuntimeException ex) {
            meterRegistry.counter("core.export.forum.requests", "outcome", "failure").increment();
            throw ex;
        }
    }
}
