package ro.uvt.pokedex.core.view.support;

import ro.uvt.pokedex.core.model.reporting.IndividualReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared web plumbing for the promotion-readiness board's criterion exclusion filter — the board is
 * served from three unit-flavored controllers (division / department / group) that all speak the same
 * {@code ?exclude=1,4} URL grammar and render the same chip bar.
 */
public final class PromotionBoardWebSupport {

    private PromotionBoardWebSupport() {
    }

    /** One chip on the promotions board: click toggles the criterion in/out of the computed buckets. */
    public record CriterionToggle(int index, String name, boolean excluded, String toggleHref) {}

    /** Parses the {@code exclude} query param (comma-separated indices); stray tokens are dropped. */
    public static Set<Integer> parseExcludedCriteria(String exclude) {
        Set<Integer> excluded = new TreeSet<>();
        if (exclude == null || exclude.isBlank()) {
            return excluded;
        }
        for (String part : exclude.split(",")) {
            try {
                excluded.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // hand-edited URL — skip the token
            }
        }
        return excluded;
    }

    /**
     * Builds the chip list for the report's criteria and clamps {@code excluded} (mutating it) to the
     * indices the report actually defines, so stray URL values neither render nor stick to hrefs.
     */
    public static List<CriterionToggle> buildToggles(IndividualReport report, Set<Integer> excluded,
                                                     String baseHref) {
        int criteriaCount = report.getCriteria() == null ? 0 : report.getCriteria().size();
        excluded.removeIf(i -> i < 0 || i >= criteriaCount);
        List<CriterionToggle> toggles = new ArrayList<>();
        for (int i = 0; i < criteriaCount; i++) {
            String name = report.getCriteria().get(i).getName();
            toggles.add(new CriterionToggle(
                    i,
                    name == null || name.isBlank() ? "C" + (i + 1) : name,
                    excluded.contains(i),
                    toggleHref(baseHref, excluded, i)));
        }
        return toggles;
    }

    private static String toggleHref(String baseHref, Set<Integer> excluded, int index) {
        Set<Integer> toggled = new TreeSet<>(excluded);
        if (!toggled.remove(index)) {
            toggled.add(index);
        }
        if (toggled.isEmpty()) {
            return baseHref;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer i : toggled) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return baseHref + "?exclude=" + sb;
    }
}
