package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityChange;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H142 slice 7 — the history an activity record keeps of what its owner changed (Adrian, 2026-10-03: a correction to
 * the faculty's submitted records applies at once, and is kept): each value that changed, old → new, and a move to
 * another type with the values the new type had no field for.
 */
final class ActivityChangeLog {

    static final String EDITED = "EDITED";
    static final String MOVED = "MOVED";
    /** The most entries a record keeps; the oldest go first. */
    static final int MAX_ENTRIES = 50;
    private static final int VALUE_MAX = 80;

    private ActivityChangeLog() {
    }

    /** Logs the values that differ between {@code beforeFields}/{@code beforeReferences} and the record now; nothing when none does. */
    static void edited(ActivityInstance record, String by, Map<String, String> beforeFields,
                       Map<Activity.ReferenceField, String> beforeReferences) {
        List<String> changes = new ArrayList<>();
        diff(changes, beforeFields, record.getFields());
        diff(changes, names(beforeReferences), names(record.getReferenceFields()));
        if (!changes.isEmpty()) {
            add(record, new ActivityChange(Instant.now(), by, EDITED, null, null, String.join("; ", changes)));
        }
    }

    static void moved(ActivityInstance record, String by, String fromType, String toType, List<String> dropped) {
        add(record, new ActivityChange(Instant.now(), by, MOVED, fromType, toType,
                dropped.isEmpty() ? null : String.join("; ", dropped.stream().map(ActivityChangeLog::abbreviate).toList())));
    }

    /** A copy of the values, to compare with after a change. */
    static Map<String, String> copy(Map<String, String> values) {
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    static Map<Activity.ReferenceField, String> copyReferences(Map<Activity.ReferenceField, String> values) {
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    private static void diff(List<String> out, Map<String, String> before, Map<String, String> after) {
        Map<String, String> b = before == null ? Map.of() : before;
        Map<String, String> a = after == null ? Map.of() : after;
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        for (String key : keys) {
            String old = blankToNull(b.get(key));
            String now = blankToNull(a.get(key));
            if (old == null ? now != null : !old.equals(now)) {
                out.add(key + ": " + shown(old) + " → " + shown(now));
            }
        }
    }

    private static Map<String, String> names(Map<Activity.ReferenceField, String> references) {
        Map<String, String> out = new LinkedHashMap<>();
        if (references != null) {
            references.forEach((k, v) -> { if (k != null) out.put(k.name(), v); });
        }
        return out;
    }

    private static void add(ActivityInstance record, ActivityChange change) {
        List<ActivityChange> log = record.getChanges() == null ? new ArrayList<>() : new ArrayList<>(record.getChanges());
        log.add(change);
        while (log.size() > MAX_ENTRIES) {
            log.removeFirst();
        }
        record.setChanges(log);
    }

    private static String shown(String value) {
        return value == null ? "—" : "«" + abbreviate(value) + "»";
    }

    private static String abbreviate(String value) {
        String v = value.trim().replaceAll("\\s+", " ");
        return v.length() <= VALUE_MAX ? v : v.substring(0, VALUE_MAX - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
