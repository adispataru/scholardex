package ro.uvt.pokedex.core.service.importing.grid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H142 — splits the "Activități candidat" cell of a filled fișă de verificare into its items. A colleague writes
 * one item per line, usually behind a bullet or a dash, sometimes with year headers ("Anul 2026") between them and
 * the link of a recording on its own line. Measured on a real Music grid: the item count of every row matched the
 * points claimed in it.
 */
public final class GridItemSplitter {

    /** One item: its text, the year it happened (from a date, a year in the text, or the year header above it), its links. */
    public record Item(String text, Integer year, String date, List<String> links) {
    }

    private static final Pattern FULL_DATE = Pattern.compile("\\b(\\d{1,2})[./](\\d{1,2})[./]((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b((?:19[5-9]|20[0-4])\\d)\\b");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s)\\]>,]+");
    private static final Pattern YEAR_HEADER = Pattern.compile("^(?:anul\\s+)?((?:19|20)\\d{2})\\s*:?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("\\r?\\n\\s*[•\\-–·▪◦]\\s*|\\s*•\\s*|\\r?\\n");
    private static final Pattern LEADING_MARKS = Pattern.compile("^[\\s•\\-–·▪◦*]+");

    private GridItemSplitter() {
    }

    public static List<Item> split(String cell) {
        List<Item> items = new ArrayList<>();
        if (cell == null || cell.isBlank()) {
            return items;
        }
        Integer headerYear = null;
        List<String> pieces = new ArrayList<>();
        for (String raw : SEPARATORS.split(cell.trim())) {
            String piece = LEADING_MARKS.matcher(raw).replaceFirst("").trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
        }
        List<StringBuilder> texts = new ArrayList<>();
        List<Integer> headerYears = new ArrayList<>();
        for (String piece : pieces) {
            Matcher header = YEAR_HEADER.matcher(piece);
            if (header.matches()) {
                headerYear = Integer.parseInt(header.group(1));
                continue;
            }
            String bare = piece.replaceAll("^[(\\[]+|[)\\]]+$", "").trim();
            if (LINK.matcher(bare).matches() && !texts.isEmpty()) {
                // a link alone on its line belongs to the item above it (a recording and its address)
                texts.getLast().append(' ').append(bare);
                continue;
            }
            if (piece.replaceAll("[^\\p{L}\\p{N}]", "").length() < 4) {
                continue; // a stray mark or a lone number, not an item
            }
            texts.add(new StringBuilder(piece));
            headerYears.add(headerYear);
        }
        for (int i = 0; i < texts.size(); i++) {
            items.add(item(texts.get(i).toString(), headerYears.get(i)));
        }
        return items;
    }

    private static Item item(String text, Integer headerYear) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        LINK.matcher(text).results().forEach(m -> found.add(m.group()));
        List<String> links = new ArrayList<>(found);
        String withoutLinks = LINK.matcher(text).replaceAll(" ");
        Matcher full = FULL_DATE.matcher(withoutLinks);
        if (full.find()) {
            int day = Integer.parseInt(full.group(1));
            int month = Integer.parseInt(full.group(2));
            int year = Integer.parseInt(full.group(3));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                return new Item(text, year, String.format("%04d-%02d-%02d", year, month, day), links);
            }
        }
        Matcher year = YEAR.matcher(withoutLinks);
        if (year.find()) {
            int y = Integer.parseInt(year.group(1));
            return new Item(text, y, y + "-01-01", links);
        }
        if (headerYear != null) {
            return new Item(text, headerYear, headerYear + "-01-01", links);
        }
        return new Item(text, null, null, links);
    }
}
