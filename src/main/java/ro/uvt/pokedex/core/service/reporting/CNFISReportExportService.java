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

            workbook.setForceFormulaRecalculation(true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    void populateSheet(Workbook workbook,
                       Sheet sheet,
                       List<? extends ScoringPublicationReadModel> publications,
                       List<CNFISReport2025> cnfisReports,
                       Map<String, ScholardexForumView> forumMap,
                       int rowNum,
                       int sampleRowNum) {
        for (int i = 0; i < publications.size(); i++) {
            ScoringPublicationReadModel publication = publications.get(i);
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
            if ((doi.isEmpty() || doi.equals("null")) && wosCode.isEmpty()){
                continue;
            }
            String brevetCode = "";
            ScholardexForumView forum = forumMap.getOrDefault(publication.getForumId(), new ScholardexForumView());
            String forumName = forum.getPublicationName();
            String issnOnline = forum.getEIssn();
            if(issnOnline.contains("null"))
                issnOnline = "";
            String issnPrint = forum.getIssn();
            if(issnPrint.contains("null"))
                issnPrint = "";
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
