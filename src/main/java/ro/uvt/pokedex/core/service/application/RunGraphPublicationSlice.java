package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H106 S3 — the slice of a publication view that a persisted run graph keeps for the export.
 * <p>
 * The run-mode citation result used to store only scores, so the xlsx export could not fill the cited
 * work's forum/year nor the citing rows' authors/forum/volume/year ("Title (, )"). Storing the full
 * {@link ScholardexPublicationView} the way the apply-page cache does costs ~250 KB per result (abstracts,
 * lineage, affiliations) and runs accumulate per refresh, so the graph keeps just the fields the
 * {@code RunIndicatorSnapshotProjector} reads: ids for author/forum name resolution at export time, plus
 * the printable scalars. {@code doi} rides along for the DOI-link slice (S4).
 */
public final class RunGraphPublicationSlice {

    private RunGraphPublicationSlice() {
    }

    public static Map<String, Object> of(ScholardexPublicationView p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) return m;
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("doi", p.getDoi());
        m.put("authors", p.getAuthors() != null ? new ArrayList<>(p.getAuthors()) : List.of());
        m.put("forum", p.getForum());
        m.put("volume", p.getVolume());
        m.put("coverDate", p.getCoverDate());
        m.put("authorCount", p.getAuthorCount());
        return m;
    }

    public static List<Map<String, Object>> ofAll(List<ScholardexPublicationView> publications) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (publications == null) return out;
        for (ScholardexPublicationView p : publications) {
            if (p != null && p.getTitle() != null) out.add(of(p));
        }
        return out;
    }

    /** Citing-title → slice, preserving the computation's insertion order. */
    public static Map<String, Map<String, Object>> ofMap(Map<String, ScholardexPublicationView> byTitle) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (byTitle == null) return out;
        byTitle.forEach((title, view) -> {
            if (title != null && view != null) out.put(title, of(view));
        });
        return out;
    }
}
