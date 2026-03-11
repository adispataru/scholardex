package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(DataSource.class)
public class PostgresAdminScopusReadPort implements AdminScopusReadPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        String normalizedTitle = paperTitle == null ? "" : paperTitle.trim();
        List<Publication> publications;
        if (normalizedTitle.isBlank()) {
            publications = namedParameterJdbcTemplate.query(
                    "SELECT * FROM reporting_read.scholardex_publication_view",
                    this::mapPublication
            );
        } else {
            publications = namedParameterJdbcTemplate.query(
                    "SELECT * FROM reporting_read.scholardex_publication_view WHERE title ILIKE :pattern",
                    new MapSqlParameterSource("pattern", "%" + normalizedTitle + "%"),
                    this::mapPublication
            );
        }

        publications.sort(PublicationOrderingSupport.publicationComparator());

        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        Map<String, Author> authorMap = findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));

        return new AdminScopusPublicationSearchViewModel(publications, authorMap);
    }

    @Override
    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        if (publicationId == null || publicationId.isBlank()) {
            return Optional.empty();
        }

        Optional<Publication> publicationOpt = findPublicationByAnyId(publicationId.trim());
        if (publicationOpt.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = publicationOpt.get();

        List<Citation> allByCited = namedParameterJdbcTemplate.query(
                "SELECT cited_publication_id, citing_publication_id FROM reporting_read.mv_scholardex_citation_context WHERE cited_publication_id = :citedId",
                new MapSqlParameterSource("citedId", publication.getId()),
                (rs, rowNum) -> {
                    Citation citation = new Citation();
                    citation.setCitedId(rs.getString("cited_publication_id"));
                    citation.setCitingId(rs.getString("citing_publication_id"));
                    return citation;
                }
        );

        List<Publication> citations = namedParameterJdbcTemplate.query(
                """
                        SELECT citing_publication_id, citing_title, citing_cover_date, citing_forum_id,
                               citing_author_ids, citing_eid, citing_wos_id, citing_google_scholar_id
                        FROM reporting_read.mv_scholardex_citation_context
                        WHERE cited_publication_id = :citedId
                        """,
                new MapSqlParameterSource("citedId", publication.getId()),
                (rs, rowNum) -> {
                    Publication citing = new Publication();
                    citing.setId(rs.getString("citing_publication_id"));
                    citing.setTitle(rs.getString("citing_title"));
                    citing.setCoverDate(rs.getString("citing_cover_date"));
                    citing.setForum(rs.getString("citing_forum_id"));
                    citing.setAuthors(toStringList(rs.getArray("citing_author_ids")));
                    citing.setEid(rs.getString("citing_eid"));
                    citing.setWosId(rs.getString("citing_wos_id"));
                    return citing;
                }
        );
        PublicationOrderingSupport.sortPublicationsInPlace(citations);

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citations.forEach(citation -> {
            authorKeys.addAll(citation.getAuthors());
            if (citation.getForum() != null) {
                forumKeys.add(citation.getForum());
            }
        });

        Map<String, Author> authorMap = findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author, (left, right) -> left, HashMap::new));
        Map<String, Forum> forumMap = findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum, (left, right) -> left, HashMap::new));

        Forum publicationForum = findForumById(publication.getForum()).orElse(null);

        return Optional.of(new AdminScopusCitationsViewModel(
                publication,
                publicationForum,
                citations,
                authorMap,
                forumMap
        ));
    }

    private Optional<Publication> findPublicationByAnyId(String key) {
        List<Publication> publications = namedParameterJdbcTemplate.query(
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
        return publications.stream().findFirst();
    }

    private List<Publication> findPublicationsByIdIn(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Publication> publications = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapPublication
        );
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
    }

    private List<Author> findAuthorsByIdIn(Collection<String> authorIds) {
        if (authorIds.isEmpty()) {
            return List.of();
        }
        return namedParameterJdbcTemplate.query(
                "SELECT id, name FROM reporting_read.scholardex_author_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", authorIds),
                (rs, rowNum) -> {
                    Author author = new Author();
                    author.setId(rs.getString("id"));
                    author.setName(rs.getString("name"));
                    return author;
                }
        );
    }

    private List<Forum> findForumsByIdIn(Collection<String> forumIds) {
        if (forumIds.isEmpty()) {
            return List.of();
        }
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", forumIds),
                this::mapForum
        );
    }

    private Optional<Forum> findForumById(String forumId) {
        if (forumId == null || forumId.isBlank()) {
            return Optional.empty();
        }
        List<Forum> forums = namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view WHERE id = :id",
                new MapSqlParameterSource("id", forumId),
                this::mapForum
        );
        return forums.stream().findFirst();
    }

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
        publication.setCitedBy(new HashSet<>(toStringList(rs.getArray("citing_publication_ids"))));
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
