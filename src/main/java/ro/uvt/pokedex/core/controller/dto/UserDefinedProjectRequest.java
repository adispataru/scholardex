package ro.uvt.pokedex.core.controller.dto;

import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;

/**
 * H64 slice 4b — admin payload to create/update a user-defined project (trusted budget). euGrantId or code is the
 * merge key into the canonical layer; budget is the point of the entry.
 */
public record UserDefinedProjectRequest(
        String id,
        String euGrantId,
        String code,
        String title,
        String funder,
        Long budget,
        String currency,
        String directorFirst,
        String directorLast,
        String coordinatorName,
        Integer startYear,
        Integer endYear,
        String origin
) {
    public UserDefinedProjectFact toFact() {
        UserDefinedProjectFact f = new UserDefinedProjectFact();
        f.setId(id);
        f.setEuGrantId(euGrantId);
        f.setCode(code);
        f.setTitle(title);
        f.setFunder(funder);
        f.setBudget(budget);
        f.setCurrency(currency);
        f.setDirectorFirst(directorFirst);
        f.setDirectorLast(directorLast);
        f.setCoordinatorName(coordinatorName);
        f.setStartYear(startYear);
        f.setEndYear(endYear);
        f.setOrigin(origin);
        return f;
    }
}
