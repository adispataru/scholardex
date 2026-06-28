package ro.uvt.pokedex.core.service.cordis;

import java.util.List;

/**
 * H64 slice 4b.3 — a project parsed from the public CORDIS per-project XML export
 * ({@code cordis.europa.eu/project/id/{ID}?format=xml}). The per-partner {@code ecContribution} is the trusted budget.
 */
public record CordisProject(
        String grantId,
        String rcn,
        String acronym,
        String title,
        String startDate,
        String endDate,
        String frameworkProgramme,
        Long totalCost,
        Long ecMaxContribution,
        List<CordisOrg> organizations
) {
    public record CordisOrg(String role, String legalName, String shortName, String country, Long ecContribution) {
    }

    /** EC contribution of the first organization whose legal name contains {@code nameFragment} (case-insensitive). */
    public Long contributionFor(String nameFragment) {
        CordisOrg org = organizationFor(nameFragment);
        return org == null ? null : org.ecContribution();
    }

    public CordisOrg organizationFor(String nameFragment) {
        if (nameFragment == null || nameFragment.isBlank() || organizations == null) {
            return null;
        }
        String needle = nameFragment.trim().toUpperCase();
        return organizations.stream()
                .filter(o -> o.legalName() != null && o.legalName().toUpperCase().contains(needle))
                .findFirst()
                .orElse(null);
    }

    /** Start year parsed from {@code startDate} ({@code yyyy-...}), or null. */
    public Integer startYear() {
        return year(startDate);
    }

    public Integer endYear() {
        return year(endDate);
    }

    private static Integer year(String date) {
        if (date == null || date.length() < 4 || !date.substring(0, 4).matches("\\d{4}")) {
            return null;
        }
        return Integer.valueOf(date.substring(0, 4));
    }
}
