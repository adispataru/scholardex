package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * H142 — the line a faculty's grid lists for one activity (one per line in the row's cell): what the person
 * wrote, not the type's name. That is the record's name, unless the name is only the type's or was shortened on
 * import, in which case the record's own text field; then the year when the text does not already carry it.
 */
public final class GridItemDescription {

    private static final Pattern LINK = Pattern.compile("(?i)^https?://\\S+$");
    private static final Pattern NUMBER = Pattern.compile("^[0-9 .,]+$");

    private GridItemDescription() {
    }

    /** {@code attrs}: the activity record as the run stores it (id, name, date, fields, activity…). */
    public static String of(Map<?, ?> attrs) {
        if (attrs == null) {
            return null;
        }
        String name = text(attrs.get("name"));
        String typeName = attrs.get("activity") instanceof Map<?, ?> type ? text(type.get("name")) : "";
        String longest = attrs.get("fields") instanceof Map<?, ?> fields
                ? fields.values().stream()
                        .filter(Objects::nonNull)
                        .map(GridItemDescription::text)
                        .filter(v -> !v.isEmpty() && !LINK.matcher(v).matches() && !NUMBER.matcher(v).matches())
                        .max(Comparator.comparingInt(String::length))
                        .orElse("")
                : "";
        String line;
        if (name.isEmpty() || name.equals(typeName)) {
            line = longest.isEmpty() ? name : longest;
        } else if (name.endsWith("…") && longest.startsWith(name.substring(0, name.length() - 1))) {
            line = longest; // the import shortened the name; the field holds the whole text
        } else {
            line = name;
        }
        String date = text(attrs.get("date"));
        if (date.length() >= 4 && date.substring(0, 4).matches("\\d{4}") && !line.contains(date.substring(0, 4))) {
            line = line + " (" + date.substring(0, 4) + ")";
        }
        line = line.replaceAll("\\s*\\R\\s*", " ").trim();
        return line.isEmpty() ? null : line;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
