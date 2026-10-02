package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H143 — how a standard classifies the publisher of a book the researcher declares as an activity. The category comes
 * from the lists the standard names, matched against the publisher the researcher TYPES ({@value #FIELD_PUBLISHER}),
 * never from a category they pick. Where a standard also accepts something no list holds — holdings in WorldCat
 * libraries, the complementary route of Comisia 28, a foreign publisher equivalent to CNCS A or B — the researcher may
 * ask for a category ({@value #FIELD_CLAIM}, with {@value #FIELD_CLAIM_EVIDENCE}); it counts once a head of their
 * department or faculty has approved it. Formulas read the result as {@value #VARIABLE}: the best of the listed and
 * the approved category, or null when neither counts.
 *
 * <ul>
 *   <li><b>Comisia 25 (Sociologie)</b>, definition [4]: the A2 list of the annex, else A1 for a house of international
 *       prestige — on the WoS Master Book List or on an international list or ranking (SENSE A and B, the UEFISCDI
 *       lists: {@link InternationalPublisherSupport}), the stand-ins for "Lista A1, în vigoare", as for corpus books;
 *       a book held by at least six WorldCat libraries counts as A2 (a claim), and so does one published before the
 *       current list at a house of the earlier list (a claim: the platform does not hold that list);</li>
 *   <li><b>Comisia 28 (Psihologie, Științe ale educației)</b>: the domain's 2026 list (A2, B), else A1 for a house of
 *       international prestige, as above (indicative, as for corpus books); the per-book routes (A1 by 25 EU/OECD
 *       university libraries in WorldCat, A2 or B by the complementary route) are claims, and may raise a listed
 *       category;</li>
 *   <li><b>Comisia 35 (Muzică)</b>: "publicat" means a publisher CNCS classifies A or B — its best category in the
 *       Music domain of the 2020 and 2026 lists, else its best in any domain of the 2013, 2020 and 2026 lists (the
 *       standard names no list year) — or an equivalent foreign one: a foreign house on the WoS Master Book List or on
 *       an international list or ranking (Lambert Academic Publishing excluded, as UEFISCDI excludes it); else a
 *       claim.</li>
 * </ul>
 */
public enum PublisherRules {

    SOCIOLOGIE_2026("Sociologie", List.of("A1", "A2")),
    PSIHOLOGIE_2026("Psihologie", List.of("A1", "A2", "B")),
    STIINTE_EDUCATIEI_2026("Științe ale educației", List.of("A1", "A2", "B")),
    MUZICA_2026("Muzică", List.of("A", "B", "STRAINA"));

    /** The publisher as the researcher types it. */
    public static final String FIELD_PUBLISHER = "Editura";
    /** The category the researcher asks for, where no list decides (one of {@link #CLAIM_OPTIONS}). */
    public static final String FIELD_CLAIM = "Incadrare_solicitata";
    /** What shows it: a WorldCat link, the print run, the citations, the publisher's page. */
    public static final String FIELD_CLAIM_EVIDENCE = "Dovada_incadrarii";
    /** The formula variable: the category that counts, or null. */
    public static final String VARIABLE = "Categorie_editura";
    /** The foreign-publisher category of Comisia 35. */
    public static final String FOREIGN = "STRAINA";

    /**
     * Comisia 25 also counts a book published before the current list at a house of the earlier one ("lista de edituri
     * din Anexa 2"), a list the 2026 annex does not reproduce and the platform does not hold: a head decides it.
     */
    private static final Map<String, String> SOCIOLOGY_OPTIONS = Map.of(
            "Editură de prestigiu internațional (Lista A1)", "A1",
            "Minimum 6 biblioteci în WorldCat (asimilat Listei A2)", "A2",
            "Editură de pe lista anterioară (Anexa 2), carte apărută înaintea listei actuale", "A2");
    private static final Map<String, String> COMISIA_28_OPTIONS = Map.of(
            "A1 — minimum 25 de biblioteci universitare din UE/OCDE în WorldCat", "A1",
            "A2 — cel puțin două criterii din ruta complementară", "A2",
            "B — un criteriu din ruta complementară", "B",
            "Editură de prestigiu internațional (A1)", "A1");
    private static final Map<String, String> MUSIC_OPTIONS = Map.of(
            "Editură străină echivalentă (categoria A sau B)", "STRAINA");

    /** Every option an activity type may offer in {@value #FIELD_CLAIM}, and the category it asks for. */
    public static final Map<String, String> CLAIM_OPTIONS;

    static {
        Map<String, String> options = new LinkedHashMap<>();
        options.putAll(SOCIOLOGY_OPTIONS);
        options.putAll(COMISIA_28_OPTIONS);
        options.putAll(MUSIC_OPTIONS);
        CLAIM_OPTIONS = Map.copyOf(options);
    }

    private final String label;
    /** The categories that count, best first. */
    private final List<String> categories;

    PublisherRules(String label, List<String> categories) {
        this.label = label;
        this.categories = categories;
    }

    public String label() {
        return label;
    }

    /** The categories that count, best first. */
    public List<String> categories() {
        return categories;
    }

    public boolean counts(String category) {
        return category != null && categories.contains(category);
    }

    /** The options these rules accept in {@value #FIELD_CLAIM}, and the category each asks for. */
    public Map<String, String> claimOptions() {
        return switch (this) {
            case SOCIOLOGIE_2026 -> SOCIOLOGY_OPTIONS;
            case PSIHOLOGIE_2026, STIINTE_EDUCATIEI_2026 -> COMISIA_28_OPTIONS;
            case MUZICA_2026 -> MUSIC_OPTIONS;
        };
    }

    /** The category a claim option asks for under these rules, or empty when the option is not one of theirs. */
    public Optional<String> claimCategory(String option) {
        String category = option == null ? null : claimOptions().get(option.trim());
        return counts(category) ? Optional.of(category) : Optional.empty();
    }

    /** The better of two categories (null when neither counts). */
    public String best(String a, String b) {
        boolean aCounts = counts(a), bCounts = counts(b);
        if (!aCounts) return bCounts ? b : null;
        if (!bCounts) return a;
        return categories.indexOf(a) <= categories.indexOf(b) ? a : b;
    }

    /** The rules an indicator scores declared books with, through its 2026 flag. */
    public static Optional<PublisherRules> of(Indicator indicator) {
        if (indicator == null) {
            return Optional.empty();
        }
        if (indicator.usesSociologie2026()) return Optional.of(SOCIOLOGIE_2026);
        if (indicator.usesPsihologie2026()) return Optional.of(PSIHOLOGIE_2026);
        if (indicator.usesStiinteEducatiei2026()) return Optional.of(STIINTE_EDUCATIEI_2026);
        if (indicator.usesMuzica2026()) return Optional.of(MUZICA_2026);
        return Optional.empty();
    }
}
