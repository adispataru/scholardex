package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** H142 slice 7 — what every person's CNFIS sheet (Anexa 4.1, 5, 5.1) carries besides its rows. */
public final class CnfisSheets {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");

    private CnfisSheets() {
    }

    /**
     * The person the sheet is about: the value under the "Nume şi prenume" label of the identification block (a guidance
     * line may sit between them: "Date de identificare ale cadrului didactic …"). Empty when the form was left blank.
     */
    public static Optional<String> personName(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 14); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (Cell cell : row) {
                    if (!MusicGridLayout.normalize(MusicGridParser.text(cell)).startsWith("nume si prenume")) continue;
                    for (int below = r + 1; below <= r + 3; below++) {
                        Row next = sheet.getRow(below);
                        String value = next == null ? "" : MusicGridParser.text(next.getCell(cell.getColumnIndex())).trim();
                        if (value.isEmpty() || isIntermediateGuidance(value)) {
                            continue;
                        }
                        // the first other cell is the name; a label, a note or a position there means the name was left blank
                        return isName(value) ? Optional.of(value.replaceAll("\\s+", " ")) : Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The guidance line Anexa 5.1 puts between the label and the name. */
    private static boolean isIntermediateGuidance(String value) {
        String n = MusicGridLayout.normalize(value);
        return value.startsWith("←") || n.startsWith("date de identificare") || n.startsWith("cnp");
    }

    /** Labels, notes, positions and the institution's own lines a form may hold under the label: no name. */
    private static final List<String> NOT_A_NAME = List.of("se va", "se completeaza", "nota", "note", "functia", "facultatea",
            "universitatea", "departamentul", "titular", "cod domeniu", "domeniu", "nr crt", "conferentiar", "asistent",
            "lector", "profesor", "sef de lucrari", "cs ");

    private static boolean isName(String value) {
        String n = MusicGridLayout.normalize(value);
        return n.length() >= 3 && value.length() <= 80 && NOT_A_NAME.stream().noneMatch(n::startsWith);
    }

    /** The last plausible year a text names (an event "14–20 octombrie 2023"), never one after the current year. */
    public static Integer lastYear(String text) {
        Integer found = null;
        Matcher m = YEAR.matcher(text == null ? "" : text);
        int now = Year.now().getValue();
        while (m.find()) {
            int y = Integer.parseInt(m.group());
            if (y >= 1950 && y <= now) found = y;
        }
        return found;
    }
}
