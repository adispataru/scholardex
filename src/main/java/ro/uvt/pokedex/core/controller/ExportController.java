package ro.uvt.pokedex.core.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExportController {

    private final ForumExportFacade forumExportFacade;

    @GetMapping("/export")
    public StreamingResponseBody exportToExcel(HttpServletResponse response) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=forums.xlsx");

        return outputStream -> {
            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Forums");

                // Create header row
                Row headerRow = sheet.createRow(0);
                headerRow.createCell(0).setCellValue("Publication Name");
                headerRow.createCell(1).setCellValue("ISSN");
                headerRow.createCell(2).setCellValue("eISSN");
                headerRow.createCell(2).setCellValue("sourceId");
                headerRow.createCell(3).setCellValue("Aggregation Type");

                ForumExportViewModel viewModel = forumExportFacade.buildBookAndBookSeriesExport();
                int rowNum = 1;
                for (var exportRow : viewModel.rows()) {
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(exportRow.publicationName());
                    row.createCell(1).setCellValue(exportRow.issn());
                    row.createCell(2).setCellValue(exportRow.eIssn());
                    row.createCell(2).setCellValue(exportRow.sourceId());
                    row.createCell(3).setCellValue(exportRow.aggregationType());
                }

                workbook.write(outputStream);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }
}
