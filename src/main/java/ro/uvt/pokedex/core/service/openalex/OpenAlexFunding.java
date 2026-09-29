package ro.uvt.pokedex.core.service.openalex;

import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse.Funder;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse.Grant;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse.OpenAlexWork;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * H120 — the funding line of a publication, from OpenAlex: {@code "Funder (award); Funder"}, funders in the
 * order OpenAlex lists them, each once per award. Null when the work names no funder.
 */
public final class OpenAlexFunding {

    private static final int MAX_LENGTH = 500;

    private OpenAlexFunding() {
    }

    public static String describe(OpenAlexWork work) {
        if (work == null) {
            return null;
        }
        Set<String> parts = new LinkedHashSet<>();
        if (work.getGrants() != null) {
            for (Grant grant : work.getGrants()) {
                if (grant == null || isBlank(grant.getFunder_display_name())) {
                    continue;
                }
                String funder = grant.getFunder_display_name().trim();
                parts.add(isBlank(grant.getAward_id()) ? funder : funder + " (" + grant.getAward_id().trim() + ")");
            }
        }
        if (parts.isEmpty() && work.getFunders() != null) {
            for (Funder funder : work.getFunders()) {
                if (funder != null && !isBlank(funder.getDisplay_name())) {
                    parts.add(funder.getDisplay_name().trim());
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String line = String.join("; ", parts);
        return line.length() <= MAX_LENGTH ? line : line.substring(0, MAX_LENGTH - 1) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
