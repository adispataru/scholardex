package ro.uvt.pokedex.core.service.importing.scopus;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * H73 S2.1 — match a Scopus affiliation (name/city/country) to the OpenAlex ROR <b>backbone</b> affiliation it
 * belongs to, so a verified afid plugs into the backbone record (ROR is the cross-source key Scopus lacks) instead
 * of minting a duplicate. Three tiers, most precise first:
 *
 * <ol>
 *   <li><b>Exact alias</b> — normalized Scopus name equals the backbone's display name or any
 *       {@code display_name_alternatives}/acronym (the cross-language linchpin: "Universitatea de Vest" ↔
 *       "West University of Timișoara" both sit on one ROR record's aliases).</li>
 *   <li><b>Simplification</b> — strip a leading "the", parenthetical acronyms, and a leading
 *       faculty/department/institute clause, then retry the exact-alias index.</li>
 *   <li><b>Country-gated token-Jaccard ≥ 0.8</b> — only against backbone records in the same country; ties broken
 *       by matching city. Cross-country never matches (the unsafe direction H72 warned about).</li>
 * </ol>
 *
 * Built once per canon run from the backbone (immutable after build). {@code null} = no confident match → the
 * caller mints an afid-keyed Scopus-only affiliation.
 */
public final class ScopusAffiliationRorMatcher {

    private static final double JACCARD_THRESHOLD = 0.8;
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final Pattern LEADING_FACULTY_CLAUSE = Pattern.compile(
            "^(the\\s+)?(faculty|department|dept|institute|school|college|centre|center|laboratory|lab|division|"
                    + "facultatea|departamentul|institutul)\\b[^,]*,\\s*", Pattern.CASE_INSENSITIVE);
    // "of"/"the"/"and" carry no disambiguating signal; dropped from the Jaccard token set.
    private static final Set<String> STOPWORDS = Set.of("of", "the", "and", "for", "de", "la", "din", "si");

    private final Map<String, ScholardexAffiliationFact> byAlias;
    private final Map<String, List<ScholardexAffiliationFact>> byCountry;

    private ScopusAffiliationRorMatcher(Map<String, ScholardexAffiliationFact> byAlias,
                                        Map<String, List<ScholardexAffiliationFact>> byCountry) {
        this.byAlias = byAlias;
        this.byCountry = byCountry;
    }

    /** Build the matcher index from the ROR backbone (OPENALEX-source affiliation facts). */
    public static ScopusAffiliationRorMatcher build(Collection<ScholardexAffiliationFact> backbone) {
        Map<String, ScholardexAffiliationFact> byAlias = new HashMap<>();
        Map<String, List<ScholardexAffiliationFact>> byCountry = new HashMap<>();
        if (backbone != null) {
            for (ScholardexAffiliationFact fact : backbone) {
                if (fact == null) {
                    continue;
                }
                indexAlias(byAlias, fact.getName(), fact);
                if (fact.getAliases() != null) {
                    for (String alias : fact.getAliases()) {
                        indexAlias(byAlias, alias, fact);
                    }
                }
                String country = CanonicalizationSupport.normalizeToken(fact.getCountry());
                if (country != null && !country.isEmpty()) {
                    byCountry.computeIfAbsent(country, k -> new ArrayList<>()).add(fact);
                }
            }
        }
        return new ScopusAffiliationRorMatcher(byAlias, byCountry);
    }

    private static void indexAlias(Map<String, ScholardexAffiliationFact> byAlias, String value,
                                   ScholardexAffiliationFact fact) {
        String norm = CanonicalizationSupport.normalizeName(value);
        if (norm != null && !norm.isEmpty()) {
            // First writer wins — a ROR record's own display name and earlier records take precedence over a later
            // record reusing the same alias string (rare; keeps matching deterministic).
            byAlias.putIfAbsent(norm, fact);
        }
    }

    /** @return the backbone affiliation this Scopus affiliation belongs to, or {@code null} if no confident match. */
    public ScholardexAffiliationFact match(String name, String city, String country) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // Tier 1 — exact alias.
        String norm = CanonicalizationSupport.normalizeName(name);
        if (norm != null) {
            ScholardexAffiliationFact m = byAlias.get(norm);
            if (m != null) {
                return m;
            }
        }
        // Tier 2 — simplification, then retry the alias index.
        String simplified = CanonicalizationSupport.normalizeName(simplify(name));
        if (simplified != null && !simplified.equals(norm)) {
            ScholardexAffiliationFact m = byAlias.get(simplified);
            if (m != null) {
                return m;
            }
        }
        // Tier 3 — country-gated token-Jaccard with city tiebreak.
        return jaccardMatch(name, city, country);
    }

    private static String simplify(String name) {
        String s = PARENTHETICAL.matcher(name).replaceAll(" ");
        s = LEADING_FACULTY_CLAUSE.matcher(s).replaceFirst("");
        return s;
    }

    private ScholardexAffiliationFact jaccardMatch(String name, String city, String country) {
        String normCountry = CanonicalizationSupport.normalizeToken(country);
        if (normCountry == null || normCountry.isEmpty()) {
            return null; // no country gate — refuse cross-country fuzzy matching
        }
        List<ScholardexAffiliationFact> candidates = byCountry.get(normCountry);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<String> tokens = tokenSet(name);
        if (tokens.isEmpty()) {
            return null;
        }
        String normCity = CanonicalizationSupport.normalizeToken(city);
        ScholardexAffiliationFact best = null;
        double bestScore = 0.0;
        boolean bestCityMatch = false;
        for (ScholardexAffiliationFact candidate : candidates) {
            double score = jaccard(tokens, tokenSet(candidate.getName()));
            if (score < JACCARD_THRESHOLD) {
                continue;
            }
            boolean cityMatch = normCity != null && !normCity.isEmpty()
                    && normCity.equals(CanonicalizationSupport.normalizeToken(candidate.getCity()));
            // Prefer higher Jaccard; break ties by matching city.
            if (score > bestScore || (score == bestScore && cityMatch && !bestCityMatch)) {
                best = candidate;
                bestScore = score;
                bestCityMatch = cityMatch;
            }
        }
        return best;
    }

    private static Set<String> tokenSet(String name) {
        String norm = CanonicalizationSupport.normalizeName(name);
        if (norm == null || norm.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(norm.split(" ")));
        tokens.removeAll(STOPWORDS);
        tokens.removeIf(String::isEmpty);
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        int union = a.size() + b.size() - inter.size();
        return union == 0 ? 0.0 : (double) inter.size() / union;
    }
}
