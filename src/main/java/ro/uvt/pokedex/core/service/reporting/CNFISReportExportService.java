package ro.uvt.pokedex.core.service.reporting;


import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.util.List;
import java.util.Map;

@Service
public class CNFISReportExportService {
    private static final Logger log = LoggerFactory.getLogger(CNFISReportExportService.class);


    public void exportCNFISReport2025(List<? extends ScoringPublicationReadModel> publications,
                                      List<CNFISReport2025> cnfisReports,
                                      Map<String, ScholardexForumView> forumMap,
                                      List<String> authorIds,
                                      HttpServletResponse response, boolean group) throws IOException {
        String filename;
        if(group)
            filename = "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx";
        else
            filename = "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx";
        // Load the template Excel file
        try (InputStream resource = new FileInputStream(filename);
             Workbook workbook = new XSSFWorkbook(resource)) {

            Sheet sheet;
            if(group)
                sheet = workbook.getSheetAt(1);
            else{
                sheet = workbook.getSheetAt(0);
            }

            int rowNum = group ? 9 : 17;
            int sampleRowNum = group ? 8 : 16;
            populateSheet(workbook, sheet, publications, cnfisReports, forumMap, rowNum, sampleRowNum);
            addLeftOutSheet(workbook, publications, cnfisReports, forumMap);

            workbook.setForceFormulaRecalculation(true);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            workbook.write(response.getOutputStream());
        }
    }

    static Row copyRow(Workbook workbook, Sheet worksheet, int sourceRowNum, int destinationRowNum) {
        Row newRow = worksheet.getRow(destinationRowNum);
        Row sourceRow = worksheet.getRow(sourceRowNum);

        if (newRow != null) {
            worksheet.shiftRows(destinationRowNum, worksheet.getLastRowNum(), 1);
            newRow = worksheet.createRow(destinationRowNum);
        } else {
            newRow = worksheet.createRow(destinationRowNum);
        }

        for (int i = 0; i < sourceRow.getLastCellNum(); i++) {
            Cell oldCell = sourceRow.getCell(i);
            Cell newCell = newRow.createCell(i);
            if (oldCell == null) {
                continue;
            }
            CellStyle newCellStyle = workbook.createCellStyle();
            newCellStyle.cloneStyleFrom(oldCell.getCellStyle());
            newCell.setCellStyle(newCellStyle);
            if (oldCell.getCellComment() != null) {
                newCell.setCellComment(oldCell.getCellComment());
            }
            if (oldCell.getHyperlink() != null) {
                newCell.setHyperlink(oldCell.getHyperlink());
            }
            switch (oldCell.getCellType()) {
                case BLANK:
                    newCell.setCellValue(oldCell.getStringCellValue());
                    break;
                case BOOLEAN:
                    newCell.setCellValue(oldCell.getBooleanCellValue());
                    break;
                case ERROR:
                    newCell.setCellErrorValue(oldCell.getErrorCellValue());
                    break;
                case FORMULA:
                    newCell.setCellFormula(oldCell.getCellFormula());
                    break;
                case NUMERIC:
                    newCell.setCellValue(oldCell.getNumericCellValue());
                    break;
                case STRING:
                    newCell.setCellValue(oldCell.getRichStringCellValue());
                    break;
            }
        }
        for (int i = 0; i < worksheet.getNumMergedRegions(); i++) {
            CellRangeAddress cellRangeAddress = worksheet.getMergedRegion(i);
            if (cellRangeAddress.getFirstRow() == sourceRow.getRowNum()) {
                CellRangeAddress newCellRangeAddress = new CellRangeAddress(newRow.getRowNum(),
                        (newRow.getRowNum() + (cellRangeAddress.getLastRow() - cellRangeAddress.getFirstRow())),
                        cellRangeAddress.getFirstColumn(),
                        cellRangeAddress.getLastColumn());
                worksheet.addMergedRegion(newCellRangeAddress);
            }
        }
        return newRow;
    }

