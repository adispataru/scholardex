package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryKind;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H144 — the variables a declared activity's formula reads instead of a level the researcher picked. From the entities
 * the record names, through the registries experts rank ({@link RegistrySupport}): {@code Entitate_numita},
 * {@code Nivel_entitate} (INTERNATIONAL, NATIONAL, LOCAL or null), {@code International}, {@code Recunoscut} (national
 * or international), {@code Premiu_stiintific}, {@code In_strainatate}. From the app's own lists:
 * {@code Universitate_numita}, {@code Top500_URAP}, {@code Top1000_mondial} (QS, THE or Shanghai), {@code Tip_brevet} (TRIADIC, EUROPEAN, INTERNATIONAL, NATIONAL —
 * CNFIS's definitions, from the patent codes and offices), and for a journal named by ISSN {@code Revista_cu_taxa}
 * (null when the journal is unknown), {@code Revista_WoS} (SCIE, SSCI or AHCI), {@code Revista_WoS_CC} (the Core Collection,
 * ESCI included), {@code Revista_Scopus}, {@code N_baze_date} and {@code IF_revista} (its impact factor). Where no list
 * decides, {@code Incadrare_aprobata}: the option a head approved (H143's request flow).
 *
 * <p>Bound for every activity, so a formula never meets an unknown name. The lookups beyond the registries are
 * registered at startup ({@link RegistryScoringLookups}); unregistered (unit tests) they find nothing.</p>
 */
public final class RegistryScoringSupport {

    /** The app's lists the variables read: university rankings and journals. */
    public interface Lookups {
        Optional<Integer> urapRank(String university, int year);

        /** The best position among QS, THE and Shanghai (ARWU), URAP left out. */
        default Optional<Integer> worldRank(String university, int year) {
            return Optional.empty();
        }

        Optional<String> universityCountry(String university);

        Optional<JournalFacts> journal(String issn, int year);
    }

    /**
     * What the app knows of a journal: whether it conditions publication on a fee (DOAJ), whether Web of Science indexes
     * it in SCIE, SSCI or AHCI ({@code webOfScience}) or in its Core Collection with ESCI ({@code webOfScienceCore}),
     * whether Scopus does, in how many databases it is indexed, and its impact factor of the year (null without one).
     */
    public record JournalFacts(boolean feeJournal, boolean webOfScience, boolean webOfScienceCore, boolean scopus,
                               int databases, Double impactFactor) {
    }

    public static final int URAP_TOP = 500;
    public static final int WORLD_TOP = 1000;
    static final String FIELD_PATENT_CODE = "Cod brevet";
    static final String FIELD_PATENT_OFFICE = "Oficiu";

    private static final Lookups NONE = new Lookups() {
        @Override
        public Optional<Integer> urapRank(String university, int year) {
            return Optional.empty();
        }

        @Override
        public Optional<String> universityCountry(String university) {
            return Optional.empty();
        }

        @Override
        public Optional<JournalFacts> journal(String issn, int year) {
            return Optional.empty();
        }
    };
    private static volatile Lookups lookups = NONE;

    /** A patent code: an office prefix and a number ("EP 1234567", "US9,876,543", "RO 123456 B1", "WO2019/123456"). */
    private static final Pattern PATENT_CODE = Pattern.compile("(?<![A-Z])([A-Z]{2})\\s*[-.]?\\s*\\d");
    private static final Set<String> ROMANIA = Set.of("romania", "ro", "rou");
    /**
     * The offices whose two-letter codes open a patent number (WIPO ST.3) — a closed list, so that "nr. 123" or
     * "No. 123" never reads as an office.
     */
    private static final Set<String> OFFICES = Set.of("RO", "EP", "WO", "US", "JP", "DE", "FR", "GB", "CN", "KR", "CA",
            "AU", "IT", "ES", "CH", "AT", "NL", "SE", "FI", "DK", "PL", "CZ", "HU", "BG", "MD", "UA", "RU", "IN", "BR",
            "MX", "IL", "SG", "TW", "HK", "NZ", "ZA", "BE", "PT", "GR", "IE", "LU", "SK", "SI", "HR", "RS", "TR", "EA",
            "AP", "OA");

    private RegistryScoringSupport() {
    }

    public static void register(Lookups newLookups) {
        lookups = newLookups == null ? NONE : newLookups;
    }

    public static void reset() {
        lookups = NONE;
    }

    /** What the app knows of the journal with this ISSN in that year (the workspace shows it beside the record). */
    public static Optional<JournalFacts> journal(String issn, int year) {
        String canonical = canonicalIssn(issn);
        return canonical == null ? Optional.empty() : lookups.journal(canonical, year);
    }

    /** What the rankings say of a university: its URAP position, its best QS/THE/Shanghai position, its country. */
    public record UniversityFacts(Integer urapRank, Integer worldRank, String country) {
    }

    /** The university's facts as the scoring reads them (the workspace shows them beside the record). */
    public static UniversityFacts university(String name, int year) {
        if (name == null || name.isBlank()) {
            return new UniversityFacts(null, null, null);
        }
        String n = name.trim();
        return new UniversityFacts(lookups.urapRank(n, year).orElse(null), lookups.worldRank(n, year).orElse(null),
                lookups.universityCountry(n).orElse(null));
    }

    /** An ISSN as the lists write it (NNNN-NNNN, the check digit upper-cased), or null when it is not one. */
    static String canonicalIssn(String typed) {
        if (typed == null) return null;
        String compact = typed.replaceAll("[^0-9xX]", "").toUpperCase(Locale.ROOT);
        return compact.length() == 8 ? compact.substring(0, 4) + "-" + compact.substring(4) : null;
    }

    /** Binds the variables for one record. */
    public static void bind(ActivityInstance activity, Map<String, Object> variables) {
        Map<Activity.ReferenceField, String> refs = activity.getReferenceFields() == null ? Map.of() : activity.getReferenceFields();
        Map<String, String> fields = activity.getFields() == null ? Map.of() : activity.getFields();
        Integer year = activity.getYear();

        String best = null;
        boolean named = false, scientificAward = false, abroad = false;
        for (RegistryKind kind : RegistryKind.values()) {
            String name = refs.get(kind.referenceField());
            if (name == null || name.isBlank()) {
                continue;
            }
            named = true;
            String level = RegistrySupport.scoringLevel(kind, name);
            best = higher(best, level);
            Optional<RegistrySupport.Ranked> ranked = RegistrySupport.ranked(kind, name);
            if (kind == RegistryKind.AWARD && ranked.map(r -> "SCIENTIFIC".equals(r.category())).orElse(false)) {
                scientificAward = true;
            }
            if (ranked.map(r -> isAbroad(r.country())).orElse(false)) {
                abroad = true;
            }
        }
        String university = refs.get(Activity.ReferenceField.UNIVERSITY_NAME);
        boolean universityNamed = university != null && !university.isBlank();
        boolean top500 = false, top1000 = false;
        if (universityNamed) {
            named = true;
            top500 = lookups.urapRank(university.trim(), year == null ? 0 : year).map(r -> r > 0 && r <= URAP_TOP).orElse(false);
            top1000 = lookups.worldRank(university.trim(), year == null ? 0 : year).map(r -> r > 0 && r <= WORLD_TOP).orElse(false);
            if (lookups.universityCountry(university.trim()).map(RegistryScoringSupport::isAbroad).orElse(false)) {
                abroad = true;
            }
        }
        variables.put("Entitate_numita", named);
        variables.put("Nivel_entitate", best);
        variables.put("International", "INTERNATIONAL".equals(best));
        variables.put("Recunoscut", "INTERNATIONAL".equals(best) || "NATIONAL".equals(best));
        variables.put("Premiu_stiintific", scientificAward);
        variables.put("In_strainatate", abroad);
        variables.put("Universitate_numita", universityNamed);
        variables.put("Top500_URAP", top500);
        variables.put("Top1000_mondial", top1000);
        variables.put("Tip_brevet", patentType(fields.get(FIELD_PATENT_CODE), fields.get(FIELD_PATENT_OFFICE)));

        Optional<JournalFacts> journal = journal(refs.get(Activity.ReferenceField.FORUM_ISSN), year == null ? 0 : year);
        variables.put("Incadrare_aprobata", approvedRequest(activity, fields));
        variables.put("Revista_cu_taxa", journal.map(JournalFacts::feeJournal).orElse(null));
        variables.put("Revista_WoS", journal.map(JournalFacts::webOfScience).orElse(false));
        variables.put("Revista_WoS_CC", journal.map(JournalFacts::webOfScienceCore).orElse(false));
        variables.put("Revista_Scopus", journal.map(JournalFacts::scopus).orElse(false));
        variables.put("N_baze_date", journal.map(JournalFacts::databases).orElse(0));
        if (journal.isPresent()) {
            // the journal's impact factor comes from the data, never typed: only for a record that names its journal
            variables.put("IF_revista", journal.get().impactFactor());
        }
    }

    /**
     * The option a head approved for this record (H143's requests, used beyond publishers since H144), while the record
     * still asks for it; null otherwise.
     */
    static String approvedRequest(ActivityInstance activity, Map<String, String> fields) {
        var claim = activity.getPublisherClaim();
        String asked = fields.get(PublisherRules.FIELD_CLAIM);
        if (claim == null || claim.getStatus() != ro.uvt.pokedex.core.model.activities.PublisherClaim.Status.APPROVED
                || asked == null || claim.getRequested() == null || !asked.trim().equals(claim.getRequested().trim())) {
            return null;
        }
        return claim.getRequested().trim();
    }

    /**
     * CNFIS's patent kinds, from the codes and the office a record gives: triadic when the European, US and Japanese
     * offices all granted it; else European (EPO), international (WIPO, or any office abroad), national (OSIM). Null
     * when nothing names an office.
     */
    public static String patentType(String codes, String office) {
        Set<String> offices = new java.util.HashSet<>();
        String text = ((codes == null ? "" : codes) + " " + (office == null ? "" : office)).toUpperCase(Locale.ROOT);
        Matcher m = PATENT_CODE.matcher(text);
        while (m.find()) {
            if (OFFICES.contains(m.group(1))) {
                offices.add(m.group(1));
            }
        }
        String words = fold(text);
        if (words.contains("osim")) offices.add("RO");
        if (words.contains("epo") || words.contains("oeb") || words.contains("european patent")) offices.add("EP");
        if (words.contains("wipo") || words.contains("ompi") || words.contains("pct")) offices.add("WO");
        if (words.contains("uspto")) offices.add("US");
        if (words.contains("jpo")) offices.add("JP");
        if (offices.isEmpty()) {
            return null;
        }
        if (offices.containsAll(Set.of("EP", "US", "JP"))) {
            return "TRIADIC";
        }
        if (offices.contains("EP")) {
            return "EUROPEAN";
        }
        if (offices.stream().anyMatch(o -> !"RO".equals(o))) {
            return "INTERNATIONAL";
        }
        return "NATIONAL";
    }

    private static String higher(String a, String b) {
        return rank(b) < rank(a) ? b : a;
    }

    private static int rank(String level) {
        if ("INTERNATIONAL".equals(level)) return 0;
        if ("NATIONAL".equals(level)) return 1;
        if ("LOCAL".equals(level)) return 2;
        return 3;
    }

    static boolean isAbroad(String country) {
        if (country == null || country.isBlank()) {
            return false;
        }
        return !ROMANIA.contains(fold(country).trim());
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
