package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;

import java.util.List;

public record UserScopusTasksViewModel(
        Researcher researcher,
        List<ScopusPublicationUpdate> tasks,
        List<ScopusCitationsUpdate> citationsTasks
) {
}
