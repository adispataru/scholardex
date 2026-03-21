package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

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
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresScholardexProjectionReadPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // --- Publication reads ---

    public List<Publication> findPublicationsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapPublication
        );
    }

    public List<Publication> findPublicationsByEidIn(Collection<String> eids) {
        if (eids == null || eids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE eid IN (:eids)",
                new MapSqlParameterSource("eids", eids),
                this::mapPublication
        );
    }

    public Optional<Publication> findPublicationById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        List<Publication> results = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id = :id",
                new MapSqlParameterSource("id", id),
                this::mapPublication
        );
        return results.stream().findFirst();
    }

    public Optional<Publication> findPublicationByEid(String eid) {
        if (eid == null || eid.isBlank()) return Optional.empty();
        List<Publication> results = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE eid = :eid",
                new MapSqlParameterSource("eid", eid),
                this::mapPublication
        );
        return results.stream().findFirst();
    }

    public Optional<Publication> findPublicationByAnyId(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        List<Publication> results = namedParameterJdbcTemplate.query(
                """
                SELECT *
                FROM reporting_read.scholardex_publication_view
                WHERE id = :key OR eid = :key OR wos_id = :key OR google_scholar_id = :key
                ORDER BY updated_at DESC NULLS LAST
                LIMIT 1
                """,
                new MapSqlParameterSource("key", key),
                this::mapPublication
        );
        return results.stream().findFirst();
    }

    public List<Publication> findPublicationsByTitleContainingIgnoreCase(String title) {
        return namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE title ILIKE :pattern ORDER BY cover_date DESC NULLS LAST",
                new MapSqlParameterSource("pattern", "%" + title + "%"),
                this::mapPublication
        );
    }

    public Set<String> findExistingPublicationIdsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        List<String> results = namedParameterJdbcTemplate.query(
                "SELECT id FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                (rs, rowNum) -> rs.getString("id")
        );
        return new LinkedHashSet<>(results);
    }

    // --- Authorship / affiliation edge reads ---

    public Set<String> findPublicationIdsByAuthorIdIn(Collection<String> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) return Set.of();
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

    // --- Citation reads ---

    public List<Citation> findCitationsByCitedPublicationIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, cited_publication_id, citing_publication_id FROM reporting_read.scholardex_citation_fact WHERE cited_publication_id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapCitation
        );
    }

    public List<Citation> findCitationsByCitedPublicationId(String id) {
        if (id == null || id.isBlank()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, cited_publication_id, citing_publication_id FROM reporting_read.scholardex_citation_fact WHERE cited_publication_id = :id",
                new MapSqlParameterSource("id", id),
                this::mapCitation
        );
    }

    // --- Forum reads ---

    public List<Forum> findForumsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapForum
        );
    }

    public List<Forum> findAllForums() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view",
                this::mapForum
        );
    }

    // --- Author reads ---

    public List<Author> findAuthorsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, affiliation_ids FROM reporting_read.scholardex_author_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapAuthor
        );
    }

    public List<Author> findAllAuthors() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, affiliation_ids FROM reporting_read.scholardex_author_view",
                this::mapAuthor
        );
    }

    public List<Author> findAuthorsByNameContainsIgnoreCase(String name) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, affiliation_ids FROM reporting_read.scholardex_author_view WHERE name ILIKE :pattern",
                new MapSqlParameterSource("pattern", "%" + name + "%"),
                this::mapAuthor
        );
    }

    // --- Affiliation reads ---

    public List<Affiliation> findAffiliationsByIdIn(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapAffiliation
        );
    }

    public List<Affiliation> findAllAffiliations() {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view",
                this::mapAffiliation
        );
    }

    public List<Affiliation> findAffiliationsByCountry(String country) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE country = :country",
                new MapSqlParameterSource("country", country),
                this::mapAffiliation
        );
    }

    public List<Affiliation> findAffiliationsByNameContains(String name) {
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, city, country FROM reporting_read.scholardex_affiliation_view WHERE name ILIKE :pattern",
                new MapSqlParameterSource("pattern", "%" + name + "%"),
                this::mapAffiliation
        );
    }

    // --- Mappers ---

    private Publication mapPublication(ResultSet rs, int ignored) throws SQLException {
        Publication publication = new Publication();
        publication.setId(rs.getString("id"));
        publication.setDoi(rs.getString("doi"));
        publication.setEid(rs.getString("eid"));
        publication.setWosId(rs.getString("wos_id"));
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
        publication.setAuthorCount(rs.getObject("author_count", Integer.class) == null ? 0 : rs.getObject("author_count", Integer.class));
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
        publication.setCitedBy(new LinkedHashSet<>(toStringList(rs.getArray("citing_publication_ids"))));
        Integer citedByCount = rs.getObject("cited_by_count", Integer.class);
        publication.setCitedbyCount(citedByCount == null ? 0 : citedByCount);
        return publication;
    }

    private Forum mapForum(ResultSet rs, int ignored) throws SQLException {
        Forum forum = new Forum();
        forum.setId(rs.getString("id"));
        forum.setPublicationName(rs.getString("publication_name"));
        forum.setIssn(rs.getString("issn"));
        forum.setEIssn(rs.getString("e_issn"));
        forum.setAggregationType(rs.getString("aggregation_type"));
        return forum;
    }

    private Author mapAuthor(ResultSet rs, int ignored) throws SQLException {
        Author author = new Author();
        author.setId(rs.getString("id"));
        author.setName(rs.getString("name"));
        List<Affiliation> affiliations = toStringList(rs.getArray("affiliation_ids")).stream()
                .map(affiliationId -> {
                    Affiliation affiliation = new Affiliation();
                    affiliation.setAfid(affiliationId);
                    return affiliation;
                })
                .toList();
        author.setAffiliations(affiliations);
        return author;
    }

    private Affiliation mapAffiliation(ResultSet rs, int ignored) throws SQLException {
        Affiliation affiliation = new Affiliation();
        affiliation.setAfid(rs.getString("id"));
        affiliation.setName(rs.getString("name"));
        affiliation.setCity(rs.getString("city"));
        affiliation.setCountry(rs.getString("country"));
        return affiliation;
    }

    private Citation mapCitation(ResultSet rs, int ignored) throws SQLException {
        Citation citation = new Citation();
        citation.setId(rs.getString("id"));
        citation.setCitedId(rs.getString("cited_publication_id"));
        citation.setCitingId(rs.getString("citing_publication_id"));
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
}
