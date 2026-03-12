package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScholardexAdminReadFacade {

    private final ReportingReadStoreSelector readStoreSelector;
    private final MongoScholardexAdminReadPort mongoScholardexAdminReadPort;
    private final ObjectProvider<PostgresScholardexAdminReadPort> postgresScholardexAdminReadPortProvider;
    private final ScholardexProjectionReadService scholardexProjectionReadService;

    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        if (readStoreSelector.isPostgres()) {
            return activePort().buildPublicationSearchView(paperTitle);
        }

        List<Publication> publications = new ArrayList<>(
                scholardexProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc(paperTitle));
        publications.sort(PublicationOrderingSupport.publicationComparator());

        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        Map<String, Author> authorMap = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));

        return new AdminScopusPublicationSearchViewModel(publications, authorMap);
    }

    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        return activePort().buildPublicationCitationsView(publicationId);
    }

    private ScholardexAdminReadPort activePort() {
        if (!readStoreSelector.isPostgres()) {
            return mongoScholardexAdminReadPort;
        }
        PostgresScholardexAdminReadPort postgresPort = postgresScholardexAdminReadPortProvider.getIfAvailable();
        if (postgresPort == null) {
            throw new IllegalStateException("Postgres read-store selected but PostgresScholardexAdminReadPort is not available.");
        }
        return postgresPort;
    }
}
