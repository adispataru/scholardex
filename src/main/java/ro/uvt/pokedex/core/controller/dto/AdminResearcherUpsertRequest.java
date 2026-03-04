package ro.uvt.pokedex.core.controller.dto;

import jakarta.validation.constraints.NotBlank;
import ro.uvt.pokedex.core.model.reporting.Position;

import java.util.ArrayList;
import java.util.List;

public record AdminResearcherUpsertRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String scholarId,
        List<String> scopusId,
        List<String> wosId,
        Position position
) {
    public List<String> normalizedScopusId() {
        return scopusId == null ? new ArrayList<>() : scopusId;
    }

    public List<String> normalizedWosId() {
        return wosId == null ? new ArrayList<>() : wosId;
    }
}
