package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.Optional;

public interface ScholardexAdminReadPort {
    AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle);

    Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId);
}
