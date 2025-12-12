package ro.uvt.pokedex.core.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ExportController {

    @Autowired
    private ScopusForumRepository forumRepository;

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

                // Fetch data from repository
                List<Forum> forums = forumRepository.findAllByAggregationTypeIn(List.of(new String[]{"Book", "Book Series"}));

                // Set to keep track of unique ISSN values
                Set<String> uniqueIssns = new HashSet<>();

                // Populate data rows
                int rowNum = 1;
                for (Forum forum : forums) {
                    if (!uniqueIssns.contains(forum.getIssn())) {
                        if(!"null-".equals(forum.getIssn()))
                            uniqueIssns.add(forum.getIssn());

                        Row row = sheet.createRow(rowNum++);
                        row.createCell(0).setCellValue(forum.getPublicationName());
                        row.createCell(1).setCellValue(forum.getIssn());
                        row.createCell(2).setCellValue(forum.getEIssn());
                        row.createCell(2).setCellValue(forum.getId());
                        row.createCell(3).setCellValue(forum.getAggregationType());
                    }
                }

                workbook.write(outputStream);
            } catch (IOException e) {
                e.printStackTrace();
            }
        };
    }
}
