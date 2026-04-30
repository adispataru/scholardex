package ro.uvt.pokedex.core.service.reporting;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CNFISReportExportServiceTest {

    @Test
    void generateWorkbookFillsExpectedCellsForQ1GroupFalse() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        ScoringPublication publication = publication("p1", "forum-1", "2024-03-01", "10.1000/test", "WOS-1", 5, "A title");
        CNFISReport2025 report = report(r -> r.setIsiQ1(true));
        ScholardexForumView forum = forum("Forum Name", "1234-5678", "9876-5432");

        byte[] bytes = service.generateCNFISReportWorkbook(
                List.of(publication), List.of(report), Map.of("forum-1", forum), List.of(), false
        );

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row = sheet.getRow(17);
            assertNotNull(row);
            assertEquals("2024", row.getCell(1).getStringCellValue());
            assertEquals("A title", row.getCell(2).getStringCellValue());
            assertEquals("10.1000/test", row.getCell(3).getStringCellValue());
            assertEquals("WOS-1", row.getCell(4).getStringCellValue());
            assertEquals("Forum Name", row.getCell(6).getStringCellValue());
            assertEquals("9876-5432", row.getCell(7).getStringCellValue());
            assertEquals("1234-5678", row.getCell(8).getStringCellValue());
            assertEquals(1.0, row.getCell(12).getNumericCellValue());
            assertEquals(5.0, row.getCell(25).getNumericCellValue());
            assertEquals(2.0, row.getCell(26).getNumericCellValue());
        }
    }

    @Test
    void generateWorkbookUsesGroupSheetAndQ4ColumnAndSkipsInvalidIds() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        ScoringPublication skipped = publication("s1", "forum-1", "2024-01-01", "null", CanonicalPublicationConstants.NON_WOS_ID, 2, "Skip");
        ScoringPublication kept = publication("k1", "forum-1", "2024-01-01", "10.2000/ok", CanonicalPublicationConstants.NON_WOS_ID, 3, "Keep");
        CNFISReport2025 report = report(r -> r.setIsiQ4(true));
        ScholardexForumView forum = forum("Forum", "null-print", "null-online");

        byte[] bytes = service.generateCNFISReportWorkbook(
                List.of(skipped, kept), List.of(report, report), Map.of("forum-1", forum), List.of(), true
        );

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(1);
            Row row = null;
            for (int r = 9; r <= 20; r++) {
                Row candidate = sheet.getRow(r);
                if (candidate != null
                        && candidate.getCell(2) != null
                        && "Keep".equals(candidate.getCell(2).getStringCellValue())) {
                    row = candidate;
                    break;
                }
            }
            assertNotNull(row);
            assertEquals("Keep", row.getCell(2).getStringCellValue());
            assertEquals("", row.getCell(4).getStringCellValue());
            assertEquals("", row.getCell(7).getStringCellValue());
            assertEquals("", row.getCell(8).getStringCellValue());
            assertEquals(1.0, row.getCell(15).getNumericCellValue());
        }
    }

    @Test
    void exportMethodWritesResponseHeadersAndWorkbook() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new MemoryServletOutputStream(bos));

        service.exportCNFISReport2025(
                List.of(publication("p1", "forum-1", "2024-03-01", "10.1000/test", "WOS-1", 1, "Title")),
                List.of(report(r -> r.setErihPlus(true))),
                Map.of("forum-1", forum("Name", "1111-1111", "2222-2222")),
                List.of(),
                response,
                false
        );

        verify(response).setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        verify(response).setHeader("Content-Disposition", "attachment; filename=\"data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx\"");
        assertNotNull(new XSSFWorkbook(new ByteArrayInputStream(bos.toByteArray())));
    }

    @Test
    void populateSheetHandlesShortSampleRowThenRetriesWithNextTemplateRow() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("s");
            Row shortTemplate = sheet.createRow(0);
            shortTemplate.createCell(0).setCellValue("short");
            Row fullTemplate = sheet.createRow(1);
            for (int i = 0; i <= 26; i++) {
                fullTemplate.createCell(i).setCellValue("");
            }

            service.populateSheet(
                    workbook,
                    sheet,
                    List.of(publication("p1", "forum-1", "2024-03-01", "10.1000/test", "WOS-1", 3, "Retried")),
                    List.of(report(r -> r.setIsiProceedings(true))),
                    Map.of("forum-1", forum("Forum", "1111-1111", "2222-2222")),
                    2,
                    0
            );

            Row populated = null;
            for (int r = 2; r <= 6; r++) {
                Row candidate = sheet.getRow(r);
                if (candidate != null
                        && candidate.getCell(2) != null
                        && "Retried".equals(candidate.getCell(2).getStringCellValue())) {
                    populated = candidate;
                    break;
                }
            }
            assertNotNull(populated);
            assertEquals(1.0, populated.getCell(19).getNumericCellValue());
        }
    }

    @Test
    void populateSheetFailsFastWhenNoTemplateRowHasEnoughColumns() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("s");
            Row shortTemplate = sheet.createRow(0);
            shortTemplate.createCell(0).setCellValue("short");

            assertThrows(IllegalStateException.class, () -> service.populateSheet(
                    workbook,
                    sheet,
                    List.of(publication("p1", "forum-1", "2024-03-01", "10.1000/test", "WOS-1", 3, "Retried")),
                    List.of(report(r -> r.setIsiProceedings(true))),
                    Map.of("forum-1", forum("Forum", "1111-1111", "2222-2222")),
                    2,
                    0
            ));
        }
    }

    @Test
    void copyRowCoversCellTypesCommentHyperlinkAndMergedRegion() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("copy");
            Row source = sheet.createRow(0);

            source.createCell(0, CellType.BLANK);
            source.getCell(0).setCellValue("");
            source.createCell(1, CellType.BOOLEAN).setCellValue(true);
            source.createCell(2, CellType.ERROR).setCellErrorValue((byte) 42);
            source.createCell(3, CellType.FORMULA).setCellFormula("1+1");
            source.createCell(4, CellType.NUMERIC).setCellValue(2.5);
            source.createCell(5, CellType.STRING).setCellValue("text");

            CreationHelper helper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            var anchor = helper.createClientAnchor();
            anchor.setCol1(5);
            anchor.setCol2(6);
            anchor.setRow1(0);
            anchor.setRow2(1);
            source.getCell(5).setCellComment(drawing.createCellComment(anchor));
            source.getCell(5).setHyperlink(helper.createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL));
            source.getCell(5).getHyperlink().setAddress("https://example.test");

            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 4, 5));

            Row copied = CNFISReportExportService.copyRow(workbook, sheet, 0, 1);

            assertNotNull(copied.getCell(0));
            assertEquals(true, copied.getCell(1).getBooleanCellValue());
            assertEquals(42, copied.getCell(2).getErrorCellValue());
            assertEquals("1+1", copied.getCell(3).getCellFormula());
            assertEquals(2.5, copied.getCell(4).getNumericCellValue());
            assertEquals("text", copied.getCell(5).getStringCellValue());
            assertNotNull(copied.getCell(5).getCellComment());
            assertNotNull(copied.getCell(5).getHyperlink());
            assertTrue(sheet.getNumMergedRegions() >= 2);
        }
    }

    @Test
    void populateSheetCoversAllClassificationColumns() throws Exception {
        CNFISReportExportService service = new CNFISReportExportService();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("s");
            Row template = sheet.createRow(0);
            for (int i = 0; i <= 26; i++) {
                template.createCell(i).setCellValue("");
            }
            List<CNFISReport2025> reports = List.of(
                    report(r -> r.setIsiQ2(true)),
                    report(r -> r.setIsiQ3(true)),
                    report(r -> r.setIsiQ4(true)),
                    report(r -> r.setIsiArtsHumanities(true)),
                    report(r -> r.setIsiEmergingSourcesCitationIndex(true)),
                    report(r -> r.setErihPlus(true)),
                    report(r -> r.setIsiProceedings(true)),
                    report(r -> r.setIeeeProceedings(true))
            );
            List<ScoringPublication> pubs = List.of(
                    publication("p2", "f", "2024-01-01", "10.1/2", "W2", 1, "t2"),
                    publication("p3", "f", "2024-01-01", "10.1/3", "W3", 1, "t3"),
                    publication("p4", "f", "2024-01-01", "10.1/4", "W4", 1, "t4"),
                    publication("p5", "f", "2024-01-01", "10.1/5", "W5", 1, "t5"),
                    publication("p6", "f", "2024-01-01", "10.1/6", "W6", 1, "t6"),
                    publication("p7", "f", "2024-01-01", "10.1/7", "W7", 1, "t7"),
                    publication("p8", "f", "2024-01-01", "10.1/8", "W8", 1, "t8"),
                    publication("p9", "f", "2024-01-01", "10.1/9", "W9", 1, "t9")
            );

            service.populateSheet(workbook, sheet, pubs, reports, Map.of("f", forum("F", "1", "2")), 1, 0);

            assertEquals(1.0, sheet.getRow(1).getCell(13).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(2).getCell(14).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(3).getCell(15).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(4).getCell(16).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(5).getCell(17).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(6).getCell(18).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(7).getCell(19).getNumericCellValue());
            assertEquals(1.0, sheet.getRow(8).getCell(20).getNumericCellValue());
        }
    }

    private ScoringPublication publication(String id, String forumId, String date, String doi, String wosId, int authorCount, String title) {
        return new ScoringPublication(id, "eid-" + id, forumId, date, "ar", null, List.of("a1"), authorCount, doi, wosId, title, 0, java.util.Set.of());
    }

    private ScholardexForumView forum(String name, String issn, String eIssn) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName(name);
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        return forum;
    }

    private CNFISReport2025 report(java.util.function.Consumer<CNFISReport2025> customizer) {
        CNFISReport2025 report = new CNFISReport2025();
        report.setNumarAutoriUniversitate(2);
        customizer.accept(report);
        return report;
    }

    private static final class MemoryServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream delegate;

        private MemoryServletOutputStream(ByteArrayOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }
    }
}
