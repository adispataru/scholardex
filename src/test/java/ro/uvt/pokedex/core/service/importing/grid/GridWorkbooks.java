package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Builds, in code, workbooks shaped like the files colleagues have: the faculty's Music grid and a person's CNFIS
 * Anexa 5.1. No real file is committed — the texts are invented.
 */
public final class GridWorkbooks {

    private GridWorkbooks() {
    }

    /** A row of a table sheet: column A, column B (the label), and the candidate's activities. */
    public record GridLine(String typeColumn, String label, String activities) {
    }

    public static XSSFWorkbook musicGrid(String heading, List<GridLine> did, List<GridLine> cs, List<GridLine> ria) {
        XSSFWorkbook wb = new XSSFWorkbook();
        table(wb, "Tabelul 1 - DID", "Tabelul 1: Activitatea didactică și profesională (DID) - Domeniul MUZICĂ", heading, did);
        table(wb, "Tabelul 2 - CS", "Tabelul 2: Activitatea de cercetare științifică/creație artistică în domeniul specific (CS)", null, cs);
        table(wb, "Tabelul 3 - RIA", "Tabelul 3: Recunoaștere și impactul activității (acronim RIA)", null, ria);
        Sheet databases = wb.createSheet("Baze de Date");
        databases.createRow(0).createCell(0).setCellValue("Lista bazelor de date internaționale recunoscute pentru domeniul Muzică");
        return wb;
    }

    private static void table(XSSFWorkbook wb, String sheetName, String title, String heading, List<GridLine> lines) {
        Sheet sheet = wb.createSheet(sheetName);
        int r = 0;
        if (heading != null) {
            sheet.createRow(r++).createCell(0).setCellValue(heading);
        }
        sheet.createRow(r++).createCell(0).setCellValue(title);
        Row header = sheet.createRow(r++);
        String[] columns = {"Tipul activităților", "Categorii și restricții", "Punctaj", "Activități minimale obligatorii",
                "Activități candidat", "Punctaj candidat"};
        for (int c = 0; c < columns.length; c++) header.createCell(c).setCellValue(columns[c]);
        for (GridLine line : lines) {
            Row row = sheet.createRow(r++);
            if (line.typeColumn() != null) row.createCell(0).setCellValue(line.typeColumn());
            if (line.label() != null) row.createCell(1).setCellValue(line.label());
            row.createCell(2).setCellValue("10p");
            if (line.activities() != null) row.createCell(4).setCellValue(line.activities());
        }
        sheet.createRow(r).createCell(0).setCellValue("Punctaj cumulativ minim:");
    }

    /** A row of Anexa 5.1: year, work, event, the column (1–15) of its mark, the university participants. */
    public record ArtsLine(Object year, String work, String event, int mark, Integer participants) {
    }

    public static XSSFWorkbook cnfisArts(String title, List<ArtsLine> lines) {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("A5.1-IC2.3-Performanta-creatie");
        sheet.createRow(0).createCell(0).setCellValue(title);
        Row groups = sheet.createRow(7);
        groups.createCell(0).setCellValue("Nr. Crt");
        groups.createCell(1).setCellValue("An referinţă");
        groups.createCell(2).setCellValue("Date de identificare activitate de creaţie artistică");
        groups.createCell(3).setCellValue("Manifestare/ festival/concurs");
        groups.createCell(4).setCellValue("Proiecte individuale");
        groups.createCell(7).setCellValue("Proiecte grup");
        groups.createCell(10).setCellValue("Proiecte colective");
        groups.createCell(13).setCellValue("Nominalizări individuale");
        groups.createCell(16).setCellValue("Premii individuale");
        groups.createCell(19).setCellValue("Nr. participanți proiecte grup/proiecte colective (din universitate)");
        Row levels = sheet.createRow(8);
        for (int k = 0; k < 15; k++) levels.createCell(4 + k).setCellValue(new String[]{"național", "internațional", "internațional de vârf"}[k % 3]);
        Row guidance = sheet.createRow(9);
        guidance.createCell(1).setCellValue("Se selectează din lista predefinită anul de referinţă");
        Row numbers = sheet.createRow(10);
        numbers.createCell(0).setCellValue("A");
        numbers.createCell(1).setCellValue("B");
        int r = 11;
        for (ArtsLine line : lines) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(r - 11);
            if (line.year() instanceof Number n) row.createCell(1).setCellValue(n.doubleValue());
            else if (line.year() != null) row.createCell(1).setCellValue(line.year().toString());
            row.createCell(2).setCellValue(line.work());
            row.createCell(3).setCellValue(line.event());
            if (line.mark() >= 1) row.createCell(3 + line.mark()).setCellValue(1);
            if (line.participants() != null) row.createCell(19).setCellValue(line.participants());
        }
        sheet.createRow(r).createCell(0).setCellValue("Total general (nr.proiecte/nominalizări/premii)");
        return wb;
    }

    public static byte[] bytes(XSSFWorkbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }
}
