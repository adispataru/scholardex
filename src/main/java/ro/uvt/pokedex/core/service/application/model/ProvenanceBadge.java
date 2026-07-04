package ro.uvt.pokedex.core.service.application.model;

/**
 * A single provenance/indexing badge shown on a public detail page (publication or forum) — e.g. "Scopus",
 * "OpenAlex", "WoS", "Open Access", "APC". Derivation lives in {@code ProvenanceBadges}; rendering in the
 * {@code provenance-badges} Thymeleaf fragment.
 *
 * @param label        short pill text (e.g. "Scopus")
 * @param tone         visual tone → CSS modifier: "neutral" (source indexing), "success" (open access), "warning" (APC/fee)
 * @param icon         Font Awesome class (e.g. "fa-solid fa-database"), or null for no icon
 * @param tooltip      hover title with detail (e.g. the WoS editions), or null
 * @param requiresAuth true for login-gated signals (Web of Science — licensing); the fragment hides these from anonymous visitors
 */
public record ProvenanceBadge(
        String label,
        String tone,
        String icon,
        String tooltip,
        boolean requiresAuth
) {
    public static ProvenanceBadge source(String label, String tooltip) {
        return new ProvenanceBadge(label, "neutral", "fa-solid fa-database", tooltip, false);
    }

    public static ProvenanceBadge gatedSource(String label, String tooltip) {
        return new ProvenanceBadge(label, "neutral", "fa-solid fa-database", tooltip, true);
    }

    public static ProvenanceBadge openAccess(String tooltip) {
        return new ProvenanceBadge("Open Access", "success", "fa-solid fa-unlock", tooltip, false);
    }

    public static ProvenanceBadge apc(String tooltip) {
        return new ProvenanceBadge("APC", "warning", "fa-solid fa-money-bill-wave", tooltip, false);
    }
}
