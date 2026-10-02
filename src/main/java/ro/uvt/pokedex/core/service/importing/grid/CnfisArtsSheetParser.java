package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.List;

/**
 * H142 — reads a person's CNFIS Anexa 5.1 ("Fişa individuală pentru performanţa creaţiei artistice", IC2.3): one row
 * per performance with its year, the work, the event, a "1" in the column of its kind (individual project, group of
 * 2–4, collective of 5 or more, individual nomination, individual prize) and level (national, international, top
 * international), and the number of university participants. The guide allows one classification per row; a row
 * marked twice keeps the first mark and says so.
 */
public final class CnfisArtsSheetParser {

    /** One performance. {@code kind}: INDIVIDUAL, GROUP, COLLECTIVE, NOMINATION, PRIZE; {@code level}: NATIONAL, INTERNATIONAL, INTERNATIONAL_TOP. */
    public record ArtsRow(Integer year, String work, String event, String kind, String level, Integer participants,
                          boolean markedTwice) {
    }

    public record ParsedArts(List<ArtsRow> rows, int unmarked) {
    }

    private static final String[] KINDS = {"INDIVIDUAL", "GROUP", "COLLECTIVE", "NOMINATION", "PRIZE"};
    private static final String[] LEVELS = {"NATIONAL", "INTERNATIONAL", "INTERNATIONAL_TOP"};

    private CnfisArtsSheetParser() {
    }

    public static boolean looksLikeArtsSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (marksStart(sheet) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for the INSTITUTIONAL table (Anexa 6.1): the same columns as a person's Anexa 5.1, but every performance
     * of the university, so it must never be imported as one person's activities.
     */
    public static boolean isInstitutionalTable(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 3); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (Cell cell : row) {
                    String t = MusicGridLayout.normalize(MusicGridParser.text(cell));
                    if (t.startsWith("anexa 6") || t.contains("tabel institutional")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static ParsedArts parse(Workbook workbook) {
        List<ArtsRow> rows = new ArrayList<>();
        int unmarked = 0;
        for (Sheet sheet : workbook) {
            int[] start = marksStart(sheet);
            if (start == null) {
                continue;
            }
            int headerRow = start[0], firstMark = start[1], participantsColumn = start[2];
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String first = MusicGridLayout.normalize(MusicGridParser.text(row.getCell(0)));
                if (first.startsWith("total") || first.startsWith("puncte")) {
                    break;
                }
                Integer year = yearOf(MusicGridParser.text(row.getCell(firstMark - 3)));
                String work = MusicGridParser.text(row.getCell(firstMark - 2)).trim();
                String event = MusicGridParser.text(row.getCell(firstMark - 1)).trim();
                if (year == null || (work.isEmpty() && event.isEmpty())) {
                    continue; // a guidance row, the column numbers, or an empty template row
                }
                int marked = -1, marks = 0;
                for (int k = 0; k < 15; k++) {
                    if (isMark(row.getCell(firstMark + k))) {
                        marks++;
                        if (marked < 0) marked = k;
                    }
                }
                if (marked < 0) {
                    unmarked++;
                    continue;
                }
                Integer participants = participantsColumn >= 0 ? yearOrNumber(MusicGridParser.text(row.getCell(participantsColumn))) : null;
                rows.add(new ArtsRow(year, work, event, KINDS[marked / 3], LEVELS[marked % 3], participants, marks > 1));
            }
        }
        return new ParsedArts(rows, unmarked);
    }

    /** {header row, first mark column, participants column} from the "Proiecte individuale" heading, or null. */
    private static int[] marksStart(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int firstMark = -1, participants = -1;
            for (Cell cell : row) {
                String h = MusicGridLayout.normalize(MusicGridParser.text(cell));
                if (h.startsWith("proiecte individuale")) firstMark = cell.getColumnIndex();
                if (h.startsWith("nr participanti")) participants = cell.getColumnIndex();
            }
            if (firstMark >= 3) {
                return new int[]{r, firstMark, participants};
            }
        }
        return null;
    }

    private static boolean isMark(Cell cell) {
        String t = MusicGridParser.text(cell).trim();
        return !t.isEmpty() && !t.equals("0");
    }

    private static Integer yearOf(String text) {
        String t = text.trim();
        if (t.matches("(19|20)\\d{2}(\\.0)?")) {
            return Integer.parseInt(t.substring(0, 4));
        }
        return null;
    }

    private static Integer yearOrNumber(String text) {
        try {
            return (int) Math.round(Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
