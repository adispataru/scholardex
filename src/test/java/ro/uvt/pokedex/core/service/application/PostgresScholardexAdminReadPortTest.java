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
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostgresScholardexAdminReadPortTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock
    private PublicationAuthorshipDecisionRepository publicationAuthorshipDecisionRepository;

    private PostgresScholardexAdminReadPort readPort;

    @BeforeEach
    void setUp() {
        readPort = new PostgresScholardexAdminReadPort(namedParameterJdbcTemplate, publicationAuthorshipDecisionRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCatalogPageAppliesNormalizationAndBuildsRelatedMaps() throws Exception {
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);
        PublicationAuthorshipDecision older = new PublicationAuthorshipDecision();
        older.setPublicationId("p1");
        older.setStatus(PublicationAuthorshipDecision.Status.REJECTED);
        older.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        PublicationAuthorshipDecision newer = new PublicationAuthorshipDecision();
        newer.setPublicationId("p1");
        newer.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        newer.setUpdatedAt(Instant.parse("2024-01-02T00:00:00Z"));
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of(older, newer));
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.scholardex_publication_view")) {
                        return List.of(mapper.mapRow(publicationRs("p1"), 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0), mapper.mapRow(authorRs("a2"), 1));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_forum_view")) {
                        return List.of(mapper.mapRow(forumRs("f1"), 0));
                    }
                    return List.of();
                });

        var page = readPort.buildPublicationCatalogPage(" title ", " f1 ", "a1", "af1", 9, 2, "year", "desc");
        assertEquals(1, page.content().size());
        assertEquals(3L, page.total());
        assertEquals(1, page.totalPages());
        assertEquals(0, page.page());
        assertEquals(25, page.size());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
        assertEquals(2, page.authorMap().size());
        assertEquals(1, page.forumMap().size());
        assertEquals("Forum f1", page.forumMap().get("f1").getPublicationName());
        assertEquals(2, page.decisionSummaryByPublicationId().get("p1").totalDecisions());
        assertEquals(1, page.decisionSummaryByPublicationId().get("p1").confirmedCount());
        assertEquals(1, page.decisionSummaryByPublicationId().get("p1").rejectedCount());
        assertEquals(PublicationAuthorshipDecision.Status.CONFIRMED,
                page.decisionSummaryByPublicationId().get("p1").latestStatus());

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));
        MapSqlParameterSource params = null;
        List<String> sqls = sqlCaptor.getAllValues();
        List<MapSqlParameterSource> allParams = paramsCaptor.getAllValues();
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).contains("ORDER BY")) {
                params = allParams.get(i);
                break;
            }
        }
        assertTrue(params != null);
        assertEquals(25, params.getValue("limit"));
        assertEquals(0L, params.getValue("offset"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationSearchViewCoversBlankAndFilteredPath() throws Exception {
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return new ArrayList<>(List.of(mapper.mapRow(publicationRs("p2"), 0), mapper.mapRow(publicationRs("p1"), 1)));
                });
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0), mapper.mapRow(authorRs("a2"), 1));
                    }
                    if (sql.contains("WHERE title ILIKE :pattern")) {
                        return new ArrayList<>(List.of(mapper.mapRow(publicationRs("p3"), 0)));
                    }
                    return List.of();
                });

        var blank = readPort.buildPublicationSearchView("  ");
        var filtered = readPort.buildPublicationSearchView("abc");
        assertEquals(2, blank.publications().size());
        assertEquals("p1", blank.publications().get(0).getId());
        assertEquals("p2", blank.publications().get(1).getId());
        assertEquals(2, blank.authorMap().size());
        assertEquals(1, filtered.publications().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCitationsViewCoversEmptyAndPagedMapping() throws Exception {
        assertTrue(readPort.buildPublicationCitationsView(" ", 0, 25).isEmpty());

        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("WHERE id = :key OR eid = :key")) {
                        return List.of(mapper.mapRow(publicationRs("p1"), 0));
                    }
                    if (sql.contains("mv_scholardex_citation_context")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("citing_publication_id")).thenReturn("p2");
                        when(rs.getString("citing_title")).thenReturn("Citing");
                        when(rs.getString("citing_cover_date")).thenReturn("2024-02-01");
                        when(rs.getString("citing_forum_id")).thenReturn("f2");
                        when(rs.getString("citing_eid")).thenReturn("eid-2");
                        when(rs.getString("citing_wos_id")).thenReturn("wos-2");
                        java.sql.Array authorIds = mock(java.sql.Array.class);
                        when(authorIds.getArray()).thenReturn(new String[]{"a1"});
                        when(rs.getArray("citing_author_ids")).thenReturn(authorIds);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0));
                    }
                    if (sql.contains("WHERE id IN (:ids)")) {
                        return List.of(mapper.mapRow(forumRs("f2"), 0));
                    }
                    if (sql.contains("WHERE id = :id")) {
                        return List.of(mapper.mapRow(forumRs("f1"), 0));
                    }
                    return List.of();
                });

        Optional<ScholardexCitationsView> viewOpt = readPort.buildPublicationCitationsView("p1", 5, 50);
        assertTrue(viewOpt.isPresent());
        ScholardexCitationsView view = viewOpt.orElseThrow();
        assertEquals(1, view.citations().size());
        assertEquals("p2", view.citations().get(0).getId());
        assertEquals("Citing", view.citations().get(0).getTitle());
        assertEquals("2024-02-01", view.citations().get(0).getCoverDate());
        assertEquals("f2", view.citations().get(0).getForum());
        assertEquals(List.of("a1"), view.citations().get(0).getAuthors());
        assertEquals("eid-2", view.citations().get(0).getEid());
        assertEquals("wos-2", view.citations().get(0).getWosId());
        assertEquals(0, view.page());
        assertEquals(50, view.size());
        assertEquals(1, view.totalPages());
        assertEquals("f1", view.publicationForum().getId());
        assertEquals("10.1/p1", view.publication().getDoi());
        assertEquals(List.of("a1", "a2"), view.publication().getAuthors());
        assertEquals("f2", view.forumMap().get("f2").getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCitationsViewReturnsEmptyWhenPublicationNotFound() {
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(readPort.buildPublicationCitationsView("missing", 0, 25).isEmpty());
    }

    @Test
    void bulkReassignForumHandlesBlankAndNonBlankForumIds() {
        when(namedParameterJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(2);
        assertEquals(0, readPort.bulkReassignForum(List.of(), "f1"));
        assertEquals(2, readPort.bulkReassignForum(List.of("p1", "p2"), " f1 "));
        assertEquals(2, readPort.bulkReassignForum(List.of("p1"), " "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decisionSummaryIgnoresBlankPublicationIdsAndFallsBackWhenUpdatedAtMissing() throws Exception {
        PublicationAuthorshipDecision validNoUpdatedAt = new PublicationAuthorshipDecision();
        validNoUpdatedAt.setPublicationId("p1");
        validNoUpdatedAt.setStatus(PublicationAuthorshipDecision.Status.REJECTED);
        PublicationAuthorshipDecision blankPublicationId = new PublicationAuthorshipDecision();
        blankPublicationId.setPublicationId(" ");
        blankPublicationId.setStatus(PublicationAuthorshipDecision.Status.CONFIRMED);
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any()))
                .thenReturn(List.of(validNoUpdatedAt, blankPublicationId));
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.scholardex_publication_view")) {
                        return List.of(mapper.mapRow(publicationRs("p1"), 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_forum_view")) {
                        return List.of(mapper.mapRow(forumRs("f1"), 0));
                    }
                    return List.of();
                });

        var page = readPort.buildPublicationCatalogPage(null, null, null, null, 0, 25, "title", "asc");
        assertEquals(1, page.decisionSummaryByPublicationId().size());
        var summary = page.decisionSummaryByPublicationId().get("p1");
        assertEquals(1, summary.totalDecisions());
        assertEquals(0, summary.confirmedCount());
        assertEquals(1, summary.rejectedCount());
        assertEquals(PublicationAuthorshipDecision.Status.REJECTED, summary.latestStatus());
        assertNull(summary.latestUpdatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCatalogPageWithoutFiltersUsesDefaults() {
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        var page = readPort.buildPublicationCatalogPage(" ", " ", " ", " ", -2, 100, "citations", "asc");
        assertEquals(0, page.content().size());
        assertEquals(100, page.size());
        assertEquals(0, page.page());
        assertEquals(1, page.totalPages());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCatalogPageUsesAllFiltersAndNonZeroOffset() throws Exception {
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(350L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.scholardex_publication_view")) {
                        return List.of(mapper.mapRow(publicationRs("p1"), 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_forum_view")) {
                        return List.of(mapper.mapRow(forumRs("f1"), 0));
                    }
                    return List.of();
                });

        var page = readPort.buildPublicationCatalogPage("abc", "f1", "a1", "af1", 2, 100, "citations", "desc");
        assertEquals(2, page.page());
        assertEquals(100, page.size());
        assertEquals(4, page.totalPages());
        assertTrue(page.hasPrevious());
        assertTrue(page.hasNext());

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));
        List<String> sqls = sqlCaptor.getAllValues();
        List<MapSqlParameterSource> paramsList = paramsCaptor.getAllValues();
        MapSqlParameterSource listQueryParams = null;
        String listQuerySql = null;
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).contains("ORDER BY cited_by_count DESC")) {
                listQuerySql = sqls.get(i);
                listQueryParams = paramsList.get(i);
                break;
            }
        }
        assertTrue(listQuerySql != null);
        assertTrue(listQuerySql.contains("title ILIKE :q"));
        assertTrue(listQuerySql.contains("forum_id = :forumId"));
        assertTrue(listQuerySql.contains(":authorId = ANY(author_ids)"));
        assertTrue(listQuerySql.contains(":affiliationId = ANY(affiliation_ids)"));
        assertEquals(100, listQueryParams.getValue("limit"));
        assertEquals(200L, listQueryParams.getValue("offset"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildPublicationCitationsViewUsesNonZeroOffsetWhenPageHasRoom() throws Exception {
        when(publicationAuthorshipDecisionRepository.findByPublicationIdIn(any())).thenReturn(List.of());
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(220L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("WHERE id = :key OR eid = :key")) {
                        return List.of(mapper.mapRow(publicationRs("p1"), 0));
                    }
                    if (sql.contains("mv_scholardex_citation_context")) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("citing_publication_id")).thenReturn("p2");
                        when(rs.getString("citing_title")).thenReturn("Citing");
                        when(rs.getString("citing_cover_date")).thenReturn("2024-02-01");
                        when(rs.getString("citing_forum_id")).thenReturn("f2");
                        when(rs.getString("citing_eid")).thenReturn("eid-2");
                        when(rs.getString("citing_wos_id")).thenReturn("wos-2");
                        java.sql.Array authorIds = mock(java.sql.Array.class);
                        when(authorIds.getArray()).thenReturn(new String[]{"a1"});
                        when(rs.getArray("citing_author_ids")).thenReturn(authorIds);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("FROM reporting_read.scholardex_author_view")) {
                        return List.of(mapper.mapRow(authorRs("a1"), 0));
                    }
                    if (sql.contains("WHERE id IN (:ids)")) {
                        return List.of(mapper.mapRow(forumRs("f2"), 0));
                    }
                    if (sql.contains("WHERE id = :id")) {
                        return List.of(mapper.mapRow(forumRs("f1"), 0));
                    }
                    return List.of();
                });

        var view = readPort.buildPublicationCitationsView("p1", 2, 50).orElseThrow();
        assertEquals(2, view.page());
        assertEquals(5, view.totalPages());
        assertTrue(view.hasPrevious());
        assertTrue(view.hasNext());
        assertEquals(220L, view.totalCitations());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));
        List<String> sqls = sqlCaptor.getAllValues();
        List<MapSqlParameterSource> params = paramsCaptor.getAllValues();
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).contains("mv_scholardex_citation_context")) {
                assertEquals(50, params.get(i).getValue("limit"));
                assertEquals(100L, params.get(i).getValue("offset"));
                return;
            }
        }
        throw new AssertionError("citation list query params not captured");
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateFindPublicationsByIdInSortsByDateThenTitle() throws Exception {
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet older = publicationRs("p-older");
                    when(older.getString("title")).thenReturn("Beta");
                    when(older.getString("cover_date")).thenReturn("2022-01-01");
                    ResultSet newerAlpha = publicationRs("p-newer-a");
                    when(newerAlpha.getString("title")).thenReturn("Alpha");
                    when(newerAlpha.getString("cover_date")).thenReturn("2024-01-01");
                    ResultSet newerZulu = publicationRs("p-newer-z");
                    when(newerZulu.getString("title")).thenReturn("Zulu");
                    when(newerZulu.getString("cover_date")).thenReturn("2024-01-01");
                    return new ArrayList<>(List.of(
                            mapper.mapRow(newerZulu, 0),
                            mapper.mapRow(older, 1),
                            mapper.mapRow(newerAlpha, 2)
                    ));
                });

        @SuppressWarnings("unchecked")
        List<?> result = (List<?>) invoke("findPublicationsByIdIn", new Class[]{Collection.class}, List.of("x", "y"));
        assertEquals(3, result.size());
        var p0 = (ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView) result.get(0);
        var p1 = (ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView) result.get(1);
        var p2 = (ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView) result.get(2);
        assertEquals("p-newer-a", p0.getId());
        assertEquals("p-newer-z", p1.getId());
        assertEquals("p-older", p2.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void decisionSummaryReturnsEmptyWhenAllPublicationIdsBlank() throws Exception {
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("FROM reporting_read.scholardex_publication_view")) {
                        ResultSet rs1 = publicationRs("p1");
                        when(rs1.getString("id")).thenReturn(" ");
                        ResultSet rs2 = publicationRs("p2");
                        when(rs2.getString("id")).thenReturn(null);
                        return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 1));
                    }
                    return List.of();
                });

        var page = readPort.buildPublicationCatalogPage(null, null, null, null, 0, 25, "title", "asc");
        assertTrue(page.decisionSummaryByPublicationId().isEmpty());
        verifyNoInteractions(publicationAuthorshipDecisionRepository);
    }

    @Test
    void helperContractsViaReflection() throws Exception {
        assertEquals(25, invoke("normalizePageSize", new Class[]{int.class}, 12));
        assertEquals(50, invoke("normalizePageSize", new Class[]{int.class}, 50));
        assertEquals("cover_date", invoke("normalizeSort", new Class[]{String.class}, "year"));
        assertEquals("cited_by_count", invoke("normalizeSort", new Class[]{String.class}, "citations"));
        assertEquals("title", invoke("normalizeSort", new Class[]{String.class}, "x"));
        assertEquals("ASC", invoke("normalizeDirection", new Class[]{String.class}, "x"));
        assertEquals("DESC", invoke("normalizeDirection", new Class[]{String.class}, "desc"));
        assertEquals(0, invoke("normalizePage", new Class[]{int.class, int.class}, -1, 4));
        assertEquals(3, invoke("normalizePage", new Class[]{int.class, int.class}, 9, 4));
    }

    private Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
        var method = PostgresScholardexAdminReadPort.class.getDeclaredMethod(name, sig);
        method.setAccessible(true);
        return method.invoke(readPort, args);
    }

    private ResultSet publicationRs(String id) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id);
        when(rs.getString("doi")).thenReturn("10.1/" + id);
        when(rs.getString("eid")).thenReturn("2-s2.0-" + id);
        when(rs.getString("wos_id")).thenReturn("wos-" + id);
        when(rs.getString("title")).thenReturn("Title " + id);
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
        when(rs.getObject("author_count", Integer.class)).thenReturn(2);
        when(rs.getObject("open_access", Boolean.class)).thenReturn(Boolean.TRUE);
        when(rs.getObject("approved", Boolean.class)).thenReturn(Boolean.TRUE);
        when(rs.getObject("cited_by_count", Integer.class)).thenReturn(4);
        java.sql.Array authorIds = mock(java.sql.Array.class);
        when(authorIds.getArray()).thenReturn(new String[]{"a1", "a2"});
        java.sql.Array affiliationIds = mock(java.sql.Array.class);
        when(affiliationIds.getArray()).thenReturn(new String[]{"af1"});
        java.sql.Array corresponding = mock(java.sql.Array.class);
        when(corresponding.getArray()).thenReturn(new String[]{"a1"});
        java.sql.Array citing = mock(java.sql.Array.class);
        when(citing.getArray()).thenReturn(new String[]{"pX"});
        when(rs.getArray("author_ids")).thenReturn(authorIds);
        when(rs.getArray("affiliation_ids")).thenReturn(affiliationIds);
        when(rs.getArray("corresponding_authors")).thenReturn(corresponding);
        when(rs.getArray("citing_publication_ids")).thenReturn(citing);
        return rs;
    }

    private ResultSet authorRs(String id) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id);
        when(rs.getString("name")).thenReturn("Author " + id);
        java.sql.Array alt = mock(java.sql.Array.class);
        when(alt.getArray()).thenReturn(new String[]{"Alt " + id});
        java.sql.Array aff = mock(java.sql.Array.class);
        when(aff.getArray()).thenReturn(new String[]{"af1"});
        when(rs.getArray("alternative_names")).thenReturn(alt);
        when(rs.getArray("affiliation_ids")).thenReturn(aff);
        return rs;
    }

    private ResultSet forumRs(String id) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id);
        when(rs.getString("publication_name")).thenReturn("Forum " + id);
        when(rs.getString("issn")).thenReturn("1111");
        when(rs.getString("e_issn")).thenReturn("2222");
        when(rs.getString("aggregation_type")).thenReturn("JOURNAL");
        return rs;
    }

}
