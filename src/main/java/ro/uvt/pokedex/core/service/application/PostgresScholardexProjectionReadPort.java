package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostgresScholardexProjectionReadPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // --- Publication reads ---

    public List<ScholardexPublicationView> findPublicationsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapPublicationView
        );
    }

    public List<ScholardexPublicationView> findPublicationsByEidIn(Collection<String> eids) {
        if (isNullOrEmpty(eids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE eid IN (:eids)",
                new MapSqlParameterSource("eids", eids),
                this::mapPublicationView
        );
    }

    public Optional<ScholardexPublicationView> findPublicationById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return queryFirst(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id = :id",
                new MapSqlParameterSource("id", id),
                this::mapPublicationView
        );
    }

    public Optional<ScholardexPublicationView> findPublicationByEid(String eid) {
        if (eid == null || eid.isBlank()) return Optional.empty();
        return queryFirst(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE eid = :eid",
                new MapSqlParameterSource("eid", eid),
                this::mapPublicationView
        );
    }

    public Optional<ScholardexPublicationView> findPublicationByAnyId(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return queryFirst(
                """
                SELECT *
                FROM reporting_read.scholardex_publication_view
                WHERE id = :key OR eid = :key OR wos_id = :key OR google_scholar_id = :key
                ORDER BY updated_at DESC NULLS LAST
                LIMIT 1
                """,
                new MapSqlParameterSource("key", key),
                this::mapPublicationView
        );
    }

    public List<ScholardexPublicationView> findPublicationsByTitleContainingIgnoreCase(String title) {
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE title ILIKE :pattern ORDER BY cover_date DESC NULLS LAST",
                new MapSqlParameterSource("pattern", "%" + title + "%"),
                this::mapPublicationView
        );
    }

    public List<ScoringPublicationReadModel> findScoringPublicationsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapScoringPublication
        );
    }

    public Optional<ScoringPublicationReadModel> findScoringPublicationById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return queryFirst(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id = :id",
                new MapSqlParameterSource("id", id),
                this::mapScoringPublication
        );
    }

    public Set<String> findExistingPublicationIdsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return Set.of();
        List<String> results = namedParameterJdbcTemplate.query(
                "SELECT id FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                (rs, rowNum) -> rs.getString("id")
        );
        return new LinkedHashSet<>(results);
    }

    // --- Authorship / affiliation edge reads ---

    public Set<String> findPublicationIdsByAuthorIdIn(Collection<String> authorIds) {
        if (isNullOrEmpty(authorIds)) return Set.of();
        List<String> results = namedParameterJdbcTemplate.query(
                "SELECT publication_id FROM reporting_read.scholardex_authorship_fact WHERE author_id IN (:ids)",
                new MapSqlParameterSource("ids", authorIds),
                (rs, rowNum) -> rs.getString("publication_id")
        );
        return results.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> findAuthorIdsByAffiliationId(String affiliationId) {
        if (affiliationId == null || affiliationId.isBlank()) return Set.of();
        List<String> results = namedParameterJdbcTemplate.query(
                "SELECT DISTINCT author_id FROM reporting_read.scholardex_author_affiliation_fact WHERE affiliation_id = :id",
                new MapSqlParameterSource("id", affiliationId),
                (rs, rowNum) -> rs.getString("author_id")
        );
        return results.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * An author's affiliation ids from the author→affiliation EDGE table (the authoritative source; V2 does not
     * denormalize {@code affiliationIds} onto the author view, so consumers must read the edges).
     */
    public Set<String> findAffiliationIdsByAuthorId(String authorId) {
        if (authorId == null || authorId.isBlank()) return Set.of();
        return namedParameterJdbcTemplate.query(
                "SELECT DISTINCT affiliation_id FROM reporting_read.scholardex_author_affiliation_fact WHERE author_id = :id",
                new MapSqlParameterSource("id", authorId),
                (rs, rowNum) -> rs.getString("affiliation_id")
        ).stream().filter(id -> id != null && !id.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // --- Citation reads ---

    public List<ScholardexCitationView> findCitationsByCitedPublicationIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, cited_publication_id, citing_publication_id FROM reporting_read.scholardex_citation_fact WHERE cited_publication_id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapCitation
        );
    }

    public List<ScholardexCitationView> findCitationsByCitedPublicationId(String id) {
        if (id == null || id.isBlank()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, cited_publication_id, citing_publication_id FROM reporting_read.scholardex_citation_fact WHERE cited_publication_id = :id",
                new MapSqlParameterSource("id", id),
                this::mapCitation
        );
    }

    // --- Forum reads ---

    public List<ScholardexForumView> findForumsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, isbn, aggregation_type, publisher FROM reporting_read.scholardex_forum_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapForum
        );
    }

    /**
     * H69 (scoped WoS reads) / H67 2/2b: year-true WoS Core Collection membership for the given forums, scoped to the
     * given years — a targeted read over the {@code (forum_id, year, edition)}-keyed category view (vs loading a
     * forum's whole multi-year ranking blob). Returns forumId → set of years it was in SCIE/SSCI/AHCI. Empty inputs
     * short-circuit (avoids an empty {@code IN ()}). Core editions are SCIE/SSCI/AHCI; ESCI is excluded by policy.
     */
    public java.util.Map<String, Set<Integer>> findForumCoreCollectionYears(
            Collection<String> forumIds, Collection<Integer> years) {
        if (isNullOrEmpty(forumIds) || years == null || years.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Set<Integer>> out = new java.util.HashMap<>();
        namedParameterJdbcTemplate.query(
                "SELECT DISTINCT forum_id, year FROM reporting_read.scholardex_forum_category_view "
                        + "WHERE forum_id IN (:ids) AND year IN (:years) AND edition::text IN ('SCIE','SSCI','AHCI')",
                new MapSqlParameterSource().addValue("ids", forumIds).addValue("years", years),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.computeIfAbsent(rs.getString("forum_id"), k -> new java.util.HashSet<>()).add(rs.getInt("year")));
        return out;
    }

    /**
     * H67 (Hirsch "current year" option): forums **currently** in the WoS Core Collection — the snapshot
     * {@code forum_membership_view} (database SCIE/SSCI/AHCI), as opposed to the year-true
     * {@link #findForumCoreCollectionYears} ("item year"). Empty input short-circuits.
     */
    public Set<String> findForumsCurrentlyInCore(Collection<String> forumIds) {
        if (isNullOrEmpty(forumIds)) {
            return Set.of();
        }
        return new java.util.HashSet<>(namedParameterJdbcTemplate.queryForList(
                "SELECT DISTINCT forum_id FROM reporting_read.scholardex_forum_membership_view "
                        + "WHERE forum_id IN (:ids) AND database IN ('SCIE','SSCI','AHCI')",
                new MapSqlParameterSource("ids", forumIds), String.class));
    }

    public List<ScholardexForumView> findAllForums() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, isbn, aggregation_type, publisher FROM reporting_read.scholardex_forum_view",
                this::mapForum
        );
    }

    // --- Author reads ---

    public List<ScholardexAuthorView> findAuthorsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, alternative_names, affiliation_ids FROM reporting_read.scholardex_author_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapAuthor
        );
    }

    public List<ScholardexAuthorView> findAllAuthors() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, alternative_names, affiliation_ids FROM reporting_read.scholardex_author_view",
                this::mapAuthor
        );
    }

    public List<ScholardexAuthorView> findAuthorsByNameContainsIgnoreCase(String name) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, alternative_names, affiliation_ids FROM reporting_read.scholardex_author_view WHERE name ILIKE :pattern OR EXISTS (SELECT 1 FROM unnest(alternative_names) alt WHERE alt ILIKE :pattern)",
                new MapSqlParameterSource("pattern", "%" + name + "%"),
                this::mapAuthor
        );
    }

    // --- Affiliation reads ---

    public List<ScholardexAffiliationView> findAffiliationsByIdIn(Collection<String> ids) {
        if (isNullOrEmpty(ids)) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapAffiliation
        );
    }

    public List<ScholardexAffiliationView> findAllAffiliations() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view",
                this::mapAffiliation
        );
    }

    public List<ScholardexAffiliationView> findAffiliationsByCountry(String country) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE country = :country",
                new MapSqlParameterSource("country", country),
                this::mapAffiliation
        );
    }

    public List<ScholardexAffiliationView> findAffiliationsByNameContains(String name) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE name ILIKE :pattern",
                new MapSqlParameterSource("pattern", "%" + name + "%"),
                this::mapAffiliation
        );
    }

    // --- Mappers ---

    private ScholardexPublicationView mapPublicationView(ResultSet rs, int ignored) throws SQLException {
        ScholardexPublicationView publication = new ScholardexPublicationView();
        publication.setId(rs.getString("id"));
        publication.setDoi(rs.getString("doi"));
        publication.setDoiNormalized(rs.getString("doi_normalized"));
        publication.setEid(rs.getString("eid"));
        publication.setPii(rs.getString("pii"));
        publication.setPubmedId(rs.getString("pubmed_id"));
        publication.setWosId(rs.getString("wos_id"));
        publication.setGoogleScholarId(rs.getString("google_scholar_id"));
        publication.setTitle(rs.getString("title"));
        publication.setSubtype(rs.getString("subtype"));
        publication.setSubtypeDescription(rs.getString("subtype_description"));
        publication.setScopusSubtype(rs.getString("scopus_subtype"));
        publication.setScopusSubtypeDescription(rs.getString("scopus_subtype_description"));
        publication.setCreator(rs.getString("creator"));
        publication.setCoverDate(rs.getString("cover_date"));
        publication.setCoverDisplayDate(rs.getString("cover_display_date"));
        publication.setVolume(rs.getString("volume"));
        publication.setIssueIdentifier(rs.getString("issue_identifier"));
        publication.setDescription(rs.getString("description"));
        publication.setAuthKeywords(toStringList(rs.getArray("auth_keywords")));
        publication.setAuthorCount(readIntOrDefault(rs, "author_count"));
        publication.setCorrespondingAuthors(toStringList(rs.getArray("corresponding_authors")));
        publication.setOpenAccess(Boolean.TRUE.equals(rs.getObject("open_access", Boolean.class)));
        publication.setFreetoread(rs.getString("freetoread"));
        publication.setFreetoreadLabel(rs.getString("freetoread_label"));
        publication.setFundingId(rs.getString("funding_id"));
        publication.setArticleNumber(rs.getString("article_number"));
        publication.setPageRange(rs.getString("page_range"));
        publication.setApproved(Boolean.TRUE.equals(rs.getObject("approved", Boolean.class)));
        publication.setAuthors(toStringList(rs.getArray("author_ids")));
        publication.setAffiliations(toStringList(rs.getArray("affiliation_ids")));
        publication.setForum(rs.getString("forum_id"));
        publication.setBookId(rs.getString("book_id"));
        publication.setCitingPublicationIds(new LinkedHashSet<>(toStringList(rs.getArray("citing_publication_ids"))));
        publication.setCitedbyCount(readIntOrDefault(rs, "cited_by_count"));
        // H67: source-attributed incoming-citation counts (Scopus-venue / WoS-venue / graph total).
        publication.setGraphCitationCount(readIntOrDefault(rs, "graph_citation_count"));
        publication.setScopusCitationCount(readIntOrDefault(rs, "scopus_citation_count"));
        publication.setWosCitationCount(readIntOrDefault(rs, "wos_citation_count"));
        publication.setBuildVersion(rs.getString("build_version"));
        publication.setBuildAt(rs.getTimestamp("build_at") == null ? null : rs.getTimestamp("build_at").toInstant());
        publication.setUpdatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
        publication.setScopusLineage(rs.getString("scopus_lineage"));
        publication.setWosLineage(rs.getString("wos_lineage"));
        publication.setScholarLineage(rs.getString("scholar_lineage"));
        publication.setLinkerVersion(rs.getString("linker_version"));
        publication.setLinkerRunId(rs.getString("linker_run_id"));
        publication.setLinkedAt(rs.getTimestamp("linked_at") == null ? null : rs.getTimestamp("linked_at").toInstant());
        return publication;
    }

    private ScoringPublicationReadModel mapScoringPublication(ResultSet rs, int ignored) throws SQLException {
        return new ScoringPublication(
                rs.getString("id"),
                rs.getString("eid"),
                rs.getString("forum_id"),
                rs.getString("book_id"),
                rs.getString("cover_date"),
                rs.getString("subtype"),
                rs.getString("scopus_subtype"),
                toStringList(rs.getArray("author_ids")),
                readIntOrDefault(rs, "author_count"),
                rs.getString("doi"),
                rs.getString("wos_id"),
                rs.getString("title"),
                readIntOrDefault(rs, "cited_by_count"),
                new LinkedHashSet<>(toStringList(rs.getArray("citing_publication_ids")))
        );
    }

    private ScholardexForumView mapForum(ResultSet rs, int ignored) throws SQLException {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(rs.getString("id"));
        forum.setPublicationName(rs.getString("publication_name"));
        forum.setIssn(rs.getString("issn"));
        forum.setEIssn(rs.getString("e_issn"));
        forum.setIsbn(rs.getString("isbn"));
        forum.setAggregationType(rs.getString("aggregation_type"));
        forum.setPublisher(rs.getString("publisher"));
        forum.setScopusId(rs.getString("id"));
        return forum;
    }

    private ScholardexAuthorView mapAuthor(ResultSet rs, int ignored) throws SQLException {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(rs.getString("id"));
        author.setName(rs.getString("name"));
        author.setAlternativeNames(toStringList(rs.getArray("alternative_names")));
        List<ScholardexAffiliationView> affiliations = toStringList(rs.getArray("affiliation_ids")).stream()
                .map(affiliationId -> {
                    ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
                    affiliation.setId(affiliationId);
                    return affiliation;
                })
                .toList();
        author.setAffiliationIds(affiliations.stream().map(ScholardexAffiliationView::getId).toList());
        author.setAffiliations(affiliations);
        return author;
    }

    private ScholardexAffiliationView mapAffiliation(ResultSet rs, int ignored) throws SQLException {
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setId(rs.getString("id"));
        affiliation.setName(rs.getString("name"));
        affiliation.setCity(rs.getString("city"));
        affiliation.setCountry(rs.getString("country"));
        return affiliation;
    }

    private ScholardexCitationView mapCitation(ResultSet rs, int ignored) throws SQLException {
        ScholardexCitationView citation = new ScholardexCitationView();
        citation.setId(rs.getString("id"));
        citation.setCitedPublicationId(rs.getString("cited_publication_id"));
        citation.setCitingPublicationId(rs.getString("citing_publication_id"));
        return citation;
    }

    private List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] items) {
            return new ArrayList<>(List.of(items));
        }
        return List.of();
    }

    private <T> Optional<T> queryFirst(
            String sql,
            MapSqlParameterSource params,
            org.springframework.jdbc.core.RowMapper<T> mapper
    ) {
        List<T> results = namedParameterJdbcTemplate.query(sql, params, mapper);
        return results.stream().findFirst();
    }

    private boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private int readIntOrDefault(ResultSet rs, String column) throws SQLException {
        Integer value = rs.getObject(column, Integer.class);
        return value == null ? 0 : value;
    }
}
