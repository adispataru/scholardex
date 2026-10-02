package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * H142 — reads a filled Music fișă de verificare (the faculty's Excel grid: one sheet per table, a row per item of
 * the standard, the candidate's activities listed in the "Activități candidat" cell). Each row is recognised by
 * its label ({@link MusicGridLayout#classify}); a row with activities but a label nobody recognises is reported,
 * never guessed.
 */
public final class MusicGridParser {

    /** A recognised row and the activities listed in it. */
    public record ParsedRow(MusicGridLayout.Row row, String label, List<GridItemSplitter.Item> items) {
    }

    /** What the file holds: the name in its heading (if any), the recognised rows, the labels of the rows left out. */
    public record ParsedGrid(String heading, List<ParsedRow> rows, List<String> unrecognised) {
        public int itemCount() {
            return rows.stream().mapToInt(r -> r.items().size()).sum();
        }
    }

    private static final DataFormatter FORMAT = new DataFormatter();
    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "(?i)^\\s*(lect|conf|prof|asist|cs\\b|cercet|dr\\.|drd)");

    private MusicGridParser() {
    }

    /** True when some sheet of the workbook is a table of the Music grid with an "Activități candidat" column. */
    public static boolean looksLikeGrid(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (tableOf(sheet).isPresent() && header(sheet) != null) {
                return true;
            }
        }
        return false;
    }

    public static ParsedGrid parse(Workbook workbook) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String> unrecognised = new ArrayList<>();
        String heading = null;
        for (Sheet sheet : workbook) {
            Optional<MusicGridLayout.Table> table = tableOf(sheet);
            int[] header = header(sheet);
            if (table.isEmpty() || header == null) {
                continue;
            }
            if (heading == null) {
                heading = heading(sheet, header[0]);
            }
            int labelColumn = header[1], itemsColumn = header[2];
            for (int r = header[0] + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String label = text(row.getCell(labelColumn));
                if (label.isBlank() && labelColumn > 0) {
                    label = text(row.getCell(labelColumn - 1));
                }
                String items = text(row.getCell(itemsColumn));
                if (MusicGridLayout.normalize(label).contains("punctaj cumulativ")
                        || MusicGridLayout.normalize(text(row.getCell(0))).contains("punctaj cumulativ")) {
                    break; // the table's minimum, below its last item
                }
                if (items.isBlank()) {
                    continue;
                }
                Optional<MusicGridLayout.Row> recognised = MusicGridLayout.classify(table.get(), label);
                if (recognised.isEmpty()) {
                    unrecognised.add(table.get() + ": " + abbreviate(label, 120));
                    continue;
                }
                rows.add(new ParsedRow(recognised.get(), label, GridItemSplitter.split(items)));
            }
        }
        return new ParsedGrid(heading, rows, unrecognised);
    }

    private static Optional<MusicGridLayout.Table> tableOf(Sheet sheet) {
        Optional<MusicGridLayout.Table> byName = MusicGridLayout.tableOf(sheet.getSheetName());
        if (byName.isPresent()) {
            return byName;
        }
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 6); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                Optional<MusicGridLayout.Table> byTitle = MusicGridLayout.tableOf(text(cell));
                if (byTitle.isPresent() && MusicGridLayout.normalize(text(cell)).contains("tabelul")) {
                    return byTitle;
                }
            }
        }
        return Optional.empty();
    }

    /** {header row, label column, items column}, or null when the sheet has no "Activități candidat" heading. */
    private static int[] header(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 15); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int items = -1, label = -1;
            for (Cell cell : row) {
                String h = MusicGridLayout.normalize(text(cell));
                if (h.contains("activitati candidat")) items = cell.getColumnIndex();
                if (h.contains("categorii")) label = cell.getColumnIndex();
            }
            if (items >= 0) {
                return new int[]{r, label >= 0 ? label : Math.max(0, items - 3), items};
            }
        }
        return null;
    }

    /** The candidate's name as the file's heading gives it ("Lect.univ.dr. NUME PRENUME"), or null. */
    private static String heading(Sheet sheet, int headerRow) {
        for (int r = 0; r < headerRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String t = text(cell).trim();
                if (!t.isEmpty() && ACADEMIC_TITLE.matcher(t).find() && !MusicGridLayout.normalize(t).contains("tabelul")) {
                    return t;
                }
            }
        }
        return null;
    }

    static String text(Cell cell) {
        return cell == null ? "" : FORMAT.formatCellValue(cell);
    }

    static String abbreviate(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
