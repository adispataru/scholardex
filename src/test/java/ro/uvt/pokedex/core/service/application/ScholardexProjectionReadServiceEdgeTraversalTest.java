package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        ScholardexPublicationView pubOlder = new ScholardexPublicationView();
        pubOlder.setId("spub_0");
        pubOlder.setTitle("Paper older");
        pubOlder.setCoverDate("2020-01-01");
        pub.setCoverDate("2021-01-01");
        when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection()))
                .thenReturn(List.of(pubOlder, pub));

        List<ScholardexPublicationView> publications = service.findAllPublicationsByAuthorsIn(List.of("legacy-author"));

        assertEquals(2, publications.size());
        assertEquals("spub_1", publications.getFirst().getId());
    }

    @Test
    void findAllPublicationsByAuthorsContainingDelegatesAndReturnsEmptyWhenNoAuthors() {
        ScholardexProjectionReadService service = buildService();
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of());
        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection()))
                .thenReturn(Set.of());

        assertTrue(service.findAllPublicationsByAuthorsContaining("legacy-author").isEmpty());
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
    void findAllPublicationsByAffiliationsContainingReturnsEmptyWhenNoResolvedAffiliations() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findAllPublicationsByAffiliationsContaining(" ").isEmpty());
    }

    @Test
    void findAllPublicationsByAffiliationsContainingReturnsEmptyWhenNoPublicationIds() {
        ScholardexProjectionReadService service = buildService();
        ScholardexSourceLink affiliationLink = new ScholardexSourceLink();
        affiliationLink.setCanonicalEntityId("saff_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of(affiliationLink));
        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("legacy-aff")).thenReturn(Set.of());
        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("saff_1")).thenReturn(Set.of("sauth_1"));
        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(Set.of("sauth_1"))).thenReturn(Set.of());
        assertTrue(service.findAllPublicationsByAffiliationsContaining("legacy-aff").isEmpty());
    }

    @Test
    void findAllPublicationsByAuthorsInReturnsEmptyWhenNoResolvedAuthors() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findAllPublicationsByAuthorsIn(List.of(" ")).isEmpty());
    }

    @Test
    void findAllPublicationsByAuthorsInReturnsEmptyWhenNoPublicationIds() {
        ScholardexProjectionReadService service = buildService();
        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of(authorLink));
        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection())).thenReturn(Set.of());
        assertTrue(service.findAllPublicationsByAuthorsIn(List.of("legacy-author")).isEmpty());
    }

    @Test
    void findAllScoringPublicationsByAuthorsInReturnsEmptyWhenNoResolvedAuthors() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findAllScoringPublicationsByAuthorsIn(List.of(" ")).isEmpty());
    }

    @Test
    void findAllScoringPublicationsByAuthorsInReturnsEmptyWhenNoPublicationIds() {
        ScholardexProjectionReadService service = buildService();
        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of(authorLink));
        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection())).thenReturn(Set.of());
        assertTrue(service.findAllScoringPublicationsByAuthorsIn(List.of("legacy-author")).isEmpty());
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

    @Test
    void findAllCitationsByCitedIdInResolvesIdThenEidThenAnyIdFallback() {
        ScholardexProjectionReadService service = buildService();

        ScholardexPublicationView byId = new ScholardexPublicationView();
        byId.setId("spub_id");
        ScholardexPublicationView byEid = new ScholardexPublicationView();
        byEid.setId("spub_eid");
        byEid.setEid("2-s2.0-eid");
        ScholardexPublicationView byAny = new ScholardexPublicationView();
        byAny.setId("spub_any");

        when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection())).thenReturn(List.of(byId));
        when(postgresProjectionReadPort.findPublicationsByEidIn(anyCollection())).thenReturn(List.of(byEid));
        when(postgresProjectionReadPort.findPublicationByAnyId("legacy_key")).thenReturn(Optional.of(byAny));

        ScholardexCitationView c1 = new ScholardexCitationView();
        c1.setCitedId("spub_id");
        c1.setCitingId("spub_x");
        ScholardexCitationView c2 = new ScholardexCitationView();
        c2.setCitedId("spub_eid");
        c2.setCitingId("spub_y");
        ScholardexCitationView c3 = new ScholardexCitationView();
        c3.setCitedId("spub_any");
        c3.setCitingId("spub_z");
        when(postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(anyCollection())).thenReturn(List.of(c1, c2, c3));
        when(postgresProjectionReadPort.findExistingPublicationIdsByIdIn(anyCollection()))
                .thenReturn(Set.of("spub_id", "spub_eid", "spub_any", "spub_x", "spub_y", "spub_z"));

        List<ScholardexCitationView> out = service.findAllCitationsByCitedIdIn(List.of("spub_id", "2-s2.0-eid", "legacy_key"));
        assertEquals(3, out.size());
    }

    @Test
    void findAllAuthorsDelegatesToReadPort() {
        ScholardexProjectionReadService service = buildService();
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("sauthor_1");
        when(postgresProjectionReadPort.findAllAuthors()).thenReturn(List.of(author));
        assertEquals(1, service.findAllAuthors().size());
        assertEquals("sauthor_1", service.findAllAuthors().getFirst().getId());
    }

    @Test
    void findAllAffiliationsDelegatesToReadPort() {
        ScholardexProjectionReadService service = buildService();
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("saff_1");
        when(postgresProjectionReadPort.findAllAffiliations()).thenReturn(List.of(affiliation));
        assertEquals(1, service.findAllAffiliations().size());
        assertEquals("saff_1", service.findAllAffiliations().getFirst().getAfid());
    }

    @Test
    void findAffiliationsByIdInReturnsEmptyForNullOrEmpty() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findAffiliationsByIdIn(null).isEmpty());
        assertTrue(service.findAffiliationsByIdIn(List.of()).isEmpty());
        verify(postgresProjectionReadPort, never()).findAffiliationsByIdIn(anyCollection());
    }

    @Test
    void findAffiliationsByIdInDelegatesForNonEmptyIds() {
        ScholardexProjectionReadService service = buildService();
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("saff_42");
        when(postgresProjectionReadPort.findAffiliationsByIdIn(List.of("saff_42"))).thenReturn(List.of(affiliation));

        List<ScholardexAffiliationView> out = service.findAffiliationsByIdIn(List.of("saff_42"));
        assertEquals(1, out.size());
        assertEquals("saff_42", out.getFirst().getAfid());
    }

    @Test
    void findAllCitationsByCitedIdFiltersInvalidRows() {
        ScholardexProjectionReadService service = buildService();

        ScholardexPublicationView cited = new ScholardexPublicationView();
        cited.setId("spub_10");
        when(postgresProjectionReadPort.findPublicationByAnyId("2-s2.0-cited")).thenReturn(Optional.of(cited));

        ScholardexCitationView good = new ScholardexCitationView();
        good.setId("c-good");
        good.setCitedId("spub_10");
        good.setCitingId("spub_20");

        ScholardexCitationView missingCiting = new ScholardexCitationView();
        missingCiting.setId("c-bad");
        missingCiting.setCitedId("spub_10");
        missingCiting.setCitingId(null);

        when(postgresProjectionReadPort.findCitationsByCitedPublicationId("spub_10"))
                .thenReturn(List.of(missingCiting, good));
        when(postgresProjectionReadPort.findExistingPublicationIdsByIdIn(anyCollection()))
                .thenReturn(Set.of("spub_10", "spub_20"));

        List<ScholardexCitationView> result = service.findAllCitationsByCitedId("2-s2.0-cited");

        assertEquals(1, result.size());
        assertEquals("c-good", result.getFirst().getId());
        assertEquals(1L, service.countCitationsByCitedId("2-s2.0-cited"));
    }

    @Test
    void findAllCitationsByCitedIdReturnsEmptyWhenKeyCannotResolve() {
        ScholardexProjectionReadService service = buildService();
        when(postgresProjectionReadPort.findPublicationByAnyId("missing")).thenReturn(Optional.empty());

        assertTrue(service.findAllCitationsByCitedId("missing").isEmpty());
        verify(postgresProjectionReadPort, never()).findCitationsByCitedPublicationId(any());
    }

    @Test
    void findAllCitationsByCitedIdInWithNoResolvedKeysSkipsCitationFetch() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findAllCitationsByCitedIdIn(List.of(" ", "\t")).isEmpty());
        verify(postgresProjectionReadPort, never()).findCitationsByCitedPublicationIdIn(anyCollection());
    }

    @Test
    void findAllCitationsByCitedIdInWithNoCitationsSkipsExistingIdsLookup() {
        ScholardexProjectionReadService service = buildService();
        when(postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(anyCollection())).thenReturn(List.of());

        List<ScholardexCitationView> out = service.findAllCitationsByCitedIdIn(List.of("spub_100"));
        assertTrue(out.isEmpty());
        verify(postgresProjectionReadPort, never()).findExistingPublicationIdsByIdIn(anyCollection());
    }

    @Test
    void citationFiltersDropRowsWithMissingNodesAndSortRemaining() {
        ScholardexProjectionReadService service = buildService();
        ScholardexCitationView c1 = new ScholardexCitationView();
        c1.setId("c1");
        c1.setCitedId("spub_b");
        c1.setCitingId("spub_a");
        ScholardexCitationView c2 = new ScholardexCitationView();
        c2.setId("c2");
        c2.setCitedId("spub_a");
        c2.setCitingId("spub_c");
        ScholardexCitationView cInvalid = new ScholardexCitationView();
        cInvalid.setId("c3");
        cInvalid.setCitedId("spub_x");
        cInvalid.setCitingId("spub_y");
        when(postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(anyCollection())).thenReturn(List.of(c1, c2, cInvalid));
        when(postgresProjectionReadPort.findExistingPublicationIdsByIdIn(anyCollection())).thenReturn(Set.of("spub_a", "spub_b", "spub_c"));

        List<ScholardexCitationView> out = service.findAllCitationsByCitedIdIn(List.of("spub_a", "spub_b"));
        assertEquals(2, out.size());
        assertEquals("spub_a", out.get(0).getCitedId());
        assertEquals("spub_b", out.get(1).getCitedId());
    }

    @Test
    void findForumByIdResolvesViaSourceLinkAndReturnsFirstForum() {
        ScholardexProjectionReadService service = buildService();
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setCanonicalEntityId("sforum_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection()))
                .thenReturn(List.of(link));

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("sforum_1");
        when(postgresProjectionReadPort.findForumsByIdIn(eq(List.of("legacy_forum", "sforum_1"))))
                .thenReturn(List.of(forum));

        assertTrue(service.findForumById("legacy_forum").isPresent());
        assertEquals("sforum_1", service.findForumById("legacy_forum").orElseThrow().getId());
    }

    @Test
    void findForumByIdReturnsEmptyForNullOrBlankIdInsteadOfThrowing() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findForumById(null).isEmpty());
        assertTrue(service.findForumById(" ").isEmpty());
        verify(postgresProjectionReadPort, never()).findForumsByIdIn(anyCollection());
    }

    @Test
    void findAllForumsAndFindForumsByIdInDelegate() {
        ScholardexProjectionReadService service = buildService();
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("sforum_d");
        when(postgresProjectionReadPort.findAllForums()).thenReturn(List.of(forum));

        ScholardexSourceLink map = new ScholardexSourceLink();
        map.setCanonicalEntityId("sforum_c");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.FORUM), anyCollection()))
                .thenReturn(List.of(map));
        when(postgresProjectionReadPort.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum));

        assertEquals(1, service.findAllForums().size());
        assertEquals(1, service.findForumsByIdIn(List.of("legacy_forum")).size());
    }

    @Test
    void publicationAndTitleAndScoringDelegates() {
        ScholardexProjectionReadService service = buildService();
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_1");
        pub.setEid("2-s2.0-1");
        pub.setCoverDate("2023-01-01");
        when(postgresProjectionReadPort.findPublicationById("spub_1")).thenReturn(Optional.of(pub));
        when(postgresProjectionReadPort.findPublicationByEid("2-s2.0-1")).thenReturn(Optional.of(pub));
        when(postgresProjectionReadPort.findPublicationByAnyId("k")).thenReturn(Optional.of(pub));
        ScholardexPublicationView pubOlder = new ScholardexPublicationView();
        pubOlder.setId("spub_0");
        pubOlder.setCoverDate("2020-01-01");
        when(postgresProjectionReadPort.findPublicationsByTitleContainingIgnoreCase("ai")).thenReturn(List.of(pubOlder, pub));

        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setCanonicalEntityId("sauth_x");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(any(), anyCollection())).thenReturn(List.of(link));
        when(postgresProjectionReadPort.findPublicationIdsByAuthorIdIn(anyCollection())).thenReturn(Set.of("spub_s"));
        ScoringPublicationReadModel scoring = mock(ScoringPublicationReadModel.class);
        when(scoring.getId()).thenReturn("spub_s");
        when(postgresProjectionReadPort.findScoringPublicationsByIdIn(anyCollection())).thenReturn(List.of(scoring));
        when(postgresProjectionReadPort.findScoringPublicationById("spub_s")).thenReturn(Optional.of(scoring));

        assertTrue(service.findPublicationById("spub_1").isPresent());
        assertTrue(service.findPublicationByEid("2-s2.0-1").isPresent());
        assertTrue(service.findPublicationByAnyId("k").isPresent());
        assertTrue(service.findPublicationByAnyId(" ").isEmpty());
        List<ScholardexPublicationView> byTitle = service.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc("ai");
        assertEquals(2, byTitle.size());
        assertEquals("spub_1", byTitle.getFirst().getId());
        assertEquals(1, service.findAllScoringPublicationsByAuthorsIn(List.of("legacy")).size());
        assertTrue(service.findScoringPublicationById("spub_s").isPresent());
    }

    @Test
    void authorAndAffiliationDelegates() {
        ScholardexProjectionReadService service = buildService();
        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sa1");
        ScholardexSourceLink affLink = new ScholardexSourceLink();
        affLink.setCanonicalEntityId("saff_1");
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AUTHOR), anyCollection()))
                .thenReturn(List.of(authorLink));
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.AFFILIATION), anyCollection()))
                .thenReturn(List.of(affLink));
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("sa1");
        when(postgresProjectionReadPort.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author));
        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("legacyAff")).thenReturn(Set.of());
        when(postgresProjectionReadPort.findAuthorIdsByAffiliationId("saff_1")).thenReturn(Set.of("sa1"));
        when(postgresProjectionReadPort.findAuthorsByNameContainsIgnoreCase("john")).thenReturn(List.of(author));
        ScholardexAffiliationView aff = new ScholardexAffiliationView();
        aff.setAfid("saff_1");
        when(postgresProjectionReadPort.findAffiliationsByIdIn(anyCollection())).thenReturn(List.of(aff));
        when(postgresProjectionReadPort.findAffiliationsByCountry("RO")).thenReturn(List.of(aff));
        when(postgresProjectionReadPort.findAffiliationsByNameContains("uvt")).thenReturn(List.of(aff));

        assertEquals(1, service.findAuthorsByIdIn(List.of("legacyAuthor")).size());
        assertTrue(service.findAuthorById("legacyAuthor").isPresent());
        assertEquals(1, service.findAuthorsByAffiliationId("legacyAff").size());
        assertEquals(1, service.findAuthorsByNameContainsIgnoreCase("john").size());
        assertTrue(service.findAffiliationById("legacyAff").isPresent());
        assertEquals(1, service.findAffiliationsByCountry("RO").size());
        assertEquals(1, service.findAffiliationsByNameContains("uvt").size());
        assertEquals(1, service.findAffiliationsByIdIn(List.of("saff_1")).size());
    }

    @Test
    void findAllPublicationsByIdInMergesIdAndEid() {
        ScholardexProjectionReadService service = buildService();
        ScholardexPublicationView byId = new ScholardexPublicationView();
        byId.setId("spub_1");
        byId.setCoverDate("2020-01-01");
        ScholardexPublicationView byEid = new ScholardexPublicationView();
        byEid.setId("spub_2");
        byEid.setCoverDate("2021-01-01");
        when(postgresProjectionReadPort.findPublicationsByIdIn(anyCollection())).thenReturn(List.of(byId));
        when(postgresProjectionReadPort.findPublicationsByEidIn(anyCollection())).thenReturn(List.of(byEid));

        List<ScholardexPublicationView> out = service.findAllPublicationsByIdIn(List.of("spub_1", "2-s2.0-2"));
        assertEquals(2, out.size());
        assertEquals("spub_2", out.getFirst().getId());

        when(postgresProjectionReadPort.findPublicationByAnyId("legacy-key")).thenReturn(Optional.of(byId));
        when(postgresProjectionReadPort.findCitationsByCitedPublicationIdIn(anyCollection())).thenReturn(List.of());
        assertTrue(service.findAllCitationsByCitedIdIn(List.of("legacy-key")).isEmpty());
    }

    // saveForum/saveAuthor/saveAffiliation tests moved to ScholardexManualEditServiceTest (H54.5c).

    @Test
    void findPublicationByAnyIdReturnsEmptyForNull() {
        ScholardexProjectionReadService service = buildService();
        assertTrue(service.findPublicationByAnyId(null).isEmpty());
    }

    private ScholardexProjectionReadService buildService() {
        return new ScholardexProjectionReadService(
                jdbcTemplate,
                new ScholardexCanonicalIdResolver(sourceLinkService),
                postgresProjectionReadPort
        );
    }
}
