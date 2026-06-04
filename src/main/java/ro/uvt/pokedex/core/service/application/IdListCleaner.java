package ro.uvt.pokedex.core.service.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Drops null/blank entries and dedupes while preserving insertion order. Shared between
 * surfaces that bind {@code List<String>} of ids/emails from forms (heads, supervisors,
 * department ids, etc.) so blank rows from repeat-row UIs never leak into persisted state.
 */
public final class IdListCleaner {
    private IdListCleaner() {}

    public static List<String> clean(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank() && !"undefined".equals(id) && !"null".equals(id)) seen.add(id);
        }
        return new ArrayList<>(seen);
    }
}
