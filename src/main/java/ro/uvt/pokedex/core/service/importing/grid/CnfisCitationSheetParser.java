package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H142 — reads a person's CNFIS Anexa 4.1 ("Fişa individuală pentru impactul creaţiei artistice", IC 2.2, for the whole
 * career): one row per citation with the year of the cited work (B), the work as the form identifies it (C) and the
 * citation — the publication, its issue and the year (D). The year of the citation is the last year written in D.
 */
public final class CnfisCitationSheetParser {

    /** One citation; {@code citationYear} is null when D names no year. */
    public record CitationRow(Integer workYear, String work, String citation, Integer citationYear) {
    }

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    private CnfisCitationSheetParser() {
    }

    public static boolean looksLikeCitationSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (citationColumn(sheet) != null) {
                return true;
            }
        }
        return false;
    }

    public static List<CitationRow> parse(Workbook workbook) {
        List<CitationRow> rows = new ArrayList<>();
        for (Sheet sheet : workbook) {
            int[] start = citationColumn(sheet);
            if (start == null) {
                continue;
            }
            int headerRow = start[0], citationColumn = start[1];
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String first = MusicGridLayout.normalize(MusicGridParser.text(row.getCell(0)));
                if (first.startsWith("total")) {
                    break;
                }
                Integer workYear = yearOf(MusicGridParser.text(row.getCell(citationColumn - 2)));
                String work = MusicGridParser.text(row.getCell(citationColumn - 1)).trim();
                String citation = MusicGridParser.text(row.getCell(citationColumn)).trim();
                if (work.isEmpty() || citation.isEmpty() || (workYear == null && isGuidance(work))) {
                    continue; // the guidance row, the column letters, or an empty row of the form
                }
                rows.add(new CitationRow(workYear, work, citation, lastYear(citation)));
            }
        }
        return rows;
    }

    /** {header row, citation column} from the "Date de identificare ale citării" heading, or null. */
    private static int[] citationColumn(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String h = MusicGridLayout.normalize(MusicGridParser.text(cell));
                if (h.startsWith("date de identificare ale citarii") && cell.getColumnIndex() >= 2) {
                    return new int[]{r, cell.getColumnIndex()};
                }
            }
        }
        return null;
    }

    private static boolean isGuidance(String text) {
        String t = MusicGridLayout.normalize(text);
        return t.length() <= 2 || t.startsWith("domeniul de creatie") || t.startsWith("anul de referinta");
    }

    private static Integer yearOf(String text) {
        String t = text.trim();
        return t.matches("(19|20)\\d{2}(\\.0)?") ? Integer.valueOf(t.substring(0, 4)) : null;
    }

    /** The last plausible year D names ("Muzica, nr. 2/2022" → 2022), never one after the current year. */
    static Integer lastYear(String text) {
        Integer found = null;
        Matcher m = YEAR.matcher(text);
        int now = Year.now().getValue();
        while (m.find()) {
            int y = Integer.parseInt(m.group());
            if (y <= now) found = y;
        }
        return found;
    }
}
