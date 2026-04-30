package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.service.application.model.ScholardexCitationsView;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipDecisionAdminSummary;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationSearchView;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostgresScholardexAdminReadPort {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PublicationAuthorshipDecisionRepository publicationAuthorshipDecisionRepository;

    public record PublicationCatalogPage(
            List<ScholardexPublicationView> content,
            Map<String, ScholardexAuthorView> authorMap,
            Map<String, ScholardexForumView> forumMap,
            Map<String, PublicationAuthorshipDecisionAdminSummary> decisionSummaryByPublicationId,
            long total,
            int page,
            int size,
            int totalPages
    ) {
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page < totalPages - 1; }
    }

    public PublicationCatalogPage buildPublicationCatalogPage(
            String q, String forumId, String authorId, String affiliationId,
            int page, int size, String sort, String direction
    ) {
        int safeSize = normalizePageSize(size);
        String safeSort = normalizeSort(sort);
        String safeDir = normalizeDirection(direction);

        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (q != null && !q.isBlank()) {
            conditions.add("title ILIKE :q");
            params.addValue("q", "%" + q.trim() + "%");
        }
        if (forumId != null && !forumId.isBlank()) {
            conditions.add("forum_id = :forumId");
            params.addValue("forumId", forumId.trim());
        }
        if (authorId != null && !authorId.isBlank()) {
            conditions.add(":authorId = ANY(author_ids)");
            params.addValue("authorId", authorId.trim());
        }
        if (affiliationId != null && !affiliationId.isBlank()) {
            conditions.add(":affiliationId = ANY(affiliation_ids)");
            params.addValue("affiliationId", affiliationId.trim());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

        Long total = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.scholardex_publication_view " + where, params, Long.class);
        long totalCount = total == null ? 0L : total;
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / safeSize);
        int safePage = normalizePage(page, totalPages);

        params.addValue("limit", safeSize);
        params.addValue("offset", (long) safePage * safeSize);
        List<ScholardexPublicationView> publications = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view " + where
                        + " ORDER BY " + safeSort + " " + safeDir + " LIMIT :limit OFFSET :offset",
                params, this::mapPublication);

        Set<String> authorKeys = publications.stream().flatMap(p -> p.getAuthors().stream()).collect(Collectors.toCollection(HashSet::new));
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum)
                .filter(f -> f != null && !f.isBlank()).collect(Collectors.toCollection(HashSet::new));
        Map<String, ScholardexAuthorView> authorMap = toIdMap(findAuthorsByIdIn(authorKeys), ScholardexAuthorView::getId);
        Map<String, ScholardexForumView> forumMap = toIdMap(findForumsByIdIn(forumKeys), ScholardexForumView::getId);
        Map<String, PublicationAuthorshipDecisionAdminSummary> decisionMap = buildDecisionSummaryByPublicationId(publications);

        return new PublicationCatalogPage(publications, authorMap, forumMap, decisionMap, totalCount, safePage, safeSize, totalPages);
    }

    public ScholardexPublicationSearchView buildPublicationSearchView(String paperTitle) {
        String normalizedTitle = paperTitle == null ? "" : paperTitle.trim();
        List<ScholardexPublicationView> publications;
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
        Map<String, ScholardexAuthorView> authorMap = findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author));
        Map<String, PublicationAuthorshipDecisionAdminSummary> decisionSummaryByPublicationId =
                buildDecisionSummaryByPublicationId(publications);

        return new ScholardexPublicationSearchView(publications, authorMap, decisionSummaryByPublicationId);
    }

    public Optional<ScholardexCitationsView> buildPublicationCitationsView(String publicationId, int page, int size) {
        if (publicationId == null || publicationId.isBlank()) {
            return Optional.empty();
        }

        Optional<ScholardexPublicationView> publicationOpt = findPublicationByAnyId(publicationId.trim());
        if (publicationOpt.isEmpty()) {
            return Optional.empty();
        }
        ScholardexPublicationView publication = publicationOpt.get();

        int safeSize = normalizePageSize(size);
        Long totalCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reporting_read.mv_scholardex_citation_context WHERE cited_publication_id = :citedId",
                new MapSqlParameterSource("citedId", publication.getId()), Long.class);
        long total = totalCount == null ? 0L : totalCount;
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / safeSize);
        int safePage = normalizePage(page, totalPages);

        List<ScholardexPublicationView> citations = namedParameterJdbcTemplate.query(
                """
                        SELECT citing_publication_id, citing_title, citing_cover_date, citing_forum_id,
                               citing_author_ids, citing_eid, citing_wos_id, citing_google_scholar_id
                        FROM reporting_read.mv_scholardex_citation_context
                        WHERE cited_publication_id = :citedId
                        ORDER BY citing_cover_date DESC NULLS LAST, citing_title ASC
                        LIMIT :limit OFFSET :offset
                        """,
                new MapSqlParameterSource("citedId", publication.getId())
                        .addValue("limit", safeSize)
                        .addValue("offset", (long) safePage * safeSize),
                (rs, rowNum) -> {
                    ScholardexPublicationView citing = new ScholardexPublicationView();
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

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citations.forEach(citation -> {
            authorKeys.addAll(citation.getAuthors());
            if (citation.getForum() != null) {
                forumKeys.add(citation.getForum());
            }
        });

        Map<String, ScholardexAuthorView> authorMap = findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author, (left, right) -> left, HashMap::new));
        Map<String, ScholardexForumView> forumMap = toIdMap(findForumsByIdIn(forumKeys), ScholardexForumView::getId);

        ScholardexForumView publicationForum = findForumById(publication.getForum()).orElse(null);

        return Optional.of(new ScholardexCitationsView(
                publication,
                publicationForum,
                citations,
                authorMap,
                forumMap,
                total,
                safePage,
                safeSize,
                totalPages
        ));
    }

    private Optional<ScholardexPublicationView> findPublicationByAnyId(String key) {
        List<ScholardexPublicationView> publications = namedParameterJdbcTemplate.query(
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

    private List<ScholardexPublicationView> findPublicationsByIdIn(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<ScholardexPublicationView> publications = namedParameterJdbcTemplate.query(
                "SELECT * FROM reporting_read.scholardex_publication_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                this::mapPublication
        );
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
    }

    private List<ScholardexAuthorView> findAuthorsByIdIn(Collection<String> authorIds) {
        if (authorIds.isEmpty()) {
            return List.of();
        }
        return namedParameterJdbcTemplate.query(
                "SELECT id, name, alternative_names, affiliation_ids FROM reporting_read.scholardex_author_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", authorIds),
                (rs, rowNum) -> {
                    ScholardexAuthorView author = new ScholardexAuthorView();
                    author.setId(rs.getString("id"));
                    author.setName(rs.getString("name"));
                    author.setAlternativeNames(toStringList(rs.getArray("alternative_names")));
                    List<ScholardexAffiliationView> affiliations = toStringList(rs.getArray("affiliation_ids")).stream()
                            .map(affiliationId -> {
                                ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
                                affiliation.setAfid(affiliationId);
                                return affiliation;
                            })
                            .toList();
                    author.setAffiliationIds(affiliations.stream().map(ScholardexAffiliationView::getAfid).toList());
                    author.setAffiliations(affiliations);
                    return author;
                }
        );
    }

    private List<ScholardexForumView> findForumsByIdIn(Collection<String> forumIds) {
        if (forumIds.isEmpty()) {
            return List.of();
        }
        return namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", forumIds),
                this::mapForum
        );
    }

    private Optional<ScholardexForumView> findForumById(String forumId) {
        if (forumId == null || forumId.isBlank()) {
            return Optional.empty();
        }
        List<ScholardexForumView> forums = namedParameterJdbcTemplate.query(
                "SELECT id, publication_name, issn, e_issn, aggregation_type FROM reporting_read.scholardex_forum_view WHERE id = :id",
                new MapSqlParameterSource("id", forumId),
                this::mapForum
        );
        return forums.stream().findFirst();
    }

    private ScholardexPublicationView mapPublication(ResultSet rs, int ignored) throws SQLException {
        ScholardexPublicationView publication = new ScholardexPublicationView();
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
        publication.setCitingPublicationIds(new LinkedHashSet<>(toStringList(rs.getArray("citing_publication_ids"))));
        Integer citedByCount = rs.getObject("cited_by_count", Integer.class);
        publication.setCitedbyCount(citedByCount == null ? 0 : citedByCount);
        return publication;
    }

    private ScholardexForumView mapForum(ResultSet rs, int ignored) throws SQLException {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(rs.getString("id"));
        forum.setPublicationName(rs.getString("publication_name"));
        forum.setIssn(rs.getString("issn"));
        forum.setEIssn(rs.getString("e_issn"));
        forum.setAggregationType(rs.getString("aggregation_type"));
        return forum;
    }

    private Map<String, PublicationAuthorshipDecisionAdminSummary> buildDecisionSummaryByPublicationId(
            List<ScholardexPublicationView> publications) {
        List<String> publicationIds = publications.stream()
                .map(ScholardexPublicationView::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (publicationIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<PublicationAuthorshipDecision>> decisionsByPublicationId =
                publicationAuthorshipDecisionRepository.findByPublicationIdIn(publicationIds).stream()
                        .filter(decision -> decision.getPublicationId() != null && !decision.getPublicationId().isBlank())
                        .collect(Collectors.groupingBy(PublicationAuthorshipDecision::getPublicationId));

        Map<String, PublicationAuthorshipDecisionAdminSummary> summaries = new HashMap<>();
        decisionsByPublicationId.forEach((publicationId, decisions) -> {
            int confirmedCount = (int) decisions.stream()
                    .filter(decision -> decision.getStatus() == PublicationAuthorshipDecision.Status.CONFIRMED)
                    .count();
            int rejectedCount = (int) decisions.stream()
                    .filter(decision -> decision.getStatus() == PublicationAuthorshipDecision.Status.REJECTED)
                    .count();
            PublicationAuthorshipDecision latestDecision = decisions.stream()
                    .filter(decision -> decision.getUpdatedAt() != null)
                    .max(java.util.Comparator.comparing(PublicationAuthorshipDecision::getUpdatedAt))
                    .orElseGet(() -> decisions.get(0));
            summaries.put(publicationId, new PublicationAuthorshipDecisionAdminSummary(
                    decisions.size(),
                    confirmedCount,
                    rejectedCount,
                    latestDecision.getStatus(),
                    latestDecision.getUpdatedAt()
            ));
        });
        return summaries;
    }

    public int bulkReassignForum(List<String> publicationIds, String newForumId) {
        if (publicationIds == null || publicationIds.isEmpty()) return 0;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("forumId", newForumId != null && !newForumId.isBlank() ? newForumId.trim() : null)
                .addValue("ids", publicationIds);
        return namedParameterJdbcTemplate.update(
                "UPDATE reporting_read.scholardex_publication_view SET forum_id = :forumId WHERE id IN (:ids)",
                params);
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

    private int normalizePageSize(int size) {
        return (size == 50 || size == 100) ? size : 25;
    }

    private String normalizeSort(String sort) {
        return switch (sort != null ? sort : "") {
            case "year" -> "cover_date";
            case "citations" -> "cited_by_count";
            default -> "title";
        };
    }

    private String normalizeDirection(String direction) {
        return "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
    }

    private int normalizePage(int page, int totalPages) {
        return Math.max(0, Math.min(page, totalPages - 1));
    }

    private <T> Map<String, T> toIdMap(Collection<T> values, java.util.function.Function<T, String> idGetter) {
        return values.stream().collect(Collectors.toMap(idGetter, value -> value, (left, right) -> left, HashMap::new));
    }
}
