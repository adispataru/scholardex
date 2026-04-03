package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexProjectionReadServiceEdgeTraversalTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexAuthorFactRepository canonicalAuthorFactRepository;
    @Mock private ScholardexAffiliationFactRepository canonicalAffiliationFactRepository;
    @Mock private ScholardexForumFactRepository canonicalForumFactRepository;
    @Mock private ScholardexEdgeWriterService edgeWriterService;
    @Mock private PostgresScholardexProjectionReadPort postgresProjectionReadPort;

    @Test
    void findAllPublicationsByAuthorsInDelegatesToPostgresPort() {
        ScholardexProjectionReadService service = buildService();

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection()))
                .thenReturn(List.of(authorLink));

        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection()))
                .thenReturn(Set.of("spub_1"));

        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_1");
        pub.setTitle("Paper");
        when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(pub));

        List<ScholardexPublicationView> publications = service.findAllPublicationsByAuthorsIn(List.of("legacy-author"));

        assertEquals(1, publications.size());
        assertEquals("spub_1", publications.getFirst().getId());
    }

    @Test
    void findAllPublicationsByAffiliationsContainingDelegatesToPostgresPort() {
        ScholardexProjectionReadService service = buildService();

        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection()))
                .thenReturn(List.of(affiliationLink));

        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("legacy-aff"))
                .thenReturn(Set.of());
        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("saff_1"))
                .thenReturn(Set.of("sauth_1"));

        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection()))
                .thenReturn(Set.of("spub_2"));

        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_2");
        pub.setTitle("From affiliation");
        when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(pub));

        List<ScholardexPublicationView> publications = service.findAllPublicationsByAffiliationsContaining("legacy-aff");

        assertEquals(1, publications.size());
        assertEquals("spub_2", publications.getFirst().getId());
    }

    @Test
    void findAllCitationsByCitedIdInDelegatesToPostgresPort() {
        ScholardexProjectionReadService service = buildService();

        // Direct canonical IDs (start with "spub_") fast-path: no lookup needed
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setId("c1");
        citation.setCitedId("spub_1");
        citation.setCitingId("spub_citing");
        when(postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(anyCollection()))
                .thenReturn(List.of(citation));

        when(postgresProjectionReadPort.findExistingPublicationIdsByIdIn(anyCollection()))
                .thenReturn(Set.of("spub_1", "spub_citing"));

        List<ScholardexCitationView> citations = service.findAllCitationsByCitedIdIn(List.of("spub_1", "spub_2"));

        assertEquals(1, citations.size());
    }

    private ScholardexProjectionReadService buildService() {
        return new ScholardexProjectionReadService(
                jdbcTemplate,
                sourceLinkService,
                canonicalAuthorFactRepository,
                canonicalAffiliationFactRepository,
                canonicalForumFactRepository,
                edgeWriterService,
                postgresProjectionReadPort
        );
    }
}
