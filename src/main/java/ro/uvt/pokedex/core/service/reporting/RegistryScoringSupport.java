package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.registry.RegistryKind;
import ro.uvt.pokedex.core.model.reporting.Indicator;

import java.text.Normalizer;
import java.util.List;
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
                               Set<String> databases, Double impactFactor, String country) {
        public JournalFacts {
            databases = databases == null ? Set.of() : Set.copyOf(databases);
        }

        public JournalFacts(boolean feeJournal, boolean webOfScience, boolean webOfScienceCore, boolean scopus,
                            Set<String> databases, Double impactFactor) {
            this(feeJournal, webOfScience, webOfScienceCore, scopus, databases, impactFactor, null);
        }
    }

    /** The field of a declared publication that states its language (a fact, H145). */
    static final String FIELD_LANGUAGE = "Limba";
    /** The languages of international circulation of Comisia 25's definition [6], as the field offers them. */
    static final Set<String> INTERNATIONAL_LANGUAGES = Set.of("Engleză", "Franceză", "Germană", "Italiană", "Spaniolă");

    /** Web of Science editions: one database however many of them index a journal (H145). */
    static final Set<String> WOS_EDITIONS = Set.of("SCIE", "SSCI", "AHCI", "ESCI");
    /** What counts besides Web of Science where a standard names no list of its own (Comisia 25 and the rest). */
    static final Set<String> RECOGNISED_DATABASES = Set.of("SCOPUS", "ERIH", "DOAJ");
    /** The candidate's own university, which a "visit to another institution" never names (H145). */
    private static final Set<String> OWN_UNIVERSITY = Set.of("west university of timisoara",
            "universitatea de vest din timisoara", "universitatea de vest timisoara", "uvt");

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

    /**
     * A patent number as patent documents write it: an upper-case office code, then at least five digits (or a year and
     * a serial, or groups of thousands), then optionally the kind code ("EP 1234567 B1", "US 9,876,543 B2",
     * "RO 123456 B1", "WO 2019/123456"). H145: case-sensitive — "data de 15.06.2021" or "acordat in 2020" are prose, not
     * German or Indian patents — and never inside another code ("PCT/RO2019/…" is an application).
     */
    private static final Pattern PATENT_CODE = Pattern.compile(
            "(?<![A-Za-z0-9/])([A-Z]{2})[ \\-.]?(\\d{4}[/-]\\d{5,7}|\\d{1,3}(?:[,.]\\d{3}){1,3}|\\d{5,})(?:\\s*([ABCUY]\\d?))?(?![A-Za-z0-9])");
    /** An office named in words, as a whole word ("depozit" is not EPO, "compilatie" is not OMPI). */
    private static final Pattern OFFICE_WORD = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(OSIM|EPO|OEB|WIPO|OMPI|USPTO|JPO|European Patent Office)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
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

    /** Binds the variables for one record, counting a journal's databases as the general rule does. */
    public static void bind(ActivityInstance activity, Map<String, Object> variables) {
        bind(activity, variables, null);
    }

    /**
     * Binds the variables for one record under an indicator's standard (it decides which databases count).
     *
     * <p>H145: only the references the record's type declares are read, and a record names one entity — a second name
     * never lifts the first. A level comes only from the experts' ranking: a name waiting for them, or one they
     * rejected, has none ({@code Entitate_valida} tells a waiting name from a rejected one, for a standard that counts
     * a waiting name at a level of its own, as Comisia 28 counts a conference national). A university counts once the
     * rankings know it, never the candidate's own.</p>
     */
    public static void bind(ActivityInstance activity, Map<String, Object> variables, Indicator indicator) {
        Map<Activity.ReferenceField, String> refs = activity.getReferenceFields() == null ? Map.of() : activity.getReferenceFields();
        Map<String, String> fields = activity.getFields() == null ? Map.of() : activity.getFields();
        Integer year = activity.getYear();
        List<Activity.ReferenceField> declared = activity.getActivity() == null || activity.getActivity().getReferenceFields() == null
                ? List.of() : activity.getActivity().getReferenceFields();

        List<RegistryKind> namedKinds = new java.util.ArrayList<>();
        for (RegistryKind kind : RegistryKind.values()) {
            String name = declared.contains(kind.referenceField()) ? refs.get(kind.referenceField()) : null;
            if (name != null && !name.isBlank()) {
                namedKinds.add(kind);
            }
        }
        String university = declared.contains(Activity.ReferenceField.UNIVERSITY_NAME)
                ? refs.get(Activity.ReferenceField.UNIVERSITY_NAME) : null;
        boolean universityGiven = university != null && !university.isBlank();
        int entities = namedKinds.size() + (universityGiven ? 1 : 0);

        String level = null;
        boolean valid = false, scientificAward = false, stateAward = false, competition = false, abroad = false;
        boolean peerReview = false;
        if (entities == 1 && !namedKinds.isEmpty()) {
            RegistryKind kind = namedKinds.getFirst();
            String name = refs.get(kind.referenceField());
            Optional<RegistrySupport.EntryStatus> status = RegistrySupport.statusOf(kind, name);
            valid = status.isPresent() && status.get() != RegistrySupport.EntryStatus.REJECTED;
            level = RegistrySupport.scoringLevel(kind, name);
            Optional<RegistrySupport.Ranked> ranked = RegistrySupport.ranked(kind, name);
            scientificAward = kind == RegistryKind.AWARD && ranked.map(r -> "SCIENTIFIC".equals(r.category())).orElse(false);
            stateAward = kind == RegistryKind.AWARD && ranked.map(r -> "STATE".equals(r.category())).orElse(false);
            competition = kind == RegistryKind.ARTISTIC_EVENT && ranked.map(r -> "COMPETITION".equals(r.category())).orElse(false);
            abroad = ranked.map(r -> isAbroad(r.country())).orElse(false);
            peerReview = kind == RegistryKind.SCIENTIFIC_EVENT && ranked.map(r -> r.criteria() != null
                    && r.criteria().contains(RegistryKind.PEER_REVIEW)).orElse(false);
        }
        boolean universityKnown = false, ownUniversity = false, top500 = false, top1000 = false;
        if (entities == 1 && universityGiven) {
            UniversityFacts facts = university(university, year == null ? 0 : year);
            ownUniversity = isOwnUniversity(university);
            universityKnown = !ownUniversity && (facts.urapRank() != null || facts.worldRank() != null || facts.country() != null);
            if (universityKnown) {
                valid = true;
                top500 = facts.urapRank() != null && facts.urapRank() > 0 && facts.urapRank() <= URAP_TOP;
                top1000 = facts.worldRank() != null && facts.worldRank() > 0 && facts.worldRank() <= WORLD_TOP;
                abroad = isAbroad(facts.country());
            }
        }
        variables.put("Entitate_numita", entities > 0);
        variables.put("Entitate_valida", valid);
        variables.put("Nivel_entitate", level);
        variables.put("International", "INTERNATIONAL".equals(level));
        variables.put("Recunoscut", "INTERNATIONAL".equals(level) || "NATIONAL".equals(level));
        variables.put("Premiu_stiintific", scientificAward);
        variables.put("Premiu_de_stat", stateAward);
        variables.put("Concurs", competition);
        variables.put("Comitet_selectie", peerReview);
        variables.put("In_strainatate", abroad);
        variables.put("Universitate_numita", universityKnown);
        variables.put("Universitate_proprie", ownUniversity);
        variables.put("Top500_URAP", top500);
        variables.put("Top1000_mondial", top1000);
        variables.put("Tip_brevet", patentType(fields.get(FIELD_PATENT_CODE), fields.get(FIELD_PATENT_OFFICE)));

        Optional<JournalFacts> journal = journal(refs.get(Activity.ReferenceField.FORUM_ISSN), year == null ? 0 : year);
        variables.put("Incadrare_aprobata", approvedRequest(activity, fields));
        variables.put("Revista_cu_taxa", journal.map(JournalFacts::feeJournal).orElse(null));
        variables.put("Revista_WoS", journal.map(JournalFacts::webOfScience).orElse(false));
        variables.put("Revista_WoS_CC", journal.map(JournalFacts::webOfScienceCore).orElse(false));
        variables.put("Revista_Scopus", journal.map(JournalFacts::scopus).orElse(false));
        variables.put("N_baze_date", journal.map(j -> recognisedDatabases(j.databases(), indicator)).orElse(0));
        variables.put("Coef_m", declaredCoefficient(fields.get(FIELD_LANGUAGE), placeAbroad(journal, fields.get(FIELD_PUBLISHER))));
        variables.put("Identificator_valid", IDENTIFIER_FIELDS.stream().anyMatch(f -> identifierValid(fields.get(f))));
        bindIndexedYears(refs.get(Activity.ReferenceField.FORUM_ISSN), fields, year, indicator, variables);
        // the journal's impact factor comes from the data, never typed (H145: no type asks for it): null when unknown
        variables.put("IF_revista", journal.map(JournalFacts::impactFactor).orElse(null));
    }

    /**
     * The option a head approved for this record (H143's requests, used beyond publishers since H144), while the record
     * still asks for it; null otherwise.
     */
    public static String approvedRequest(ActivityInstance activity, Map<String, String> fields) {
        var claim = ro.uvt.pokedex.core.model.activities.PublisherClaim.inForce(activity); // H145: facts unchanged
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
        String text = (codes == null ? "" : codes) + " " + (office == null ? "" : office);
        Matcher m = PATENT_CODE.matcher(text);
        while (m.find()) {
            String code = m.group(1);
            String kind = m.group(3);
            // an application (kind A) or a utility model (U, Y) is not a granted patent; WIPO publishes applications
            boolean granted = kind == null || kind.startsWith("B") || kind.startsWith("C") || "WO".equals(code);
            if (OFFICES.contains(code) && granted) {
                offices.add(code);
            }
        }
        Matcher w = OFFICE_WORD.matcher(text);
        while (w.find()) {
            String word = w.group(1).toUpperCase(Locale.ROOT);
            switch (word) {
                case "OSIM" -> offices.add("RO");
                case "EPO", "OEB", "EUROPEAN PATENT OFFICE" -> offices.add("EP");
                case "WIPO", "OMPI" -> offices.add("WO"); // a PCT application is no registration
                case "USPTO" -> offices.add("US");
                case "JPO" -> offices.add("JP");
                default -> {
                }
            }
        }
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

    /**
     * H145 — the granted patents a record names, as office + number without separators ({@code RO128500},
     * {@code EP1234567}): what tells two colleagues' records of the same patent apart from two patents.
     */
    public static Set<String> grantedPatentCodes(String codes, String office) {
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = PATENT_CODE.matcher((codes == null ? "" : codes) + " " + (office == null ? "" : office));
        while (m.find()) {
            String kind = m.group(3);
            boolean granted = kind == null || kind.startsWith("B") || kind.startsWith("C") || "WO".equals(m.group(1));
            if (OFFICES.contains(m.group(1)) && granted) {
                out.add(m.group(1) + m.group(2).replaceAll("[^0-9]", ""));
            }
        }
        return out;
    }

    /**
     * H145 — the databases a standard recognises, counted as it counts them: Comisia 28 its own list (Psychology:
     * Scopus, ERIH; Education: also DOAJ); any other standard Web of Science once (whichever editions index the
     * journal), Scopus, ERIH and DOAJ. DBLP, OpenAlex's fee rows or a second WoS edition never count.
     */
    public static int recognisedDatabases(Set<String> databases, Indicator indicator) {
        if (databases == null || databases.isEmpty()) {
            return 0;
        }
        Optional<Comisia28Rules> c28 = indicator == null ? Optional.empty() : Comisia28Rules.of(indicator);
        if (c28.isPresent()) {
            return (int) databases.stream().filter(c28.get().recognisedDatabases()::contains).count();
        }
        return (int) databases.stream().filter(RECOGNISED_DATABASES::contains).count()
                + (databases.stream().anyMatch(WOS_EDITIONS::contains) ? 1 : 0);
    }

    /** The databases the general rule counts, as the workspace names them (Web of Science once). */
    public static List<String> recognisedDatabaseNames(Set<String> databases) {
        List<String> names = new java.util.ArrayList<>();
        if (databases == null) {
            return names;
        }
        if (databases.stream().anyMatch(WOS_EDITIONS::contains)) {
            names.add("Web of Science");
        }
        databases.stream().filter(RECOGNISED_DATABASES::contains).sorted().forEach(names::add);
        return names;
    }

    static final String FIELD_PUBLISHER = "Editura";

    /**
     * H145 — the coefficient m of a declared publication (Comisia 25, definition [6]) from facts, never picked: a
     * language of international circulation (stated as a fact) published abroad (the journal's country, or a
     * publisher on the international lists that is no Romanian house) gives 2; such a language at home or in an
     * unknown place 1.5; any other language, or none stated, 1. As for the publications the platform finds (H125),
     * every unknown resolves downward, and "international peer review" cannot be looked up.
     */
    static double declaredCoefficient(String language, Boolean abroad) {
        if (language == null || !INTERNATIONAL_LANGUAGES.contains(language.trim())) {
            return 1.0;
        }
        return Boolean.TRUE.equals(abroad) ? 2.0 : 1.5;
    }

    /** Whether the publication appeared abroad: true, false, or null when the platform cannot tell. */
    static Boolean placeAbroad(Optional<JournalFacts> journal, String publisher) {
        if (journal.isPresent() && journal.get().country() != null && !journal.get().country().isBlank()) {
            return isAbroad(journal.get().country());
        }
        if (publisher != null && !publisher.isBlank()) {
            if (InternationalPublisherSupport.isRomanian(publisher)) {
                return false;
            }
            if (InternationalPublisherSupport.recognize(publisher).isPresent()) {
                return true;
            }
        }
        return null;
    }

    /** At most this many years of an editorship are looked up (1950 to now). */
    private static final int YEARS_MAX = 80;

    /**
     * H145 — the years of an editorship (An_inceput to An_sfarsit, or to the run's reference year) in which the journal
     * was in Web of Science (SCIE, SSCI, AHCI) or Scopus ({@code N_ani_WoS_Scopus}), and those in which it was in
     * neither but in three recognised databases ({@code N_ani_BDI}); only those years are paid. Without years, the
     * record's own year. Scopus membership has no history in the data: its current state stands for every year.
     */
    static void bindIndexedYears(String issn, Map<String, String> fields, Integer recordYear, Indicator indicator,
                                 Map<String, Object> variables) {
        int wosOrScopus = 0, bdi = 0;
        if (canonicalIssn(issn) != null) {
            Integer reference = ScoringReferenceYearContext.current();
            int last = reference != null ? reference : java.time.Year.now().getValue();
            Integer from = year(fields.get("An_inceput"));
            Integer to = year(fields.get("An_sfarsit"));
            int start = from != null ? from : (recordYear == null ? 0 : recordYear);
            int end = from != null ? Math.min(to != null ? to : last, last) : start;
            if (start >= 1950 && end >= start && end - start < YEARS_MAX) {
                for (int y = start; y <= end; y++) {
                    Optional<JournalFacts> j = journal(issn, y);
                    if (j.isEmpty()) {
                        continue;
                    }
                    if (j.get().webOfScience() || j.get().scopus()) {
                        wosOrScopus++;
                    } else if (recognisedDatabases(j.get().databases(), indicator) >= 3) {
                        bdi++;
                    }
                }
            }
        }
        variables.put("N_ani_WoS_Scopus", wosOrScopus);
        variables.put("N_ani_BDI", bdi);
    }

    private static Integer year(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(value.trim().replace(',', '.'));
            return Double.isFinite(d) && d == Math.rint(d) ? (int) d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The fields a declared publication states its ISSN, ISBN or ISMN in. */
    static final List<String> IDENTIFIER_FIELDS = List.of("ISSN_ISBN", "ISBN", "ISMN_sau_ISBN");

    /**
     * H145 — whether a text holds a real ISSN, ISBN-10, ISBN-13 or ISMN (979-0): its check digit is right. A standard that
     * asks for "a volume with ISSN or ISBN" asks for one that exists, not for any string.
     */
    static boolean identifierValid(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String token : text.split("[;,|]")) { // "ISSN 1234-5679; ISBN 978-973-…": one identifier per part
            String c = token.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
            if ((c.length() == 8 && issnValid(c)) || (c.length() == 10 && isbn10Valid(c)) || (c.length() == 13 && isbn13Valid(c))) {
                return true;
            }
        }
        return false;
    }

    private static boolean issnValid(String c) {
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            if (!Character.isDigit(c.charAt(i))) return false;
            sum += (c.charAt(i) - '0') * (8 - i);
        }
        int check = (11 - sum % 11) % 11;
        return c.charAt(7) == (check == 10 ? 'X' : (char) ('0' + check));
    }

    private static boolean isbn10Valid(String c) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char ch = c.charAt(i);
            int v = ch == 'X' && i == 9 ? 10 : (Character.isDigit(ch) ? ch - '0' : -1);
            if (v < 0) return false;
            sum += v * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static boolean isbn13Valid(String c) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            if (!Character.isDigit(c.charAt(i))) return false;
            sum += (c.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    /** The candidate's own university, written as the rankings or the university write it. */
    static boolean isOwnUniversity(String name) {
        String n = fold(name).replaceAll("[^a-z ]+", " ").replaceAll("\\s+", " ").trim();
        return OWN_UNIVERSITY.contains(n);
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