    public byte[] generateCNFISReportWorkbook(List<? extends ScoringPublicationReadModel> publications,
                                              List<CNFISReport2025> cnfisReports,
                                              Map<String, ScholardexForumView> forumMap,
                                              List<String> authorIds,
                                              boolean group) throws IOException {
        String filename = group ? "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx"
                : "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx";
        try (InputStream resource = new FileInputStream(filename);
             Workbook workbook = new XSSFWorkbook(resource)) {

            Sheet sheet = group ? workbook.getSheetAt(1) : workbook.getSheetAt(0);
            int rowNum = group ? 9 : 17;
            int sampleRowNum = group ? 8 : 16;

            populateSheet(workbook, sheet, publications, cnfisReports, forumMap, rowNum, sampleRowNum);
            addLeftOutSheet(workbook, publications, cnfisReports, forumMap);

            workbook.setForceFormulaRecalculation(true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    /**
     * H129 — the Anexa 5 workbook of a person: the classified publications, then the patents (from the
     * declared activity "Brevet"), then the platform's sheet of left-out publications.
     */
    public byte[] generateAnexa5(List<? extends ScoringPublicationReadModel> publications,
                                 List<CNFISReport2025> cnfisReports,
                                 Map<String, ScholardexForumView> forumMap,
                                 List<CNFISReport2025> patents) throws IOException {
        return generate("data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx", 0, 17, 16,
                publications, cnfisReports, forumMap, patents);
    }

    /** Slice 3 — the institutional table (Anexa 6) of a unit, from what its members' frozen sheets hold. */
    public byte[] generateAnexa6(List<? extends ScoringPublicationReadModel> publications,
                                 List<CNFISReport2025> cnfisReports,
                                 Map<String, ScholardexForumView> forumMap,
                                 List<CNFISReport2025> patents) throws IOException {
        return generate("data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx", 1, 9, 8,
                publications, cnfisReports, forumMap, patents);
    }

    private byte[] generate(String template, int sheetIndex, int firstRow, int sampleRow,
                            List<? extends ScoringPublicationReadModel> publications,
                            List<CNFISReport2025> cnfisReports,
                            Map<String, ScholardexForumView> forumMap,
                            List<CNFISReport2025> patents) throws IOException {
        try (InputStream resource = new FileInputStream(template);
             Workbook workbook = new XSSFWorkbook(resource)) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int next = populateSheet(workbook, sheet, publications, cnfisReports, forumMap, firstRow, sampleRow);
            populatePatents(workbook, sheet, patents, next, sampleRow);
            addLeftOutSheet(workbook, publications, cnfisReports, forumMap);
            workbook.setForceFormulaRecalculation(true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    /** One row per patent: year, title, patent code, office, the category column, the author counts. */
    void populatePatents(Workbook workbook, Sheet sheet, List<CNFISReport2025> patents, int rowNum, int sampleRowNum) {
        for (CNFISReport2025 patent : patents) {
            int usableTemplateRow = findNextUsableTemplateRow(sheet, sampleRowNum);
            if (usableTemplateRow < 0) {
                throw new IllegalStateException("No suitable template row available for CNFIS export population.");
            }
            sampleRowNum = usableTemplateRow;
            Row row = copyRow(workbook, sheet, sampleRowNum, rowNum);
            row.getCell(1).setCellValue(patent.getListYear() == null ? "" : String.valueOf(patent.getListYear()));
            row.getCell(2).setCellValue(cellText(patent.getTitlu()));
            row.getCell(5).setCellValue(cellText(patent.getBrevetCode()));
            row.getCell(6).setCellValue(cellText(patent.getDenumireJurnal()));
            row.getCell(10).setCellValue(cellText(patent.getOficiuBrevet()));
            if (patent.isTriadice()) {
                row.getCell(21).setCellValue(1);
            } else if (patent.isEuropene()) {
                row.getCell(22).setCellValue(1);
            } else if (patent.isInternationale()) {
                row.getCell(23).setCellValue(1);
            } else if (patent.isNationale()) {
                row.getCell(24).setCellValue(1);
            }
            row.getCell(25).setCellValue(patent.getNumarAutori());
            row.getCell(26).setCellValue(patent.getNumarAutoriUniversitate());
            rowNum++;
        }
    }

    /** @return the row number after the last row written */
    int populateSheet(Workbook workbook,
                      Sheet sheet,
                      List<? extends ScoringPublicationReadModel> publications,
                      List<CNFISReport2025> cnfisReports,
                      Map<String, ScholardexForumView> forumMap,
                      int rowNum,
                      int sampleRowNum) {
        for (int i = 0; i < publications.size(); i++) {
            ScoringPublicationReadModel publication = publications.get(i);
            // H129: decided BEFORE a template row is copied — a skipped publication used to leave a blank row
            if (leftOutReason(publication, cnfisReports.get(i)) != null) {
                continue;
            }
            int usableTemplateRow = findNextUsableTemplateRow(sheet, sampleRowNum);
            if (usableTemplateRow < 0) {
                throw new IllegalStateException("No suitable template row available for CNFIS export population.");
            }
            sampleRowNum = usableTemplateRow;
            Row row = copyRow(workbook, sheet, sampleRowNum, rowNum);
            String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
            String title = publication.getTitle() != null ? publication.getTitle() : "";
            String doi = publication.getDoi() != null ? publication.getDoi() : "";
            String wosCode = publication.getWosId() != null && !publication.getWosId().equals(CanonicalPublicationConstants.NON_WOS_ID)
                    ? publication.getWosId() : "";
            String brevetCode = "";
            ScholardexForumView forum = forumMap.getOrDefault(publication.getForumId(), new ScholardexForumView());
            // A forum without an ISSN (conference series, book) or a publication without a forum at all is normal;
            // the export used to NPE here (prod 2026-09-24, every researcher with one such confirmed paper).
            String forumName = cellText(forum.getPublicationName());
            String issnOnline = cellText(forum.getEIssn());
            String issnPrint = cellText(forum.getIssn());
            String isbn = "";
            int totalAuthors = publication.getAuthorCount();

            row.getCell(1).setCellValue(year);
            row.getCell(2).setCellValue(title);
            row.getCell(3).setCellValue(doi);
            row.getCell(4).setCellValue(wosCode);
            row.getCell(5).setCellValue(brevetCode);
            row.getCell(6).setCellValue(forumName);
            row.getCell(7).setCellValue(issnOnline);
            row.getCell(8).setCellValue(issnPrint);
            row.getCell(9).setCellValue(isbn);
            CNFISReport2025 cnfisReport = cnfisReports.get(i);
            long universityAuthors = cnfisReport.getNumarAutoriUniversitate();
            if (cnfisReport.isIsiQ1()){
                row.getCell(12).setCellValue(1);
            } else if (cnfisReport.isIsiQ2()) {
                row.getCell(13).setCellValue(1);
            } else if (cnfisReport.isIsiQ3()) {
                row.getCell(14).setCellValue(1);
            } else if (cnfisReport.isIsiQ4()) {
                row.getCell(15).setCellValue(1);
            } else if (cnfisReport.isIsiArtsHumanities()) {
                row.getCell(16).setCellValue(1);
            } else if(cnfisReport.isIsiEmergingSourcesCitationIndex()){
                row.getCell(17).setCellValue(1);
            } else if (cnfisReport.isErihPlus()) {
                row.getCell(18).setCellValue(1);
            } else if (cnfisReport.isIsiProceedings()) {
                row.getCell(19).setCellValue(1);
            } else if (cnfisReport.isIeeeProceedings()) {
                row.getCell(20).setCellValue(1);
            }
            row.getCell(25).setCellValue(totalAuthors);
            row.getCell(26).setCellValue(universityAuthors);
            rowNum++;
        }
        return rowNum;
    }

    /**
     * H129 — why a publication has no row in the form, or null when it has one. The form asks for a DOI or a
     * WoS code ("cel puțin unul din coduri") and has a place only for the categories it lists.
     */
    public static String leftOutReason(ScoringPublicationReadModel publication, CNFISReport2025 report) {
        String doi = publication.getDoi();
        boolean hasDoi = doi != null && !doi.isBlank() && !doi.equals("null");
        String wos = publication.getWosId();
        boolean hasWos = wos != null && !wos.isBlank() && !wos.equals(CanonicalPublicationConstants.NON_WOS_ID);
        if (!hasDoi && !hasWos) {
            return "neither a DOI nor a WoS code: the form asks for at least one of them";
        }
        if (report == null || !report.isClassified()) {
            return report != null && report.getLeftOutReason() != null
                    ? report.getLeftOutReason()
                    : "in none of the categories of the form";
        }
        return null;
    }

    static final String LEFT_OUT_SHEET = "Neincluse (platforma)";

    /**
     * H129 — the publications that got no row, each with its reason, on a sheet of its own at the end of the
     * workbook. It is the platform's note to the person who checks the file, not part of the form: delete the
     * sheet before the file is handed in.
     */
    void addLeftOutSheet(Workbook workbook,
                         List<? extends ScoringPublicationReadModel> publications,
                         List<CNFISReport2025> cnfisReports,
                         Map<String, ScholardexForumView> forumMap) {
        Sheet sheet = null;
        int rowNum = 0;
        for (int i = 0; i < publications.size(); i++) {
            ScoringPublicationReadModel publication = publications.get(i);
            String reason = leftOutReason(publication, cnfisReports.get(i));
            if (reason == null) {
                continue;
            }
            if (sheet == null) {
                sheet = workbook.createSheet(LEFT_OUT_SHEET);
                sheet.createRow(rowNum++).createCell(0).setCellValue(
                        "Publicații fără rând în fișă / publications with no row in the form —"
                                + " nota platformei, se șterge înainte de depunere");
                Row header = sheet.createRow(rowNum++);
                header.createCell(0).setCellValue("An");
                header.createCell(1).setCellValue("Titlu");
                header.createCell(2).setCellValue("Jurnal / volum");
                header.createCell(3).setCellValue("DOI");
                header.createCell(4).setCellValue("Motiv / reason");
            }
            ScholardexForumView forum = forumMap.getOrDefault(publication.getForumId(), new ScholardexForumView());
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(
                    PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log));
            row.createCell(1).setCellValue(publication.getTitle() == null ? "" : publication.getTitle());
            row.createCell(2).setCellValue(cellText(forum.getPublicationName()));
            row.createCell(3).setCellValue(cellText(publication.getDoi()));
            row.createCell(4).setCellValue(reason);
        }
    }

    /** Null, blank and the literal "null" (a legacy string on some forum rows) all render as an empty cell. */
    private static String cellText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.contains("null") ? "" : trimmed;
    }

    private int findNextUsableTemplateRow(Sheet sheet, int startRowNum) {
        for (int rowNum = Math.max(0, startRowNum); rowNum <= sheet.getLastRowNum(); rowNum++) {
            Row candidate = sheet.getRow(rowNum);
            if (candidate != null && candidate.getLastCellNum() >= 25) {
                return rowNum;
            }
        }
        return -1;
    }
}
