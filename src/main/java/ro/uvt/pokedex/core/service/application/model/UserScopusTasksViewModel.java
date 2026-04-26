package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.user.User;

import java.util.List;

public record UserScopusTasksViewModel(
        User user,
        List<ScopusPublicationUpdate> tasks,
        List<ScopusCitationsUpdate> citationsTasks
) {
}
