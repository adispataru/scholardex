package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Group;

import java.util.List;

public record GroupEditViewModel(
        Group group,
        List<Domain> domains,
        List<Institution> affiliations,
        List<Researcher> allResearchers
) {
}
