package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostgresScholardexProjectionReadPortTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private PostgresScholardexProjectionReadPort readPort;

    @BeforeEach
    void setUp() {
        readPort = new PostgresScholardexProjectionReadPort(namedParameterJdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicationQueriesMapPublicationAndScoringModels() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet rs = publicationRs();
                    if (sql.contains("WHERE id IN (:ids)") || sql.contains("WHERE eid IN (:eids)") || sql.contains("WHERE id = :id")
                            || sql.contains("WHERE eid = :eid") || sql.contains("WHERE id = :key OR eid = :key")) {
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });

        List<ScholardexPublicationView> byId = readPort.findPublicationsByIdIn(List.of("p1"));
        List<ScholardexPublicationView> byEid = readPort.findPublicationsByEidIn(List.of("2-s2.0-x"));
        Optional<ScholardexPublicationView> oneById = readPort.findPublicationById("p1");
        Optional<ScholardexPublicationView> oneByEid = readPort.findPublicationByEid("2-s2.0-x");
        Optional<ScholardexPublicationView> anyId = readPort.findPublicationByAnyId("p1");
        List<ScoringPublicationReadModel> scoring = readPort.findScoringPublicationsByIdIn(List.of("p1"));
        Optional<ScoringPublicationReadModel> oneScoring = readPort.findScoringPublicationById("p1");

        assertEquals(1, byId.size());
        assertEquals(1, byEid.size());
        assertTrue(oneById.isPresent());
        assertTrue(oneByEid.isPresent());
        assertTrue(anyId.isPresent());
        assertEquals("doi:10.1/x", oneById.orElseThrow().getDoi());
        assertEquals("10.1/x", oneById.orElseThrow().getDoiNormalized());
        assertEquals("wos-x", oneById.orElseThrow().getWosId());
        assertEquals("gs-x", oneById.orElseThrow().getGoogleScholarId());
        assertEquals("Title", oneById.orElseThrow().getTitle());
        assertEquals("article", oneById.orElseThrow().getSubtype());
        assertEquals("Article", oneById.orElseThrow().getSubtypeDescription());
        assertEquals("ar", oneById.orElseThrow().getScopusSubtype());
        assertEquals("Article", oneById.orElseThrow().getScopusSubtypeDescription());
        assertEquals("Creator", oneById.orElseThrow().getCreator());
        assertEquals("2024-01-01", oneById.orElseThrow().getCoverDate());
        assertEquals("2024", oneById.orElseThrow().getCoverDisplayDate());
        assertEquals("1", oneById.orElseThrow().getVolume());
        assertEquals("2", oneById.orElseThrow().getIssueIdentifier());
        assertEquals("Desc", oneById.orElseThrow().getDescription());
        assertEquals(2, oneById.orElseThrow().getAuthorCount());
        assertEquals(List.of("a1"), oneById.orElseThrow().getCorrespondingAuthors());
        assertTrue(oneById.orElseThrow().isOpenAccess());
        assertEquals("yes", oneById.orElseThrow().getFreetoread());
        assertEquals("OA", oneById.orElseThrow().getFreetoreadLabel());
        assertEquals("fund", oneById.orElseThrow().getFundingId());
        assertEquals("art", oneById.orElseThrow().getArticleNumber());
        assertEquals("1-2", oneById.orElseThrow().getPageRange());
        assertTrue(oneById.orElseThrow().isApproved());
        assertEquals(List.of("a1", "a2"), oneById.orElseThrow().getAuthors());
        assertEquals(List.of("af1"), oneById.orElseThrow().getAffiliations());
        assertEquals("f1", oneById.orElseThrow().getForum());
        assertEquals(Set.of("p2"), oneById.orElseThrow().getCitingPublicationIds());
        assertEquals(7, oneById.orElseThrow().getCitedbyCount());
        assertEquals("v1", oneById.orElseThrow().getBuildVersion());
        assertEquals("s-line", oneById.orElseThrow().getScopusLineage());
        assertEquals("w-line", oneById.orElseThrow().getWosLineage());
        assertEquals("g-line", oneById.orElseThrow().getScholarLineage());
        assertEquals("l1", oneById.orElseThrow().getLinkerVersion());
        assertEquals("run-1", oneById.orElseThrow().getLinkerRunId());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), oneById.orElseThrow().getBuildAt());
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), oneById.orElseThrow().getUpdatedAt());
        assertEquals(Instant.parse("2024-01-03T00:00:00Z"), oneById.orElseThrow().getLinkedAt());
        assertEquals(1, scoring.size());
        assertTrue(oneScoring.isPresent());
        assertEquals(7, scoring.get(0).getCitedByCount());
        assertEquals(2, scoring.get(0).getAuthorIds().size());
        assertEquals("2-s2.0-x", scoring.get(0).getEid());
        assertEquals("f1", scoring.get(0).getForumId());
        assertEquals("wos-x", scoring.get(0).getWosId());
        assertEquals("Title", scoring.get(0).getTitle());
    }

    @Test
    @SuppressWarnings("unchecked")
    void idAndEdgeQueriesFilterBlankValues() {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("SELECT id FROM reporting_read.scholardex_publication_view")) {
                        when(rs.getString("id")).thenReturn("p1").thenReturn("p1").thenReturn("p1");
                        return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1), mapper.mapRow(rs, 2));
                    }
                    if (sql.contains("SELECT publication_id FROM reporting_read.scholardex_authorship_fact")) {
                        when(rs.getString("publication_id")).thenReturn("p1").thenReturn(" ").thenReturn("p2");
                        return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1), mapper.mapRow(rs, 2));
                    }
                    if (sql.contains("SELECT DISTINCT author_id")) {
                        when(rs.getString("author_id")).thenReturn("a1").thenReturn("").thenReturn("a2");
                        return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1), mapper.mapRow(rs, 2));
                    }
                    return List.of();
                });

        Set<String> existing = readPort.findExistingPublicationIdsByIdIn(List.of("p1", "p2"));
        Set<String> publicationIds = readPort.findPublicationIdsByAuthorIdIn(List.of("a1"));
        Set<String> authorIds = readPort.findAuthorIdsByAffiliationId("aff1");

        assertEquals(Set.of("p1"), existing);
        assertEquals(Set.of("p1", "p2"), publicationIds);
        assertEquals(Set.of("a1", "a2"), authorIds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorAndAffiliationQueriesMapArraysAndStrings() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("scholardex_author_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("a1");
                        when(rs.getString("name")).thenReturn("Author One");
                        java.sql.Array alt = mock(java.sql.Array.class);
                        when(alt.getArray()).thenReturn(new String[]{"Alt 1"});
                        java.sql.Array aff = mock(java.sql.Array.class);
                        when(aff.getArray()).thenReturn(new String[]{"af1", "af2"});
                        when(rs.getArray("alternative_names")).thenReturn(alt);
                        when(rs.getArray("affiliation_ids")).thenReturn(aff);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("scholardex_affiliation_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("af1");
                        when(rs.getString("name")).thenReturn("UVT");
                        when(rs.getString("city")).thenReturn("Timisoara");
                        when(rs.getString("country")).thenReturn("RO");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
        when(namedParameterJdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("id")).thenReturn("af1");
                    when(rs.getString("name")).thenReturn("UVT");
                    when(rs.getString("city")).thenReturn("Timisoara");
                    when(rs.getString("country")).thenReturn("RO");
                    return List.of(mapper.mapRow(rs, 0));
                });

        ScholardexAuthorView author = readPort.findAuthorsByIdIn(List.of("a1")).get(0);
        assertEquals("Author One", author.getName());
        assertEquals(List.of("Alt 1"), author.getAlternativeNames());
        assertEquals(List.of("af1", "af2"), author.getAffiliationIds());
        assertEquals(1, readPort.findAffiliationsByIdIn(List.of("af1")).size());
        assertEquals(1, readPort.findAllAffiliations().size());
        assertEquals(1, readPort.findAffiliationsByCountry("RO").size());
        assertEquals(1, readPort.findAffiliationsByNameContains("UV").size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankInputsShortCircuitAndDoNotQueryDatabase() {
        assertTrue(readPort.findPublicationsByIdIn(List.of()).isEmpty());
        assertTrue(readPort.findPublicationsByIdIn(null).isEmpty());
        assertTrue(readPort.findPublicationById(" ").isEmpty());
        assertTrue(readPort.findPublicationByEid(null).isEmpty());
        assertTrue(readPort.findPublicationByAnyId(" ").isEmpty());
        assertTrue(readPort.findScoringPublicationsByIdIn(List.of()).isEmpty());
        assertTrue(readPort.findScoringPublicationById("").isEmpty());
        assertTrue(readPort.findExistingPublicationIdsByIdIn(List.of()).isEmpty());
        assertTrue(readPort.findPublicationIdsByAuthorIdIn(List.of()).isEmpty());
        assertTrue(readPort.findAuthorIdsByAffiliationId(" ").isEmpty());
        assertTrue(readPort.findCitationsByCitedPublicationId(" ").isEmpty());
        assertTrue(readPort.findCitationsByCitedPublicationIdIn(List.of()).isEmpty());
        assertTrue(readPort.findForumsByIdIn(List.of()).isEmpty());
        assertTrue(readPort.findAuthorsByIdIn(List.of()).isEmpty());
        assertTrue(readPort.findAffiliationsByIdIn(List.of()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void titleAndAllQueriesUseExpectedSqlContracts() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    String sql = invocation.getArgument(0);
                    if (sql.contains("scholardex_forum_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("f1");
                        when(rs.getString("publication_name")).thenReturn("Forum");
                        when(rs.getString("issn")).thenReturn("1111");
                        when(rs.getString("e_issn")).thenReturn("2222");
                        when(rs.getString("aggregation_type")).thenReturn("JOURNAL");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("scholardex_author_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("a1");
                        when(rs.getString("name")).thenReturn("Author");
                        when(rs.getArray("alternative_names")).thenReturn(null);
                        when(rs.getArray("affiliation_ids")).thenReturn(null);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("scholardex_affiliation_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("af1");
                        when(rs.getString("name")).thenReturn("Aff");
                        when(rs.getString("city")).thenReturn("Timisoara");
                        when(rs.getString("country")).thenReturn("RO");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        readPort.findPublicationsByTitleContainingIgnoreCase("x");
        readPort.findAuthorsByNameContainsIgnoreCase("y");
        readPort.findAllForums();
        readPort.findAllAuthors();
        readPort.findAllAffiliations();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, times(2)).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("title ILIKE :pattern")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void citationAndForumQueriesMapNonEmptyResults() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("scholardex_citation_fact")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("c1");
                        when(rs.getString("cited_publication_id")).thenReturn("p1");
                        when(rs.getString("citing_publication_id")).thenReturn("p2");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("scholardex_forum_view")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("f1");
                        when(rs.getString("publication_name")).thenReturn("Forum");
                        when(rs.getString("issn")).thenReturn("1111");
                        when(rs.getString("e_issn")).thenReturn("2222");
                        when(rs.getString("aggregation_type")).thenReturn("JOURNAL");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });

        List<ScholardexCitationView> byId = readPort.findCitationsByCitedPublicationId("p1");
        List<ScholardexCitationView> byIds = readPort.findCitationsByCitedPublicationIdIn(List.of("p1"));
        List<ScholardexForumView> forums = readPort.findForumsByIdIn(List.of("f1"));
        assertEquals(1, byId.size());
        assertEquals(1, byIds.size());
        assertEquals("p2", byIds.get(0).getCitingPublicationId());
        assertEquals(1, forums.size());
        assertEquals("Forum", forums.get(0).getPublicationName());
        assertEquals("1111", forums.get(0).getIssn());
        assertEquals("2222", forums.get(0).getEIssn());
        assertEquals("JOURNAL", forums.get(0).getAggregationType());
        assertEquals("f1", forums.get(0).getScopusId());
    }

    @Test
    void helperContractsViaReflection() throws Exception {
        assertTrue((boolean) invoke("isNullOrEmpty", new Class[]{java.util.Collection.class}, (Object) null));
        assertTrue((boolean) invoke("isNullOrEmpty", new Class[]{java.util.Collection.class}, List.of()));
        assertEquals(0, invoke("readIntOrDefault", new Class[]{ResultSet.class, String.class}, nullIntRs(), "x"));
        assertEquals(7, invoke("readIntOrDefault", new Class[]{ResultSet.class, String.class}, fixedIntRs(), "x"));
    }

    private ResultSet publicationRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("p1");
        when(rs.getString("doi")).thenReturn("doi:10.1/x");
        when(rs.getString("doi_normalized")).thenReturn("10.1/x");
        when(rs.getString("eid")).thenReturn("2-s2.0-x");
        when(rs.getString("wos_id")).thenReturn("wos-x");
        when(rs.getString("google_scholar_id")).thenReturn("gs-x");
        when(rs.getString("title")).thenReturn("Title");
        when(rs.getString("subtype")).thenReturn("article");
        when(rs.getString("subtype_description")).thenReturn("Article");
        when(rs.getString("scopus_subtype")).thenReturn("ar");
        when(rs.getString("scopus_subtype_description")).thenReturn("Article");
        when(rs.getString("creator")).thenReturn("Creator");
        when(rs.getString("cover_date")).thenReturn("2024-01-01");
        when(rs.getString("cover_display_date")).thenReturn("2024");
        when(rs.getString("volume")).thenReturn("1");
        when(rs.getString("issue_identifier")).thenReturn("2");
        when(rs.getString("description")).thenReturn("Desc");
        when(rs.getString("freetoread")).thenReturn("yes");
        when(rs.getString("freetoread_label")).thenReturn("OA");
        when(rs.getString("funding_id")).thenReturn("fund");
        when(rs.getString("article_number")).thenReturn("art");
        when(rs.getString("page_range")).thenReturn("1-2");
        when(rs.getString("forum_id")).thenReturn("f1");
        when(rs.getString("build_version")).thenReturn("v1");
        when(rs.getString("scopus_lineage")).thenReturn("s-line");
        when(rs.getString("wos_lineage")).thenReturn("w-line");
        when(rs.getString("scholar_lineage")).thenReturn("g-line");
        when(rs.getString("linker_version")).thenReturn("l1");
        when(rs.getString("linker_run_id")).thenReturn("run-1");
        when(rs.getObject("author_count", Integer.class)).thenReturn(2);
        when(rs.getObject("cited_by_count", Integer.class)).thenReturn(7);
        when(rs.getObject("open_access", Boolean.class)).thenReturn(Boolean.TRUE);
        when(rs.getObject("approved", Boolean.class)).thenReturn(Boolean.TRUE);
        when(rs.getTimestamp("build_at")).thenReturn(Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2024-01-02T00:00:00Z")));
        when(rs.getTimestamp("linked_at")).thenReturn(Timestamp.from(Instant.parse("2024-01-03T00:00:00Z")));

        java.sql.Array authors = mock(java.sql.Array.class);
        when(authors.getArray()).thenReturn(new String[]{"a1", "a2"});
        java.sql.Array affiliations = mock(java.sql.Array.class);
        when(affiliations.getArray()).thenReturn(new String[]{"af1"});
        java.sql.Array corresponding = mock(java.sql.Array.class);
        when(corresponding.getArray()).thenReturn(new String[]{"a1"});
        java.sql.Array citing = mock(java.sql.Array.class);
        when(citing.getArray()).thenReturn(new String[]{"p2"});

        when(rs.getArray("author_ids")).thenReturn(authors);
        when(rs.getArray("affiliation_ids")).thenReturn(affiliations);
        when(rs.getArray("corresponding_authors")).thenReturn(corresponding);
        when(rs.getArray("citing_publication_ids")).thenReturn(citing);
        return rs;
    }

    private ResultSet nullIntRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("x", Integer.class)).thenReturn(null);
        return rs;
    }

    private ResultSet fixedIntRs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("x", Integer.class)).thenReturn(7);
        return rs;
    }

    private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
        var method = PostgresScholardexProjectionReadPort.class.getDeclaredMethod(name, sig);
        method.setAccessible(true);
        return method.invoke(readPort, args);
    }
}
