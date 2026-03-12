package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScholardexAdminReadFacade {

    private final ObjectProvider<PostgresScholardexAdminReadPort> postgresScholardexAdminReadPortProvider;

    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        return activePort().buildPublicationSearchView(paperTitle);
    }

    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        return activePort().buildPublicationCitationsView(publicationId);
    }

    private PostgresScholardexAdminReadPort activePort() {
        PostgresScholardexAdminReadPort postgresPort = postgresScholardexAdminReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres admin read port is not available.");
        }
        return postgresPort;
    }
}
