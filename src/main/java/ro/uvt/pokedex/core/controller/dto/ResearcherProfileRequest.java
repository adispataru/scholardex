package ro.uvt.pokedex.core.controller.dto;

import jakarta.validation.constraints.NotBlank;
import ro.uvt.pokedex.core.model.reporting.Position;

import java.util.ArrayList;
import java.util.List;

public record ResearcherProfileRequest(
        /** User email — required for create; must match the User's email (which is their id). */
        String email,
        @NotBlank String firstName,
        @NotBlank String lastName,
        String scholarId,
        List<String> scopusId,
        List<String> wosId,
        String orcid,
        String primaryScholardexAuthorId,
        Position position
) {
    public List<String> normalizedScopusId() {
        return scopusId == null ? new ArrayList<>() : scopusId;
    }

    public String normalizedOrcid() {
        return ro.uvt.pokedex.core.service.openalex.OrcidSupport.normalize(orcid);
    }

    public List<String> normalizedWosId() {
        return wosId == null ? new ArrayList<>() : wosId;
    }
}
