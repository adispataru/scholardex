package ro.uvt.pokedex.core.service.importing.scopus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ScopusProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScopusProjectionBuilderService.class);
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    private static final int JDBC_BATCH_SIZE = 500;

    private final ScopusForumFactRepository forumFactRepository;
    private final ScholardexForumFactRepository canonicalForumFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final MongoTemplate mongoTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ScopusProjectionBuilderService(
            ScopusForumFactRepository forumFactRepository,
            ScholardexForumFactRepository canonicalForumFactRepository,
            ScholardexAuthorFactRepository authorFactRepository,
            ScholardexAffiliationFactRepository affiliationFactRepository,
            ScholardexPublicationFactRepository publicationFactRepository,
            MongoTemplate mongoTemplate,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.forumFactRepository = forumFactRepository;
        this.canonicalForumFactRepository = canonicalForumFactRepository;
        this.authorFactRepository = authorFactRepository;
        this.affiliationFactRepository = affiliationFactRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.mongoTemplate = mongoTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ImportProcessingResult rebuildViews() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        long totalStartedAtNanos = System.nanoTime();
        try {
            // --- build forum views ---
            long forumStartedAtNanos = System.nanoTime();
            List<ScopusForumFact> forumFacts = new ArrayList<>(forumFactRepository.findAll());
            forumFacts.sort(Comparator.comparing(ScopusForumFact::getSourceId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexForumView> forumViews = forumFacts.stream()
                    .map(fact -> toForumView(fact, buildVersion, buildAt))
                    .collect(Collectors.toCollection(ArrayList::new));
            mergeWosOnlyForumViews(forumViews, buildVersion, buildAt);
            markImported(result, forumViews.size());
            long forumMs = nanosToMillis(System.nanoTime() - forumStartedAtNanos);

            // --- build author views ---
            long authorStartedAtNanos = System.nanoTime();
            List<ScholardexAuthorFact> authorFacts = new ArrayList<>(authorFactRepository.findAll());
            authorFacts.sort(Comparator.comparing(ScholardexAuthorFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexAuthorView> authorViews = authorFacts.stream()
                    .map(fact -> toAuthorView(fact, buildVersion, buildAt))
                    .toList();
            markImported(result, authorViews.size());
            long authorMs = nanosToMillis(System.nanoTime() - authorStartedAtNanos);

            // --- build affiliation views ---
            long affiliationStartedAtNanos = System.nanoTime();
            List<ScholardexAffiliationFact> affiliationFacts = new ArrayList<>(affiliationFactRepository.findAll());
            affiliationFacts.sort(Comparator.comparing(ScholardexAffiliationFact::getId, Comparator.nullsLast(String::compareTo)));
            List<ScholardexAffiliationView> affiliationViews = affiliationFacts.stream()
                    .map(fact -> toAffiliationView(fact, buildVersion, buildAt))
                    .toList();
            markImported(result, affiliationViews.size());
            long affiliationMs = nanosToMillis(System.nanoTime() - affiliationStartedAtNanos);

            // --- build publication views ---
            long publicationStartedAtNanos = System.nanoTime();
            List<ScholardexPublicationFact> publicationFacts = new ArrayList<>(publicationFactRepository.findAll());
            publicationFacts.sort(Comparator.comparing(ScholardexPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
            long citationMapStartedAtNanos = System.nanoTime();
            Map<String, List<String>> citingByCited = buildCitingMap();
            long citationMapMs = nanosToMillis(System.nanoTime() - citationMapStartedAtNanos);
            List<ScholardexPublicationView> publicationViews = new ArrayList<>(publicationFacts.size());
            for (ScholardexPublicationFact fact : publicationFacts) {
                publicationViews.add(toPublicationView(fact, citingByCited, buildVersion, buildAt));
            }
            markImported(result, publicationViews.size());
            long publicationMs = nanosToMillis(System.nanoTime() - publicationStartedAtNanos);

            // --- load edge facts from MongoDB for PG insertion ---
            long edgeLoadNs = System.nanoTime();
            List<ScholardexCitationFact> citationFacts = mongoTemplate.find(
                    new Query().with(Sort.by(
                            Sort.Order.asc("citedPublicationId"),
                            Sort.Order.asc("citingPublicationId"),
                            Sort.Order.asc("source"),
                            Sort.Order.asc("_id")
                    )),
                    ScholardexCitationFact.class
            );
            List<ScholardexAuthorshipFact> authorshipFacts = mongoTemplate.find(
                    new Query().with(Sort.by(
                            Sort.Order.asc("publicationId"),
                            Sort.Order.asc("authorId"),
                            Sort.Order.asc("source"),
                            Sort.Order.asc("_id")
                    )),
                    ScholardexAuthorshipFact.class
            );
            List<ScholardexAuthorAffiliationFact> authorAffiliationFacts = mongoTemplate.find(
                    new Query().with(Sort.by(
                            Sort.Order.asc("authorId"),
                            Sort.Order.asc("affiliationId"),
                            Sort.Order.asc("source"),
                            Sort.Order.asc("_id")
                    )),
                    ScholardexAuthorAffiliationFact.class
            );
            long edgeLoadMs = nanosToMillis(System.nanoTime() - edgeLoadNs);

            // --- validate edge facts against built view IDs ---
            Set<String> publicationIds = publicationViews.stream()
                    .map(ScholardexPublicationView::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));
            Set<String> authorIds = authorViews.stream()
                    .map(ScholardexAuthorView::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));
            Set<String> affiliationIds = affiliationViews.stream()
                    .map(ScholardexAffiliationView::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));

            List<ScholardexCitationFact> validCitationFacts = citationFacts.stream()
                    .filter(row -> row.getCitedPublicationId() != null
                            && row.getCitingPublicationId() != null
                            && publicationIds.contains(row.getCitedPublicationId())
                            && publicationIds.contains(row.getCitingPublicationId()))
                    .toList();
            List<ScholardexAuthorshipFact> validAuthorshipFacts = authorshipFacts.stream()
                    .filter(row -> row.getPublicationId() != null
                            && row.getAuthorId() != null
                            && publicationIds.contains(row.getPublicationId())
                            && authorIds.contains(row.getAuthorId()))
                    .toList();
            List<ScholardexAuthorAffiliationFact> validAuthorAffiliationFacts = authorAffiliationFacts.stream()
                    .filter(row -> row.getAuthorId() != null
                            && row.getAffiliationId() != null
                            && authorIds.contains(row.getAuthorId())
                            && affiliationIds.contains(row.getAffiliationId()))
                    .toList();

            int droppedCitations = citationFacts.size() - validCitationFacts.size();
            int droppedAuthorship = authorshipFacts.size() - validAuthorshipFacts.size();
            int droppedAuthorAffiliation = authorAffiliationFacts.size() - validAuthorAffiliationFacts.size();
            if (droppedCitations > 0 || droppedAuthorship > 0 || droppedAuthorAffiliation > 0) {
                log.warn("Scopus edge filter: droppedCitations={} droppedAuthorship={} droppedAuthorAffiliation={} reason=missing-parent-rows",
                        droppedCitations, droppedAuthorship, droppedAuthorAffiliation);
            }

            // --- write all 7 tables to PostgreSQL atomically ---
            long writePgNs = System.nanoTime();
            List<ScholardexForumView> forumViewsForWrite = forumViews;
            List<ScholardexAuthorView> authorViewsForWrite = authorViews;
            List<ScholardexAffiliationView> affiliationViewsForWrite = affiliationViews;
            List<ScholardexPublicationView> publicationViewsForWrite = publicationViews;
            List<ScholardexCitationFact> citationFactsForWrite = validCitationFacts;
            List<ScholardexAuthorshipFact> authorshipFactsForWrite = validAuthorshipFacts;
            List<ScholardexAuthorAffiliationFact> authorAffiliationFactsForWrite = validAuthorAffiliationFacts;
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("""
                        TRUNCATE TABLE
                            reporting_read.scholardex_author_affiliation_fact,
                            reporting_read.scholardex_authorship_fact,
                            reporting_read.scholardex_citation_fact,
                            reporting_read.scholardex_publication_view,
                            reporting_read.scholardex_affiliation_view,
                            reporting_read.scholardex_author_view,
                            reporting_read.scholardex_forum_view
                        """);
                insertForumRows(forumViewsForWrite);
                insertAuthorRows(authorViewsForWrite);
                insertAffiliationRows(affiliationViewsForWrite);
                insertPublicationRows(publicationViewsForWrite);
                insertCitationRows(citationFactsForWrite);
                insertAuthorshipRows(authorshipFactsForWrite);
                insertAuthorAffiliationRows(authorAffiliationFactsForWrite);
            });
            long writePgMs = nanosToMillis(System.nanoTime() - writePgNs);

            long totalMs = nanosToMillis(System.nanoTime() - totalStartedAtNanos);
            log.info("Scopus projection rebuild complete: buildVersion={} forums={} authors={} affiliations={} publications={} citations={} authorships={} authorAffiliations={} timingsMs[forums={} authors={} affiliations={} citationMap={} publications={} edgeLoad={} writePg={} total={}]",
                    buildVersion, forumViews.size(), authorViews.size(), affiliationViews.size(), publicationViews.size(),
                    validCitationFacts.size(), validAuthorshipFacts.size(), validAuthorAffiliationFacts.size(),
                    forumMs, authorMs, affiliationMs, citationMapMs, publicationMs, edgeLoadMs, writePgMs, totalMs);
        } catch (Exception e) {
            result.markError("scopus-projection-rebuild-error=" + e.getMessage());
            log.error("Scopus projection rebuild failed", e);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Derivation helpers (unchanged)
    // -------------------------------------------------------------------------

    private ScholardexForumView toForumView(ScopusForumFact fact, String buildVersion, Instant buildAt) {
        ScholardexForumView view = new ScholardexForumView();
        view.setId(fact.getSourceId());
        view.setPublicationName(fact.getPublicationName());
        view.setIssn(fact.getIssn());
        view.setEIssn(fact.getEIssn());
        view.setAggregationType(fact.getAggregationType());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private void mergeWosOnlyForumViews(List<ScholardexForumView> forumViews, String buildVersion, Instant buildAt) {
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(canonicalForumFactRepository.findAll());
        canonicalForums.sort(Comparator.comparing(ScholardexForumFact::getId, Comparator.nullsLast(String::compareTo)));
        Set<String> existingIds = forumViews.stream()
                .map(ScholardexForumView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            if (!safeList(canonicalForum.getScopusForumIds()).isEmpty()) {
                continue;
            }
            if (canonicalForum.getId() == null || existingIds.contains(canonicalForum.getId())) {
                continue;
            }
            ScholardexForumView wosView = new ScholardexForumView();
            wosView.setId(canonicalForum.getId());
            wosView.setPublicationName(canonicalForum.getName());
            wosView.setIssn(canonicalForum.getIssn());
            wosView.setEIssn(canonicalForum.getEIssn());
            wosView.setAggregationType(canonicalForum.getAggregationType());
            wosView.setBuildVersion(buildVersion);
            wosView.setBuildAt(buildAt);
            wosView.setUpdatedAt(buildAt);
            wosView.setSourceEventId(canonicalForum.getSourceEventId());
            forumViews.add(wosView);
            existingIds.add(canonicalForum.getId());
        }
    }

    private ScholardexAuthorView toAuthorView(ScholardexAuthorFact fact, String buildVersion, Instant buildAt) {
        ScholardexAuthorView view = new ScholardexAuthorView();
        view.setId(fact.getId());
        view.setName(fact.getDisplayName());
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScholardexAffiliationView toAffiliationView(ScholardexAffiliationFact fact, String buildVersion, Instant buildAt) {
        ScholardexAffiliationView view = new ScholardexAffiliationView();
        view.setId(fact.getId());
        view.setName(fact.getName());
        view.setCity(fact.getCity());
        view.setCountry(fact.getCountry());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScholardexPublicationView toPublicationView(
            ScholardexPublicationFact fact,
            Map<String, List<String>> citingByCited,
            String buildVersion,
            Instant buildAt
    ) {
        ScholardexPublicationView view = new ScholardexPublicationView();
        view.setId(fact.getId());
        view.setDoi(fact.getDoi());
        view.setDoiNormalized(normalizeDoi(fact.getDoi()));
        view.setEid(fact.getEid());
        view.setTitle(fact.getTitle());
        view.setSubtype(fact.getSubtype());
        view.setSubtypeDescription(fact.getSubtypeDescription());
        view.setScopusSubtype(fact.getScopusSubtype());
        view.setScopusSubtypeDescription(fact.getScopusSubtypeDescription());
        view.setCreator(fact.getCreator());
        view.setCoverDate(fact.getCoverDate());
        view.setCoverDisplayDate(fact.getCoverDisplayDate());
        view.setVolume(fact.getVolume());
        view.setIssueIdentifier(fact.getIssueIdentifier());
        view.setDescription(fact.getDescription());
        view.setAuthorCount(fact.getAuthorCount());
        view.setCorrespondingAuthors(fact.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(fact.getCorrespondingAuthors()));
        view.setOpenAccess(Boolean.TRUE.equals(fact.getOpenAccess()));
        view.setFreetoread(fact.getFreetoread());
        view.setFreetoreadLabel(fact.getFreetoreadLabel());
        view.setFundingId(fact.getFundingId());
        view.setArticleNumber(fact.getArticleNumber());
        view.setPageRange(fact.getPageRange());
        view.setApproved(Boolean.TRUE.equals(fact.getApproved()));
        view.setAuthorIds(fact.getAuthorIds() == null ? List.of() : new ArrayList<>(fact.getAuthorIds()));
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setForumId(fact.getForumId());
        List<String> citingPublicationIds = citingByCited.getOrDefault(fact.getId(), List.of());
        view.setCitingPublicationIds(new ArrayList<>(citingPublicationIds));
        view.setCitedByCount(fact.getCitedByCount() == null ? citingPublicationIds.size() : fact.getCitedByCount());
        view.setWosId(fact.getWosId());
        view.setGoogleScholarId(fact.getGoogleScholarId());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setScopusLineage(fact.getSourceEventId());
        view.setWosLineage(fact.getWosId() == null ? null : fact.getSource());
        view.setScholarLineage(fact.getGoogleScholarId() == null ? null : fact.getSource());
        return view;
    }

    private Map<String, List<String>> buildCitingMap() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<ScholardexCitationFact> facts = new ArrayList<>(mongoTemplate.find(new Query(), ScholardexCitationFact.class));
        facts.sort(Comparator
                .comparing(ScholardexCitationFact::getCitedPublicationId, Comparator.nullsLast(String::compareTo))
                .thenComparing(ScholardexCitationFact::getCitingPublicationId, Comparator.nullsLast(String::compareTo)));
        for (ScholardexCitationFact fact : facts) {
            if (fact.getCitedPublicationId() == null || fact.getCitingPublicationId() == null) {
                continue;
            }
            out.computeIfAbsent(fact.getCitedPublicationId(), key -> new ArrayList<>());
            List<String> values = out.get(fact.getCitedPublicationId());
            if (!values.contains(fact.getCitingPublicationId())) {
                values.add(fact.getCitingPublicationId());
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // PostgreSQL write methods
    // -------------------------------------------------------------------------

    private void insertForumRows(List<ScholardexForumView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_forum_view
                    (id, publication_name, issn, e_issn, aggregation_type, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexForumView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getPublicationName());
                ps.setString(3, row.getIssn());
                ps.setString(4, row.getEIssn());
                ps.setString(5, row.getAggregationType());
                ps.setString(6, row.getBuildVersion());
                setInstant(ps, 7, row.getBuildAt());
                setInstant(ps, 8, row.getUpdatedAt());
                ps.setString(9, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertAuthorRows(List<ScholardexAuthorView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_author_view
                    (id, name, affiliation_ids, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAuthorView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setArray(3, textArray(ps.getConnection(), row.getAffiliationIds()));
                ps.setString(4, row.getBuildVersion());
                setInstant(ps, 5, row.getBuildAt());
                setInstant(ps, 6, row.getUpdatedAt());
                ps.setString(7, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertAffiliationRows(List<ScholardexAffiliationView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_affiliation_view
                    (id, name, city, country, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAffiliationView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setString(3, row.getCity());
                ps.setString(4, row.getCountry());
                ps.setString(5, row.getBuildVersion());
                setInstant(ps, 6, row.getBuildAt());
                setInstant(ps, 7, row.getUpdatedAt());
                ps.setString(8, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertPublicationRows(List<ScholardexPublicationView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_publication_view (
                    id, doi, doi_normalized, eid, title, subtype, subtype_description,
                    scopus_subtype, scopus_subtype_description, creator, cover_date, cover_display_date,
                    volume, issue_identifier, description, author_count, corresponding_authors,
                    open_access, freetoread, freetoread_label, funding_id, article_number, page_range,
                    approved, author_ids, affiliation_ids, forum_id, citing_publication_ids, cited_by_count,
                    wos_id, google_scholar_id, build_version, build_at, updated_at,
                    scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexPublicationView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getDoi());
                ps.setString(3, row.getDoiNormalized());
                ps.setString(4, row.getEid());
                ps.setString(5, row.getTitle());
                ps.setString(6, row.getSubtype());
                ps.setString(7, row.getSubtypeDescription());
                ps.setString(8, row.getScopusSubtype());
                ps.setString(9, row.getScopusSubtypeDescription());
                ps.setString(10, row.getCreator());
                ps.setString(11, row.getCoverDate());
                ps.setString(12, row.getCoverDisplayDate());
                ps.setString(13, row.getVolume());
                ps.setString(14, row.getIssueIdentifier());
                ps.setString(15, row.getDescription());
                setInteger(ps, 16, row.getAuthorCount());
                ps.setArray(17, textArray(ps.getConnection(), row.getCorrespondingAuthors()));
                ps.setBoolean(18, row.isOpenAccess());
                ps.setString(19, row.getFreetoread());
                ps.setString(20, row.getFreetoreadLabel());
                ps.setString(21, row.getFundingId());
                ps.setString(22, row.getArticleNumber());
                ps.setString(23, row.getPageRange());
                ps.setBoolean(24, row.isApproved());
                ps.setArray(25, textArray(ps.getConnection(), row.getAuthorIds()));
                ps.setArray(26, textArray(ps.getConnection(), row.getAffiliationIds()));
                ps.setString(27, row.getForumId());
                ps.setArray(28, textArray(ps.getConnection(), row.getCitingPublicationIds()));
                setInteger(ps, 29, row.getCitedByCount());
                ps.setString(30, row.getWosId());
                ps.setString(31, row.getGoogleScholarId());
                ps.setString(32, row.getBuildVersion());
                setInstant(ps, 33, row.getBuildAt());
                setInstant(ps, 34, row.getUpdatedAt());
                ps.setString(35, row.getScopusLineage());
                ps.setString(36, row.getWosLineage());
                ps.setString(37, row.getScholarLineage());
                ps.setString(38, row.getLinkerVersion());
                ps.setString(39, row.getLinkerRunId());
                setInstant(ps, 40, row.getLinkedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertCitationRows(List<ScholardexCitationFact> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_citation_fact (
                    id, cited_publication_id, citing_publication_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexCitationFact row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getCitedPublicationId());
                ps.setString(3, row.getCitingPublicationId());
                ps.setString(4, row.getSource());
                ps.setString(5, row.getSourceRecordId());
                ps.setString(6, row.getSourceEventId());
                ps.setString(7, row.getSourceBatchId());
                ps.setString(8, row.getSourceCorrelationId());
                setInstant(ps, 9, row.getCreatedAt());
                setInstant(ps, 10, row.getUpdatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertAuthorshipRows(List<ScholardexAuthorshipFact> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_authorship_fact (
                    id, publication_id, author_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    link_state, link_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAuthorshipFact row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getPublicationId());
                ps.setString(3, row.getAuthorId());
                ps.setString(4, row.getSource());
                ps.setString(5, row.getSourceRecordId());
                ps.setString(6, row.getSourceEventId());
                ps.setString(7, row.getSourceBatchId());
                ps.setString(8, row.getSourceCorrelationId());
                ps.setString(9, row.getLinkState());
                ps.setString(10, row.getLinkReason());
                setInstant(ps, 11, row.getCreatedAt());
                setInstant(ps, 12, row.getUpdatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertAuthorAffiliationRows(List<ScholardexAuthorAffiliationFact> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_author_affiliation_fact (
                    id, author_id, affiliation_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    link_state, link_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAuthorAffiliationFact row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getAuthorId());
                ps.setString(3, row.getAffiliationId());
                ps.setString(4, row.getSource());
                ps.setString(5, row.getSourceRecordId());
                ps.setString(6, row.getSourceEventId());
                ps.setString(7, row.getSourceBatchId());
                ps.setString(8, row.getSourceCorrelationId());
                ps.setString(9, row.getLinkState());
                ps.setString(10, row.getLinkReason());
                setInstant(ps, 11, row.getCreatedAt());
                setInstant(ps, 12, row.getUpdatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    // -------------------------------------------------------------------------
    // JDBC utility helpers
    // -------------------------------------------------------------------------

    private <T> void batchInChunks(List<T> rows, Consumer<List<T>> chunkWriter) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i += JDBC_BATCH_SIZE) {
            int to = Math.min(rows.size(), i + JDBC_BATCH_SIZE);
            chunkWriter.accept(rows.subList(i, to));
        }
    }

    private static Array textArray(Connection connection, List<String> values) throws SQLException {
        String[] entries = values == null ? new String[0] : values.toArray(String[]::new);
        return connection.createArrayOf("text", entries);
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    // -------------------------------------------------------------------------
    // Miscellaneous helpers
    // -------------------------------------------------------------------------

    private void markImported(ImportProcessingResult result, int count) {
        for (int i = 0; i < count; i++) {
            result.markProcessed();
            result.markImported();
        }
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String normalized = doi.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = DOI_URL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
