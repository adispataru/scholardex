package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.activities.Activity;

import java.time.Year;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * H145 — what a researcher may store on a declared activity: the fields and the references its type declares, one of
 * the options of each select, and a plain number in a sensible range for each number field (no exponent, no
 * "Infinity", no year 1). A record names at most one registry entity (a type that offers a conference OR an artistic
 * event takes one of them, so a second name cannot lift the first). Every write path goes through it — the workspace
 * form, the bulk review — so scoring never meets a value it refused.
 */
public final class ActivityRecordValidator {

    /** The references naming an entity a registry ranks or a list classifies: one per record. */
    static final Set<Activity.ReferenceField> ENTITY_REFERENCES = EnumSet.of(
            Activity.ReferenceField.CONFERENCE_NAME, Activity.ReferenceField.ORGANIZATION_NAME,
            Activity.ReferenceField.AWARD_NAME, Activity.ReferenceField.EVENT_NAME,
            Activity.ReferenceField.UNIVERSITY_NAME);

    static final int TEXT_MAX = 4000;
    static final int REFERENCE_MAX = 500;
    /** No function, edition or committee year before this counts (nor can it be stored). */
    public static final int FIRST_YEAR = 1950;

    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d{1,12}([.,]\\d{1,6})?");

    private ActivityRecordValidator() {
    }

    /** What may be stored, and what was refused ("Rol: «x»"); nothing is stored when anything was refused. */
    public record Result(Map<String, String> fields, Map<Activity.ReferenceField, String> references,
                         List<String> problems) {
        public boolean valid() {
            return problems.isEmpty();
        }
    }

    /** A number field's range (inclusive) and whether it takes whole numbers only. */
    record Range(double min, double max, boolean integer) {
    }

    public static Result validate(Activity type, Map<String, String> fields, Map<Activity.ReferenceField, String> references) {
        List<String> problems = new ArrayList<>();
        Map<String, String> keptFields = new LinkedHashMap<>();
        Map<String, Activity.Field> declared = new LinkedHashMap<>();
        if (type != null && type.getFields() != null) {
            type.getFields().forEach(f -> declared.put(f.getName(), f));
        }
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (value.isEmpty()) {
                    continue; // a blank value is no value
                }
                Activity.Field field = declared.get(entry.getKey());
                if (field == null) {
                    problems.add(entry.getKey() + ": «" + abbreviate(value) + "»");
                    continue;
                }
                String problem = problem(field, value);
                if (problem != null) {
                    problems.add(problem);
                    continue;
                }
                keptFields.put(field.getName(), field.isNumber() ? value.replace(',', '.') : value);
            }
        }
        yearsProblem(keptFields).ifPresent(problems::add);

        Map<Activity.ReferenceField, String> keptReferences = new EnumMap<>(Activity.ReferenceField.class);
        List<Activity.ReferenceField> allowed = type == null || type.getReferenceFields() == null
                ? List.of() : type.getReferenceFields();
        if (references != null) {
            for (Map.Entry<Activity.ReferenceField, String> entry : references.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (value.isEmpty() || entry.getKey() == null) {
                    continue;
                }
                if (!allowed.contains(entry.getKey()) || value.length() > REFERENCE_MAX) {
                    problems.add(entry.getKey().name() + ": «" + abbreviate(value) + "»");
                    continue;
                }
                keptReferences.put(entry.getKey(), value);
            }
        }
        List<Activity.ReferenceField> named = keptReferences.keySet().stream().filter(ENTITY_REFERENCES::contains).toList();
        if (named.size() > 1) {
            problems.add(String.join(" + ", named.stream().map(Enum::name).toList()));
        }
        return new Result(keptFields, keptReferences, List.copyOf(problems));
    }

    /** The problem with one value of a field ("Rol: «x»"), or null when it may be stored. */
    public static String problem(Activity.Field field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String refused = field.getName() + ": «" + abbreviate(value) + "»";
        if (field.isNumber()) {
            if (!PLAIN_NUMBER.matcher(value).matches()) {
                return refused;
            }
            double number = Double.parseDouble(value.replace(',', '.'));
            Range range = rangeFor(field.getName());
            if (!Double.isFinite(number) || number < range.min() || number > range.max()
                    || (range.integer() && number != Math.rint(number))) {
                return refused;
            }
            return null;
        }
        if (field.getAllowedValues() != null && !field.getAllowedValues().isEmpty()) {
            return field.getAllowedValues().contains(value) ? null : refused;
        }
        return value.length() > TEXT_MAX ? refused : null;
    }

    /** The range of a number field, by its name: years, counts, minutes, amounts; any other number is 0..10⁹. */
    static Range rangeFor(String name) {
        int thisYear = Year.now().getValue();
        return switch (name) {
            case "An_inceput", "An_sfarsit" -> new Range(FIRST_YEAR, thisYear + 10, true);
            case "An_creatie" -> new Range(1900, thisYear, true);
            case "N_ani" -> new Range(1, 60, true);
            case "N_numere_speciale" -> new Range(1, 20, true);
            case "N_articole" -> new Range(1, 200, true);
            case "Durata_minute" -> new Range(1, 1440, true);
            case "Buget" -> new Range(0, 1_000_000_000, false);
            case "Citari_GS", "Citari_WoS" -> new Range(0, 1_000_000, true);
            case "h_GS" -> new Range(0, 300, true);
            default -> name.startsWith("N_") || "Marime_formatie".equals(name)
                    ? new Range(1, 100_000, true)
                    : new Range(0, 1_000_000_000, false);
        };
    }

    /** An end year before the start year is refused. */
    private static java.util.Optional<String> yearsProblem(Map<String, String> fields) {
        String start = fields.get("An_inceput");
        String end = fields.get("An_sfarsit");
        if (start != null && end != null && Double.parseDouble(end) < Double.parseDouble(start)) {
            return java.util.Optional.of("An_inceput–An_sfarsit: «" + start + "–" + end + "»");
        }
        return java.util.Optional.empty();
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "…";
    }
}
