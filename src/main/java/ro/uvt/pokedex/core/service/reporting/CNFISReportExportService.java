package ro.uvt.pokedex.core.service.reporting;


import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.util.List;
import java.util.Map;

@Service
public class CNFISReportExportService {
    private static final Logger log = LoggerFactory.getLogger(CNFISReportExportService.class);


    public void exportCNFISReport2025(List<Publication> publications,
                                      List<CNFISReport2025> cnfisReports,
                                      Map<String, Forum> forumMap,
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

            int rowNum = 9;
            int sampleRowNum = 8;
            if(!group) {
                rowNum = 17;
                sampleRowNum = 16;
            }

            for (int i = 0; i < publications.size(); i++) {
                Publication publication = publications.get(i);
                Row row = copyRow(workbook, sheet, sampleRowNum, rowNum);
                // If the copied row does not contain enough cells, adjust row numbers and skip row
                if (row.getLastCellNum() < 25) {
                    rowNum++;
                    sampleRowNum++;
                    i--;
                    continue;
                }
                String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String wosCode = publication.getWosId() != null && !publication.getWosId().equals(Publication.NON_WOS_ID)
                        ? publication.getWosId() : "";
                // Skip publication if both doi and wosCode are empty
                if ((doi.isEmpty() || doi.equals("null")) && wosCode.isEmpty()){
                    continue;
                }
                String brevetCode = "";
                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
                String issnOnline = forumMap.getOrDefault(publication.getForum(), new Forum()).getEIssn();
                if(issnOnline.contains("null"))
                    issnOnline = "";
                String issnPrint = forumMap.getOrDefault(publication.getForum(), new Forum()).getIssn();
                if(issnPrint.contains("null"))
                    issnPrint = "";
                String isbn = "";
                int totalAuthors = publication.getAuthors().size();

                // Set cell values similar to the original logic
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

            workbook.setForceFormulaRecalculation(true);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            workbook.write(response.getOutputStream());
        }
    }

    private static Row copyRow(Workbook workbook, Sheet worksheet, int sourceRowNum, int destinationRowNum) {
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
            newCell.setCellType(oldCell.getCellType());
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

    public byte[] generateCNFISReportWorkbook(List<Publication> publications,
                                              List<CNFISReport2025> cnfisReports,
                                              Map<String, Forum> forumMap,
                                              List<String> authorIds,
                                              boolean group) throws IOException {
        String filename = group ? "data/templates/AC2025_Anexa6-Tabel_institutional_articole_brevete-2025.xlsx"
                : "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx";
        try (InputStream resource = new FileInputStream(filename);
             Workbook workbook = new XSSFWorkbook(resource)) {

            Sheet sheet = group ? workbook.getSheetAt(1) : workbook.getSheetAt(0);
            int rowNum = group ? 9 : 17;
            int sampleRowNum = group ? 8 : 16;

            for (int i = 0; i < publications.size(); i++) {
                Publication publication = publications.get(i);
                Row row = copyRow(workbook, sheet, sampleRowNum, rowNum);
                if (row.getLastCellNum() < 25) {
                    rowNum++;
                    sampleRowNum++;
                    i--;
                    continue;
                }
                String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String wosCode = publication.getWosId() != null && !publication.getWosId().equals(Publication.NON_WOS_ID)
                        ? publication.getWosId() : "";
                if ((doi.isEmpty() || doi.equals("null")) && wosCode.isEmpty()){
                    continue;
                }
                String brevetCode = "";
                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
                String issnOnline = forumMap.getOrDefault(publication.getForum(), new Forum()).getEIssn();
                if(issnOnline.contains("null"))
                    issnOnline = "";
                String issnPrint = forumMap.getOrDefault(publication.getForum(), new Forum()).getIssn();
                if(issnPrint.contains("null"))
                    issnPrint = "";
                String isbn = "";
                int totalAuthors = publication.getAuthors().size();

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

            workbook.setForceFormulaRecalculation(true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return bos.toByteArray();
        }
    }
}
