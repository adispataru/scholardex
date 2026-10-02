package ro.uvt.pokedex.core.service.reporting;

import java.util.Set;

/**
 * H143 — publishers that never count, whichever list names them: Lambert Academic Publishing (LAP), which UEFISCDI
 * excludes from its list of publishers of international prestige, and which Adrian excluded everywhere (2026-10-02),
 * although CNATDCU's 2011 A2 list of the Social Sciences panel names it. The lists keep it as published; the rule
 * lives here.
 */
final class ExcludedPublishers {

    private static final Set<String> LAMBERT = WosMasterBookListService.canonicalTokens("Lambert Academic Publishing");

    private ExcludedPublishers() {
    }

    /** Whether the name, as typed or indexed, is that of an excluded house ("LAP Lambert Academic Publishing", …). */
    static boolean isExcluded(String publisher) {
        Set<String> tokens = WosMasterBookListService.canonicalTokens(publisher);
        return !tokens.isEmpty() && InternationalPublisherListService.matches(tokens, LAMBERT);
    }
}
