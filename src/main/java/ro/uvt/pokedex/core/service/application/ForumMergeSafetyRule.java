package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validated forum safe-merge rule (H55): two forums (or a cluster of forums) may be merged only
 * when they <i>either</i> share a single primary (print) ISSN <i>or</i> their names are an
 * abbreviation/expansion match. Forums bridged only by a shared eISSN/alias while carrying
 * <b>different</b> primary print ISSNs are different journals — continuations (renamed across eras) or
 * misassigned-eISSN source data errors (e.g. a journal whose eISSN field holds another journal's print
 * ISSN) — and must NOT be auto-merged.
 *
 * <p>Extracted (H57) so both {@link ScholardexForumDeduplicationService} (cluster dedup) and
 * {@link WosScholardexOnboardingService} (fold-time canonicalization) enforce the same rule. Previously
 * canonicalization merged into any candidate sharing <i>any</i> ISSN token, which let a misassigned
 * eISSN bridge two distinct journals into one canonical forum (with an order-sensitive id).
 */
@Component
public class ForumMergeSafetyRule {

    private static final Pattern ISSN_NON_ALNUM = Pattern.compile("[^0-9Xx]");
    private static final Pattern NAME_NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "of", "and", "the", "for", "in", "on", "de", "la", "le", "des", "du", "und", "fur",
            "der", "die", "das", "a", "an");

    /** Pairwise rule: is it safe to fold a forum with the given primary ISSN + name into {@code candidate}? */
    public boolean isSafeToMerge(String primaryIssn, String name, ScholardexForumFact candidate) {
        return isSafeToMerge(primaryIssn, name, candidate.getIssn(), candidate.getName());
    }

    public boolean isSafeToMerge(String primaryIssnA, String nameA, String primaryIssnB, String nameB) {
        String pa = normalizeIssn(primaryIssnA);
        String pb = normalizeIssn(primaryIssnB);
        if (pa != null && pb != null) {
            if (pa.equals(pb)) {
                return true;
            }
            // Different primary print ISSNs => different journals, unless the names are an
            // abbreviation/expansion match (a legitimately renamed continuation).
            return namesMatch(nameA, nameB);
        }
        // At least one side has no primary print ISSN: the only identity link is an eISSN/alias, which we
        // cannot prove spans two distinct journals -> allow (preserves legitimate eISSN-only merges).
        return true;
    }

    /** Cluster rule (dedup): every member shares one non-null primary print ISSN, or all names match. */
    public boolean isSafeToMergeCluster(List<ScholardexForumFact> cluster) {
        Set<String> primaries = new LinkedHashSet<>();
        for (ScholardexForumFact f : cluster) {
            String pi = normalizeIssn(f.getIssn());
            if (pi != null) {
                primaries.add(pi);
            }
        }
        if (primaries.size() == 1 && cluster.stream().allMatch(f -> normalizeIssn(f.getIssn()) != null)) {
            return true;
        }
        for (int i = 0; i < cluster.size(); i++) {
            for (int j = i + 1; j < cluster.size(); j++) {
                if (!namesMatch(cluster.get(i).getName(), cluster.get(j).getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Compact, upper-cased ISSN (hyphens/spaces stripped); null when blank. */
    public String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = ISSN_NON_ALNUM.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        return compact.isEmpty() ? null : compact;
    }

    /** True when one name is an in-order word-prefix abbreviation of the other (leading "journal"/"j" ignored). */
    public boolean namesMatch(String a, String b) {
        List<String> wa = nameWords(a);
        List<String> wb = nameWords(b);
        if (wa.isEmpty() || wb.isEmpty()) {
            return false;
        }
        if (String.join(" ", wa).equals(String.join(" ", wb))) {
            return true;
        }
        return abbreviationOf(wa, wb) || abbreviationOf(wb, wa);
    }

    private boolean abbreviationOf(List<String> shortWords, List<String> longWords) {
        List<String> s = stripLeadingJournal(shortWords);
        List<String> l = stripLeadingJournal(longWords);
        if (s.isEmpty()) {
            return false;
        }
        int j = 0;
        for (String tok : s) {
            boolean found = false;
            while (j < l.size()) {
                String lw = l.get(j);
                j++;
                if (lw.startsWith(tok) || tok.startsWith(lw)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<String> stripLeadingJournal(List<String> words) {
        if (!words.isEmpty() && (words.get(0).equals("journal") || words.get(0).equals("j"))) {
            return words.subList(1, words.size());
        }
        return words;
    }

    private List<String> nameWords(String name) {
        if (name == null) {
            return List.of();
        }
        String cleaned = NAME_NON_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String w : cleaned.split("\\s+")) {
            if (!w.isEmpty() && !NAME_STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }
}
