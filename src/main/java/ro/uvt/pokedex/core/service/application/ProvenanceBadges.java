package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.ProvenanceBadge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives the provenance/indexing badges shown on public detail pages. Pure functions of the already-projected
 * read model — no I/O. The Web of Science badges are marked {@code requiresAuth} (licensing), so the fragment
 * hides them from anonymous visitors, consistent with the rest of the WoS gating on the public UI.
 */
public final class ProvenanceBadges {

    /** WoS Core Collection editions that count as "in Web of Science" for a venue. */
    private static final Set<String> WOS_EDITIONS = Set.of("SCIE", "ESCI", "SSCI", "AHCI");

    private ProvenanceBadges() {
    }

    /**
     * Publication-level provenance from its source ids + open-access flag. Publications carry Scopus (eid),
     * Web of Science (wosId) and Google Scholar ids, plus an open-access flag; OpenAlex/DBLP are venue-level,
     * not on the publication.
     */
    public static List<ProvenanceBadge> forPublication(ScholardexPublicationView pub) {
        List<ProvenanceBadge> badges = new ArrayList<>();
        if (pub == null) {
            return badges;
        }
        if (isPresent(pub.getEid())) {
            badges.add(ProvenanceBadge.source("Scopus", "Indexed in Scopus"));
        }
        if (isPresent(pub.getWosId())) {
            badges.add(ProvenanceBadge.gatedSource("WoS", "Indexed in Web of Science"));
        }
        if (isPresent(pub.getGoogleScholarId())) {
            badges.add(ProvenanceBadge.source("Google Scholar", "Found in Google Scholar"));
        }
        if (pub.isOpenAccess()) {
            String label = isPresent(pub.getFreetoreadLabel()) ? pub.getFreetoreadLabel() : null;
            badges.add(ProvenanceBadge.openAccess(label != null ? "Open access — " + label : "Open access"));
        }
        return badges;
    }

    /**
     * Forum (venue) provenance from its normalized membership snapshot ({@code forum_membership_view}: database ∈
     * SCOPUS / OPENALEX / SCIE·ESCI·SSCI·AHCI / DOAJ / ERIH …) plus the DOAJ {@code apc} flag. DOAJ membership
     * implies the venue is open access. DBLP (conference-series) is not in the membership snapshot and is not
     * badged yet.
     *
     * @param databases uppercase database codes the forum is currently a member of
     * @param apc       whether the venue charges an article-processing charge (DOAJ)
     */
    public static List<ProvenanceBadge> forForum(Set<String> databases, boolean apc) {
        List<ProvenanceBadge> badges = new ArrayList<>();
        if (databases == null || databases.isEmpty()) {
            return badges;
        }
        if (databases.contains("SCOPUS")) {
            badges.add(ProvenanceBadge.source("Scopus", "Indexed in Scopus"));
        }
        Set<String> wosEditions = new TreeSet<>();
        for (String edition : WOS_EDITIONS) {
            if (databases.contains(edition)) {
                wosEditions.add(edition);
            }
        }
        if (!wosEditions.isEmpty()) {
            badges.add(ProvenanceBadge.gatedSource("WoS", "Web of Science — " + String.join(", ", wosEditions)));
        }
        if (databases.contains("OPENALEX")) {
            badges.add(ProvenanceBadge.source("OpenAlex", "Indexed in OpenAlex"));
        }
        if (databases.contains("DBLP")) {
            badges.add(ProvenanceBadge.source("DBLP", "Indexed in the DBLP computer science bibliography"));
        }
        boolean inDoaj = databases.contains("DOAJ");
        if (inDoaj) {
            badges.add(ProvenanceBadge.source("DOAJ", "Listed in the Directory of Open Access Journals"));
        }
        if (databases.contains("ERIH")) {
            badges.add(ProvenanceBadge.source("ERIH PLUS", "Listed in ERIH PLUS"));
        }
        if (inDoaj) {
            badges.add(ProvenanceBadge.openAccess("Open access journal (DOAJ)"));
            if (apc) {
                badges.add(ProvenanceBadge.apc("Charges an article-processing charge"));
            }
        }
        return badges;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
