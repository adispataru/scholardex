package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.uvt.pokedex.core.controller.dto.PublicationTablePageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexPublicationMvcServiceTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Mock
    private PostgresScholardexProjectionReadPort projectionReadPort;

    private ScholardexPublicationMvcService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexPublicationMvcService(namedParameterJdbcTemplate, projectionReadPort);
    }

    @Test
    void searchUsesDefaultsAndMapsAuthorsAndForumNames() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_1");
        pub.setTitle("Publication A");
        pub.setCoverDate("2023-02-10");
        pub.setForum("sforum_1");
        pub.setAuthors(List.of("sa1", "sa2"));
        pub.setCitedbyCount(7);
        pub.setEid("2-s2.0-111");

        ScholardexAuthorView a1 = new ScholardexAuthorView();
        a1.setId("sa1");
        a1.setName("Alice");
        ScholardexAuthorView a2 = new ScholardexAuthorView();
        a2.setId("sa2");
        a2.setName("Bob");

        ScholardexForumView f1 = new ScholardexForumView();
        f1.setId("sforum_1");
        f1.setPublicationName("Forum One");

        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(Set.of("sa1", "sa2"))).thenReturn(List.of(a1, a2));
        when(projectionReadPort.findForumsByIdIn(Set.of("sforum_1"))).thenReturn(List.of(f1));

        PublicationTablePageResponse result = service.search(0, 13, null, "desc", "  topic  ");

        assertEquals(25, result.size());
        assertEquals(0, result.page());
        assertEquals(1, result.totalItems());
        assertEquals(1, result.items().size());
        assertEquals("2023", result.items().getFirst().year());
        assertEquals(List.of("Alice", "Bob"), result.items().getFirst().authorNames());
        assertEquals("Forum One", result.items().getFirst().forumName());

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).queryForObject(anyString(), paramsCaptor.capture(), eq(Long.class));
        assertEquals("%topic%", paramsCaptor.getValue().getValue("q"));
    }

    @Test
    void findDetailReturnsEmptyWhenPublicationMissing() {
        when(projectionReadPort.findPublicationByAnyId("missing")).thenReturn(Optional.empty());
        assertTrue(service.findDetail("missing").isEmpty());
    }

    @Test
    void findDetailBuildsAuthorFallbackAndYearAndForumName() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_2");
        pub.setTitle("Publication B");
        pub.setCoverDate("2021-11-03");
        pub.setForum("sforum_2");
        pub.setAuthors(List.of("sa_known", "sa_unknown"));

        ScholardexAuthorView known = new ScholardexAuthorView();
        known.setId("sa_known");
        known.setName("Known Author");

        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("sforum_2");
        forum.setPublicationName("Forum Two");

        when(projectionReadPort.findPublicationByAnyId("spub_2")).thenReturn(Optional.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(List.of("sa_known", "sa_unknown"))).thenReturn(List.of(known));
        when(projectionReadPort.findForumsByIdIn(Set.of("sforum_2"))).thenReturn(List.of(forum));

        Optional<ScholardexPublicationDetailViewModel> result = service.findDetail("spub_2");

        assertTrue(result.isPresent());
        ScholardexPublicationDetailViewModel detail = result.get();
        assertEquals("2021", detail.year());
        assertEquals("Forum Two", detail.forumName());
        assertEquals("Known Author", detail.authors().get(0).name());
        assertEquals("sa_unknown", detail.authors().get(1).name());
    }

    @Test
    void searchClampsPageUsesConfiguredSizeAndSortsByYearAsc() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_3");
        pub.setTitle("Publication C");
        pub.setCoverDate("2019-01-01");
        pub.setForum(null);
        pub.setAuthors(List.of("sa1", "sa2", "sa3", "sa4", "sa5", "sa6"));
        pub.setCitedbyCount(2);
        pub.setEid("2-s2.0-222");

        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(Set.of("sa1", "sa2", "sa3", "sa4", "sa5", "sa6"))).thenReturn(List.of());
        when(projectionReadPort.findForumsByIdIn(Set.of())).thenReturn(List.of());

        PublicationTablePageResponse result = service.search(99, 50, "year", "asc", " ");

        assertEquals(0, result.page());
        assertEquals(50, result.size());
        assertEquals(1, result.totalPages());
        assertEquals(5, result.items().getFirst().authorNames().size());
        assertEquals("", result.items().getFirst().forumName());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ORDER BY cover_date ASC"));
        assertFalse(sql.contains("WHERE title ILIKE :q"));
    }

    @Test
    void searchUsesCitationSortAndDescDirectionAndIgnoresTooLongQuery() {
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(projectionReadPort.findAuthorsByIdIn(Set.of())).thenReturn(List.of());
        when(projectionReadPort.findForumsByIdIn(Set.of())).thenReturn(List.of());

        String longQuery = "x".repeat(201);
        PublicationTablePageResponse result = service.search(0, 100, "citations", "desc", longQuery);

        assertEquals(100, result.size());
        assertEquals(1, result.totalPages());
        assertTrue(result.items().isEmpty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("ORDER BY cited_by_count DESC"));
        assertFalse(sql.contains("WHERE title ILIKE :q"));
    }

    @Test
    void searchAppliesFilterAtExactBoundaryLengthAndComputesOffset() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_5");
        pub.setTitle("Publication E");
        pub.setCoverDate("2024");
        pub.setForum("sforum_dup");
        pub.setAuthors(List.of("a1"));
        pub.setCitedbyCount(1);
        pub.setEid("eid-5");

        ScholardexAuthorView dupA1 = new ScholardexAuthorView();
        dupA1.setId("a1");
        dupA1.setName("Author One");
        ScholardexAuthorView dupA2 = new ScholardexAuthorView();
        dupA2.setId("a1");
        dupA2.setName("Author One Duplicate");

        ScholardexForumView dupF1 = new ScholardexForumView();
        dupF1.setId("sforum_dup");
        dupF1.setPublicationName("Forum Primary");
        ScholardexForumView dupF2 = new ScholardexForumView();
        dupF2.setId("sforum_dup");
        dupF2.setPublicationName("Forum Duplicate");

        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(120L);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(Set.of("a1"))).thenReturn(List.of(dupA1, dupA2));
        when(projectionReadPort.findForumsByIdIn(Set.of("sforum_dup"))).thenReturn(List.of(dupF1, dupF2));

        String q200 = "x".repeat(200);
        PublicationTablePageResponse result = service.search(1, 50, "title", "asc", q200);

        assertEquals(1, result.page());
        assertEquals("2024", result.items().getFirst().year());
        assertEquals("Author One", result.items().getFirst().authorNames().getFirst());
        assertEquals("Forum Primary", result.items().getFirst().forumName());

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(namedParameterJdbcTemplate).query(anyString(), paramsCaptor.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertEquals(50L, paramsCaptor.getValue().getValue("offset"));
        assertEquals("%" + q200 + "%", paramsCaptor.getValue().getValue("q"));
    }

    @Test
    void searchTreatsNullCountAsZeroAndReturnsOnePage() {
        when(namedParameterJdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        when(namedParameterJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(projectionReadPort.findAuthorsByIdIn(Set.of())).thenReturn(List.of());
        when(projectionReadPort.findForumsByIdIn(Set.of())).thenReturn(List.of());

        PublicationTablePageResponse result = service.search(9, 25, "title", "asc", null);

        assertEquals(0L, result.totalItems());
        assertEquals(1, result.totalPages());
        assertEquals(0, result.page());
    }

    @Test
    void findDetailSkipsForumLookupWhenForumBlankAndKeepsShortCoverDate() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_4");
        pub.setTitle("Publication D");
        pub.setCoverDate("99");
        pub.setForum(" ");
        pub.setAuthors(List.of("sa_only"));

        ScholardexAuthorView known = new ScholardexAuthorView();
        known.setId("sa_only");
        known.setName("Only Author");

        when(projectionReadPort.findPublicationByAnyId("spub_4")).thenReturn(Optional.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(List.of("sa_only"))).thenReturn(List.of(known));

        Optional<ScholardexPublicationDetailViewModel> result = service.findDetail("spub_4");

        assertTrue(result.isPresent());
        assertEquals("99", result.get().year());
        assertEquals(null, result.get().forumName());
    }

    @Test
    void findDetailHandlesDuplicateAuthorRowsByKeepingFirstName() {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_6");
        pub.setTitle("Publication F");
        pub.setCoverDate("2026-01-01");
        pub.setForum(null);
        pub.setAuthors(List.of("aid"));

        ScholardexAuthorView first = new ScholardexAuthorView();
        first.setId("aid");
        first.setName("First Name");
        ScholardexAuthorView duplicate = new ScholardexAuthorView();
        duplicate.setId("aid");
        duplicate.setName("Duplicate Name");

        when(projectionReadPort.findPublicationByAnyId("spub_6")).thenReturn(Optional.of(pub));
        when(projectionReadPort.findAuthorsByIdIn(List.of("aid"))).thenReturn(List.of(first, duplicate));

        Optional<ScholardexPublicationDetailViewModel> result = service.findDetail("spub_6");
        assertTrue(result.isPresent());
        assertEquals("First Name", result.get().authors().getFirst().name());
    }

    @Test
    void mapMinimalAndArrayHelpersCoverRowMapperBranches() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Array sqlArray = mock(Array.class);
        when(rs.getString("id")).thenReturn("spub_map");
        when(rs.getString("eid")).thenReturn("2-s2.0-map");
        when(rs.getString("title")).thenReturn("Mapped Title");
        when(rs.getString("cover_date")).thenReturn("2020-05-05");
        when(rs.getString("forum_id")).thenReturn("sforum_m");
        when(rs.getObject("cited_by_count", Integer.class)).thenReturn(null);
        when(rs.getArray("author_ids")).thenReturn(sqlArray);
        when(sqlArray.getArray()).thenReturn(new Object[]{"sa1", null, "sa2"});

        Method mapMinimal = ScholardexPublicationMvcService.class
                .getDeclaredMethod("mapMinimal", ResultSet.class, int.class);
        mapMinimal.setAccessible(true);
        ScholardexPublicationView mapped = (ScholardexPublicationView) mapMinimal.invoke(service, rs, 0);

        assertNotNull(mapped);
        assertEquals("spub_map", mapped.getId());
        assertEquals("2-s2.0-map", mapped.getEid());
        assertEquals("Mapped Title", mapped.getTitle());
        assertEquals("2020-05-05", mapped.getCoverDate());
        assertEquals("sforum_m", mapped.getForum());
        assertEquals(0, mapped.getCitedbyCount());
        assertEquals(List.of("sa1", "sa2"), mapped.getAuthors());

        Method toStringList = ScholardexPublicationMvcService.class
                .getDeclaredMethod("toStringList", Array.class);
        toStringList.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> empty = (List<String>) toStringList.invoke(service, new Object[]{null});
        assertTrue(empty.isEmpty());
    }
}
