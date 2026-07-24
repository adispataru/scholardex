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
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosCoverageFact;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository;
import ro.uvt.pokedex.core.service.application.ReportingDataEpochService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ScholardexProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ScholardexProjectionBuilderService.class);
    private static final Pattern DOI_URL_PREFIX = Pattern.compile("^https?://(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PREFIX = Pattern.compile("^doi:", Pattern.CASE_INSENSITIVE);
    // H75: larger chunks pair with reWriteBatchedInserts (multi-row INSERT) to cut per-statement overhead on the
    // ~2.9M-row full projection write.
    private static final int JDBC_BATCH_SIZE = 1500;

    /**
     * H75 Tier-2: the tables the full-replacement write TRUNCATEs + reloads. Their secondary {@code idx_*} indexes
     * (incl. the GINs on the array columns) are dropped before the bulk insert and recreated after — building each
     * index once over the full table is far cheaper than maintaining it per row across millions of inserts. The
     * {@code _pkey} and {@code uq_*} unique indexes are KEPT (correctness + they guard dedup during the load).
     */
    private static final List<String> FULL_REPLACEMENT_TABLES = List.of(
            "scholardex_author_affiliation_fact",
            "scholardex_authorship_fact",
            "scholardex_citation_fact",
            "scholardex_publication_view",
            "scholardex_affiliation_view",
            "scholardex_author_view",
            "scholardex_forum_view",
            "scholardex_forum_metric_view",
            "scholardex_forum_category_view",
            "scholardex_forum_membership_view");

    private final ScholardexForumFactRepository canonicalForumFactRepository;
    private final ScholardexAuthorFactRepository authorFactRepository;
    private final ScholardexAffiliationFactRepository affiliationFactRepository;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexCitationFactRepository citationFactRepository;
    private final ScholardexAuthorshipFactRepository authorshipFactRepository;
    private final ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;
    // H66 B2: WoS facts projected onto the forum-keyed views through canonical forum wosForumIds.
    private final WosMetricFactRepository wosMetricFactRepository;
    private final WosCategoryFactRepository wosCategoryFactRepository;
    private final WosCoverageFactRepository wosCoverageFactRepository;
    private final MongoTemplate mongoTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ReportingDataEpochService reportingDataEpochService;

    public ScholardexProjectionBuilderService(
            ScholardexForumFactRepository canonicalForumFactRepository,
            ScholardexAuthorFactRepository authorFactRepository,
            ScholardexAffiliationFactRepository affiliationFactRepository,
            ScholardexPublicationFactRepository publicationFactRepository,
            ScholardexCitationFactRepository citationFactRepository,
            ScholardexAuthorshipFactRepository authorshipFactRepository,
            ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository,
            WosMetricFactRepository wosMetricFactRepository,
            WosCategoryFactRepository wosCategoryFactRepository,
            WosCoverageFactRepository wosCoverageFactRepository,
            MongoTemplate mongoTemplate,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ReportingDataEpochService reportingDataEpochService
    ) {
        this.canonicalForumFactRepository = canonicalForumFactRepository;
        this.authorFactRepository = authorFactRepository;
        this.affiliationFactRepository = affiliationFactRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.citationFactRepository = citationFactRepository;
        this.authorshipFactRepository = authorshipFactRepository;
        this.authorAffiliationFactRepository = authorAffiliationFactRepository;
        this.wosMetricFactRepository = wosMetricFactRepository;
        this.wosCategoryFactRepository = wosCategoryFactRepository;
        this.wosCoverageFactRepository = wosCoverageFactRepository;
        this.mongoTemplate = mongoTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.reportingDataEpochService = reportingDataEpochService;
    }

    public ImportProcessingResult rebuildViews() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        long totalStartedAtNanos = System.nanoTime();
        try {
            // --- build forum views (H55.3: one row per canonical forum, keyed by sforum_ id) ---
            long forumStartedAtNanos = System.nanoTime();
            List<ScholardexForumView> forumViews = buildCanonicalForumViews(buildVersion, buildAt);
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
            // H67: split each pub's incoming citations by the indexing of the CITING paper's forum (Scopus / WoS),
            // for source-attributed h-index. Done as a post-pass so toPublicationView stays untouched.
            applyCitationSourceSplit(publicationViews);
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
            validCitationFacts = dedupeCitationFactsByPair(validCitationFacts);
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

            // Denormalize author affiliations from the validated edges onto the author views before the write.
            denormalizeAuthorAffiliations(authorViews, validAuthorAffiliationFacts);
            // H63: denormalize corresponding-author ids (authorship edges where corresponding=true) onto the pub views.
            applyCorrespondingAuthors(publicationViews, validAuthorshipFacts);

            int droppedCitations = citationFacts.size() - validCitationFacts.size();
            int droppedAuthorship = authorshipFacts.size() - validAuthorshipFacts.size();
            int droppedAuthorAffiliation = authorAffiliationFacts.size() - validAuthorAffiliationFacts.size();
            if (droppedCitations > 0 || droppedAuthorship > 0 || droppedAuthorAffiliation > 0) {
                log.warn("Scopus edge filter: droppedCitations={} droppedAuthorship={} droppedAuthorAffiliation={} reason=missing-parent-rows",
                        droppedCitations, droppedAuthorship, droppedAuthorAffiliation);
            }

            // --- H66 B2: forum-keyed WoS projections (metric/category/membership via wosForumIds) ---
            List<ScholardexForumFact> canonicalForumsForProjection = canonicalForumFactRepository.findAll();
            Map<String, String> journalIdToForumId = buildJournalIdToForumId(canonicalForumsForProjection);
            List<ForumMetricRow> forumMetricRows = buildForumMetricRows(journalIdToForumId, buildVersion, buildAt);
            List<ForumCategoryRow> forumCategoryRows = buildForumCategoryRows(journalIdToForumId, buildVersion, buildAt);
            // H66 A4/A5: DOAJ membership (by ISSN) + ERIH membership (by stored erihIds FK) + DOAJ-from-ERIH.
            List<ForumMembershipRow> forumMembershipRows = new ArrayList<>(
                    buildForumMembershipRows(journalIdToForumId, buildVersion, buildAt));
            forumMembershipRows.addAll(buildDoajMembershipRows(canonicalForumsForProjection));
            forumMembershipRows.addAll(buildOpenAlexApcMembershipRows(canonicalForumsForProjection));
            forumMembershipRows.addAll(buildErihMembershipRows(canonicalForumsForProjection));
            forumMembershipRows.addAll(buildScopusMembershipRows(canonicalForumsForProjection));
            forumMembershipRows.addAll(buildDblpMembershipRows(canonicalForumsForProjection));

            // --- write all tables to PostgreSQL atomically ---
            long writePgNs = System.nanoTime();
            executeFullReplacementWrite(
                    forumViews,
                    authorViews,
                    affiliationViews,
                    publicationViews,
                    validCitationFacts,
                    validAuthorshipFacts,
                    validAuthorAffiliationFacts,
                    forumMetricRows,
                    forumCategoryRows,
                    forumMembershipRows,
                    buildVersion,
                    buildAt
            );
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
        bumpEpochOnSuccess(result, "scopus-projection-full-rebuild");
        return result;
    }

    /** A successful projection write means the reporting data changed — invalidate cached indicator results. */
    private void bumpEpochOnSuccess(ImportProcessingResult result, String reason) {
        if (result.getErrorCount() == 0) {
            reportingDataEpochService.bump(reason);
        }
    }

    public ImportProcessingResult rebuildViewsForBatch(String sourceBatchId) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        if (sourceBatchId == null || sourceBatchId.isBlank()) {
            result.markError("scopus-projection-batch-id-missing");
            return result;
        }

        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        long totalStartedAtNanos = System.nanoTime();
        try {
            BatchRefreshState batchRefreshState = loadBatchRefreshState(sourceBatchId, buildVersion, buildAt);
            // Denormalize affiliations for the batch's authors (loaded with their full edge set) before the write.
            denormalizeAuthorAffiliations(batchRefreshState.authorViews(), batchRefreshState.authorAffiliationFacts());
            executeBatchRefreshWrite(batchRefreshState);

            markImported(result, batchRefreshState.forumViews().size());
            markImported(result, batchRefreshState.authorViews().size());
            markImported(result, batchRefreshState.affiliationViews().size());
            markImported(result, batchRefreshState.publicationViews().size());
            markImported(result, batchRefreshState.citationFacts().size());
            markImported(result, batchRefreshState.authorshipFacts().size());
            markImported(result, batchRefreshState.authorAffiliationFacts().size());
            log.info("Scopus projection batch rebuild complete: sourceBatchId={} forums={} authors={} affiliations={} publications={} citations={} authorships={} authorAffiliations={} totalMs={}",
                    sourceBatchId,
                    batchRefreshState.forumViews().size(),
                    batchRefreshState.authorViews().size(),
                    batchRefreshState.affiliationViews().size(),
                    batchRefreshState.publicationViews().size(),
                    batchRefreshState.citationFacts().size(),
                    batchRefreshState.authorshipFacts().size(),
                    batchRefreshState.authorAffiliationFacts().size(),
                    nanosToMillis(System.nanoTime() - totalStartedAtNanos));
        } catch (Exception e) {
            result.markError("scopus-projection-batch-rebuild-error=" + e.getMessage());
            log.error("Scopus projection batch rebuild failed: sourceBatchId={}", sourceBatchId, e);
        }
        bumpEpochOnSuccess(result, "scopus-projection-batch-rebuild");
        return result;
    }

    // -------------------------------------------------------------------------
    // Derivation helpers (unchanged)
    // -------------------------------------------------------------------------

    /**
     * H55.3: project one forum-view row per canonical Scholardex forum, keyed by its {@code sforum_}
     * id. Replaces the former dual-key scheme (one row per {@code ScopusForumFact} keyed by raw Scopus
     * id, plus a WoS-only canonical merge) that produced duplicate {@code /forums} rows. Every forum —
     * Scopus, WoS, or user-defined — now appears exactly once under its canonical id, matching the
     * canonical {@code forumId} that publications now carry (H55.2).
     */
    private List<ScholardexForumView> buildCanonicalForumViews(String buildVersion, Instant buildAt) {
        List<ScholardexForumFact> canonicalForums = new ArrayList<>(canonicalForumFactRepository.findAll());
        canonicalForums.sort(Comparator.comparing(ScholardexForumFact::getId, Comparator.nullsLast(String::compareTo)));
        List<ScholardexForumView> forumViews = new ArrayList<>(canonicalForums.size());
        for (ScholardexForumFact canonicalForum : canonicalForums) {
            if (canonicalForum.getId() == null) {
                continue;
            }
            forumViews.add(toCanonicalForumView(canonicalForum, buildVersion, buildAt));
        }
        return forumViews;
    }

    private ScholardexForumView toCanonicalForumView(ScholardexForumFact fact, String buildVersion, Instant buildAt) {
        ScholardexForumView view = new ScholardexForumView();
        view.setId(fact.getId());
        view.setPublicationName(fact.getName());
        view.setIssn(fact.getIssn());
        view.setEIssn(fact.getEIssn());
        view.setIsbn(fact.getIsbn());
        view.setAggregationType(fact.getAggregationType());
        // Multi-type: the distinct set of per-source venue kinds (Journal/Conference Proceeding/Book Series/…).
        java.util.LinkedHashSet<String> aggTypes = new java.util.LinkedHashSet<>();
        if (fact.getAggregationTypeBySource() != null) {
            aggTypes.addAll(fact.getAggregationTypeBySource().values());
        }
        if (fact.getAggregationType() != null && !fact.getAggregationType().isBlank()) {
            aggTypes.add(fact.getAggregationType());
        }
        view.setAggregationTypes(new ArrayList<>(aggTypes));
        view.setPublisher(fact.getPublisher());
        view.setForumType(fact.getForumType());
        view.setAsjc(fact.getAsjc() == null ? new ArrayList<>() : new ArrayList<>(fact.getAsjc()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    private ScholardexAuthorView toAuthorView(ScholardexAuthorFact fact, String buildVersion, Instant buildAt) {
        ScholardexAuthorView view = new ScholardexAuthorView();
        view.setId(fact.getId());
        view.setName(fact.getDisplayName());
        view.setAlternativeNames(fact.getAlternativeNames() == null ? List.of() : new ArrayList<>(fact.getAlternativeNames()));
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        view.setSourceEventId(fact.getSourceEventId());
        return view;
    }

    /**
     * Denormalize each author's affiliations from the author&rarr;affiliation edge facts onto the author views.
     * V2 keeps author affiliations only in the edge table (the author fact's {@code affiliationIds} is unused), so a
     * consumer reading {@code author_view.affiliation_ids} would otherwise see nothing. Writes every currently-observed
     * affiliation (no primary / current / past distinction — that is a profile-level concern). Overwrites the empty
     * value {@link #toAuthorView} copies from the fact.
     */
    private void denormalizeAuthorAffiliations(
            List<ScholardexAuthorView> authorViews,
            List<ScholardexAuthorAffiliationFact> authorAffiliationFacts) {
        Map<String, LinkedHashSet<String>> affiliationIdsByAuthor = new HashMap<>();
        for (ScholardexAuthorAffiliationFact edge : authorAffiliationFacts) {
            if (edge.getAuthorId() == null || edge.getAffiliationId() == null) {
                continue;
            }
            affiliationIdsByAuthor
                    .computeIfAbsent(edge.getAuthorId(), k -> new LinkedHashSet<>())
                    .add(edge.getAffiliationId());
        }
        for (ScholardexAuthorView view : authorViews) {
            LinkedHashSet<String> affiliations = affiliationIdsByAuthor.get(view.getId());
            view.setAffiliationIds(affiliations == null ? List.of() : new ArrayList<>(affiliations));
        }
    }

    /**
     * H63: denormalize each publication's corresponding-author ids from the authorship edges (where
     * {@code corresponding=true}) onto the publication view, parallel to {@code authorIds}, so the scoring engine can
     * select first-OR-corresponding authorship without an edge join. Overwrites the empty default.
     */
    private void applyCorrespondingAuthors(
            List<ScholardexPublicationView> publicationViews,
            List<ScholardexAuthorshipFact> authorshipFacts) {
        Map<String, LinkedHashSet<String>> correspondingIdsByPublication = new HashMap<>();
        for (ScholardexAuthorshipFact edge : authorshipFacts) {
            if (edge.getPublicationId() == null || edge.getAuthorId() == null
                    || !Boolean.TRUE.equals(edge.getCorresponding())) {
                continue;
            }
            correspondingIdsByPublication
                    .computeIfAbsent(edge.getPublicationId(), k -> new LinkedHashSet<>())
                    .add(edge.getAuthorId());
        }
        for (ScholardexPublicationView view : publicationViews) {
            LinkedHashSet<String> ids = correspondingIdsByPublication.get(view.getId());
            view.setCorrespondingAuthorIds(ids == null ? List.of() : new ArrayList<>(ids));
        }
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
        view.setPii(fact.getPii());
        view.setPubmedId(fact.getPubmedId());
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
        view.setAuthKeywords(fact.getAuthKeywords() == null ? List.of() : new ArrayList<>(fact.getAuthKeywords()));
        view.setCorrespondingAuthors(fact.getCorrespondingAuthors() == null ? List.of() : new ArrayList<>(fact.getCorrespondingAuthors()));
        view.setOpenAccess(Boolean.TRUE.equals(fact.getOpenAccess()));
        view.setFreetoread(fact.getFreetoread());
        view.setFreetoreadLabel(fact.getFreetoreadLabel());
        view.setFundingId(fact.getFundingId());
        view.setArticleNumber(fact.getArticleNumber());
        view.setPageRange(fact.getPageRange());
        view.setApproved(Boolean.TRUE.equals(fact.getApproved()));
        view.setAuthorIds(fact.getAuthorIds() == null ? List.of() : new ArrayList<>(fact.getAuthorIds()));
        view.setAuthorCount(fact.getAuthorCount() == null ? view.getAuthorIds().size() : fact.getAuthorCount());
        view.setAffiliationIds(fact.getAffiliationIds() == null ? List.of() : new ArrayList<>(fact.getAffiliationIds()));
        view.setForumId(fact.getForumId());
        view.setOriginalForumId(fact.getOriginalForumId());
        view.setBookId(fact.getBookId());
        List<String> citingPublicationIds = citingByCited.getOrDefault(fact.getId(), List.of());
        view.setCitingPublicationIds(new LinkedHashSet<>(citingPublicationIds));
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

    /**
     * H67: for each publication, classify its incoming citations by whether the CITING paper's forum is Scopus- /
     * WoS-indexed, and store the per-source counts on the view. The full-build views carry every pub's forumId, so the
     * pubId→forumId lookup is complete here. A citing paper in a non-indexed / unresolved venue contributes only to
     * the graph total (this is the documented ~18% undercount + the WoS conference-index gap; see the H67 plan).
     */
    private void applyCitationSourceSplit(List<ScholardexPublicationView> views) {
        Map<String, String> pubForumId = new java.util.HashMap<>(views.size());
        for (ScholardexPublicationView v : views) {
            pubForumId.put(v.getId(), v.getForum());
        }
        Map<String, int[]> forumIndexing = new java.util.HashMap<>();
        for (ScholardexForumFact f : canonicalForumFactRepository.findAll()) {
            int scopus = f.getScopusForumIds() != null && !f.getScopusForumIds().isEmpty() ? 1 : 0;
            // H76: a forum is WoS-indexed if it carries WoS journal ids OR is flagged CPCI-indexed (conference
            // proceedings — no JCR journal id, established from the WoS CPCI Records export).
            int wos = (f.getWosForumIds() != null && !f.getWosForumIds().isEmpty()) || f.isWosCpciIndexed() ? 1 : 0;
            forumIndexing.put(f.getId(), new int[]{scopus, wos});
        }
        for (ScholardexPublicationView v : views) {
            int graph = 0;
            int scopus = 0;
            int wos = 0;
            for (String citingId : v.getCitedBy()) {
                graph++;
                int[] flags = forumIndexing.get(pubForumId.get(citingId));
                if (flags != null) {
                    scopus += flags[0];
                    wos += flags[1];
                }
            }
            v.setGraphCitationCount(graph);
            v.setScopusCitationCount(scopus);
            v.setWosCitationCount(wos);
        }
    }

    private Map<String, List<String>> buildCitingMap() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<ScholardexCitationFact> facts = new ArrayList<>(dedupeCitationFactsByPair(
                new ArrayList<>(mongoTemplate.find(new Query(), ScholardexCitationFact.class))
        ));
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

    private Map<String, List<String>> buildCitingMapForPublications(Set<String> citedPublicationIds) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (citedPublicationIds == null || citedPublicationIds.isEmpty()) {
            return out;
        }
        List<ScholardexCitationFact> facts = new ArrayList<>(dedupeCitationFactsByPair(
                new ArrayList<>(citationFactRepository.findByCitedPublicationIdIn(citedPublicationIds))
        ));
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
                    (id, publication_name, issn, e_issn, isbn, aggregation_type, publisher, forum_type, asjc, build_version, build_at, updated_at, source_event_id, aggregation_types)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeForumRows(rows, sql);
    }

    private void upsertForumRows(List<ScholardexForumView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_forum_view
                    (id, publication_name, issn, e_issn, isbn, aggregation_type, publisher, forum_type, asjc, build_version, build_at, updated_at, source_event_id, aggregation_types)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    publication_name = EXCLUDED.publication_name,
                    issn = EXCLUDED.issn,
                    e_issn = EXCLUDED.e_issn,
                    isbn = EXCLUDED.isbn,
                    aggregation_type = EXCLUDED.aggregation_type,
                    publisher = EXCLUDED.publisher,
                    forum_type = EXCLUDED.forum_type,
                    asjc = EXCLUDED.asjc,
                    build_version = EXCLUDED.build_version,
                    build_at = EXCLUDED.build_at,
                    updated_at = EXCLUDED.updated_at,
                    source_event_id = EXCLUDED.source_event_id,
                    aggregation_types = EXCLUDED.aggregation_types
                """;
        writeForumRows(rows, sql);
    }

    private void writeForumRows(List<ScholardexForumView> rows, String sql) {
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexForumView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getPublicationName());
                ps.setString(3, row.getIssn());
                ps.setString(4, row.getEIssn());
                ps.setString(5, row.getIsbn());
                ps.setString(6, row.getAggregationType());
                ps.setString(7, row.getPublisher());
                ps.setString(8, row.getForumType());
                ps.setArray(9, textArray(ps.getConnection(), row.getAsjc()));
                ps.setString(10, row.getBuildVersion());
                setInstant(ps, 11, row.getBuildAt());
                setInstant(ps, 12, row.getUpdatedAt());
                ps.setString(13, row.getSourceEventId());
                ps.setArray(14, textArray(ps.getConnection(), row.getAggregationTypes()));
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertForumMetricRows(List<ForumMetricRow> rows, String buildVersion, Instant buildAt) {
        String sql = """
                INSERT INTO reporting_read.scholardex_forum_metric_view
                    (id, forum_id, year, metric_type, value, source, build_version, build_at, updated_at)
                VALUES (?, ?, ?, ?::reporting_read.metric_type_enum, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ForumMetricRow row = chunk.get(i);
                ps.setString(1, row.id());
                ps.setString(2, row.forumId());
                ps.setInt(3, row.year());
                ps.setString(4, row.metricType());
                if (row.value() == null) {
                    ps.setNull(5, java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(5, row.value());
                }
                ps.setString(6, row.source());
                ps.setString(7, buildVersion);
                setInstant(ps, 8, buildAt);
                setInstant(ps, 9, buildAt);
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertForumCategoryRows(List<ForumCategoryRow> rows, String buildVersion, Instant buildAt) {
        String sql = """
                INSERT INTO reporting_read.scholardex_forum_category_view
                    (id, forum_id, year, edition, category, metric_type, quarter, quartile_rank, "rank", source, build_version, build_at, updated_at)
                VALUES (?, ?, ?, ?::reporting_read.edition_normalized_enum, ?, ?::reporting_read.metric_type_enum, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ForumCategoryRow row = chunk.get(i);
                ps.setString(1, row.id());
                ps.setString(2, row.forumId());
                ps.setInt(3, row.year());
                ps.setString(4, row.edition());
                ps.setString(5, row.category());
                ps.setString(6, row.metricType());
                ps.setString(7, row.quarter());
                if (row.quartileRank() == null) {
                    ps.setNull(8, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(8, row.quartileRank());
                }
                if (row.rank() == null) {
                    ps.setNull(9, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(9, row.rank());
                }
                ps.setString(10, row.source());
                ps.setString(11, buildVersion);
                setInstant(ps, 12, buildAt);
                setInstant(ps, 13, buildAt);
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertForumMembershipRows(List<ForumMembershipRow> rows, String buildVersion, Instant buildAt) {
        String sql = """
                INSERT INTO reporting_read.scholardex_forum_membership_view
                    (id, forum_id, database, member, as_of, source, build_version, build_at, updated_at, apc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ForumMembershipRow row = chunk.get(i);
                ps.setString(1, row.id());
                ps.setString(2, row.forumId());
                ps.setString(3, row.database());
                ps.setBoolean(4, true);
                ps.setString(5, row.asOf());
                ps.setString(6, row.source());
                ps.setString(7, buildVersion);
                setInstant(ps, 8, buildAt);
                setInstant(ps, 9, buildAt);
                // H79: DOAJ APC flag (null for non-DOAJ databases).
                if (row.apc() == null) {
                    ps.setNull(10, java.sql.Types.BOOLEAN);
                } else {
                    ps.setBoolean(10, row.apc());
                }
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
                    (id, name, alternative_names, affiliation_ids, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeAuthorRows(rows, sql);
    }

    private void upsertAuthorRows(List<ScholardexAuthorView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_author_view
                    (id, name, alternative_names, affiliation_ids, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    alternative_names = EXCLUDED.alternative_names,
                    affiliation_ids = EXCLUDED.affiliation_ids,
                    build_version = EXCLUDED.build_version,
                    build_at = EXCLUDED.build_at,
                    updated_at = EXCLUDED.updated_at,
                    source_event_id = EXCLUDED.source_event_id
                """;
        writeAuthorRows(rows, sql);
    }

    private void writeAuthorRows(List<ScholardexAuthorView> rows, String sql) {
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAuthorView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setArray(3, textArray(ps.getConnection(), row.getAlternativeNames()));
                ps.setArray(4, textArray(ps.getConnection(), row.getAffiliationIds()));
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

    private void insertAffiliationRows(List<ScholardexAffiliationView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_affiliation_view
                    (id, name, city, country, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeAffiliationRows(rows, sql);
    }

    private void upsertAffiliationRows(List<ScholardexAffiliationView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_affiliation_view
                    (id, name, city, country, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    city = EXCLUDED.city,
                    country = EXCLUDED.country,
                    build_version = EXCLUDED.build_version,
                    build_at = EXCLUDED.build_at,
                    updated_at = EXCLUDED.updated_at,
                    source_event_id = EXCLUDED.source_event_id
                """;
        writeAffiliationRows(rows, sql);
    }

    private void writeAffiliationRows(List<ScholardexAffiliationView> rows, String sql) {
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
                    scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at,
                    pii, pubmed_id, auth_keywords, book_id,
                    graph_citation_count, scopus_citation_count, wos_citation_count,
                    corresponding_author_ids, original_forum_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writePublicationRows(rows, sql);
    }

    private void upsertPublicationRows(List<ScholardexPublicationView> rows) {
        String sql = """
                INSERT INTO reporting_read.scholardex_publication_view (
                    id, doi, doi_normalized, eid, title, subtype, subtype_description,
                    scopus_subtype, scopus_subtype_description, creator, cover_date, cover_display_date,
                    volume, issue_identifier, description, author_count, corresponding_authors,
                    open_access, freetoread, freetoread_label, funding_id, article_number, page_range,
                    approved, author_ids, affiliation_ids, forum_id, citing_publication_ids, cited_by_count,
                    wos_id, google_scholar_id, build_version, build_at, updated_at,
                    scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at,
                    pii, pubmed_id, auth_keywords, book_id,
                    graph_citation_count, scopus_citation_count, wos_citation_count,
                    corresponding_author_ids, original_forum_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    doi = EXCLUDED.doi,
                    doi_normalized = EXCLUDED.doi_normalized,
                    eid = EXCLUDED.eid,
                    title = EXCLUDED.title,
                    subtype = EXCLUDED.subtype,
                    subtype_description = EXCLUDED.subtype_description,
                    scopus_subtype = EXCLUDED.scopus_subtype,
                    scopus_subtype_description = EXCLUDED.scopus_subtype_description,
                    creator = EXCLUDED.creator,
                    cover_date = EXCLUDED.cover_date,
                    cover_display_date = EXCLUDED.cover_display_date,
                    volume = EXCLUDED.volume,
                    issue_identifier = EXCLUDED.issue_identifier,
                    description = EXCLUDED.description,
                    author_count = EXCLUDED.author_count,
                    corresponding_authors = EXCLUDED.corresponding_authors,
                    open_access = EXCLUDED.open_access,
                    freetoread = EXCLUDED.freetoread,
                    freetoread_label = EXCLUDED.freetoread_label,
                    funding_id = EXCLUDED.funding_id,
                    article_number = EXCLUDED.article_number,
                    page_range = EXCLUDED.page_range,
                    approved = EXCLUDED.approved,
                    author_ids = EXCLUDED.author_ids,
                    affiliation_ids = EXCLUDED.affiliation_ids,
                    forum_id = EXCLUDED.forum_id,
                    citing_publication_ids = EXCLUDED.citing_publication_ids,
                    cited_by_count = EXCLUDED.cited_by_count,
                    wos_id = EXCLUDED.wos_id,
                    google_scholar_id = EXCLUDED.google_scholar_id,
                    build_version = EXCLUDED.build_version,
                    build_at = EXCLUDED.build_at,
                    updated_at = EXCLUDED.updated_at,
                    scopus_lineage = EXCLUDED.scopus_lineage,
                    wos_lineage = EXCLUDED.wos_lineage,
                    scholar_lineage = EXCLUDED.scholar_lineage,
                    linker_version = EXCLUDED.linker_version,
                    linker_run_id = EXCLUDED.linker_run_id,
                    linked_at = EXCLUDED.linked_at,
                    pii = EXCLUDED.pii,
                    pubmed_id = EXCLUDED.pubmed_id,
                    auth_keywords = EXCLUDED.auth_keywords,
                    book_id = EXCLUDED.book_id,
                    graph_citation_count = EXCLUDED.graph_citation_count,
                    scopus_citation_count = EXCLUDED.scopus_citation_count,
                    wos_citation_count = EXCLUDED.wos_citation_count,
                    corresponding_author_ids = EXCLUDED.corresponding_author_ids,
                    original_forum_id = EXCLUDED.original_forum_id
                """;
        writePublicationRows(rows, sql);
    }

    private void writePublicationRows(List<ScholardexPublicationView> rows, String sql) {
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
                ps.setArray(28, textArray(ps.getConnection(), new ArrayList<>(row.getCitingPublicationIds())));
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
                ps.setString(41, row.getPii());
                ps.setString(42, row.getPubmedId());
                ps.setArray(43, textArray(ps.getConnection(), row.getAuthKeywords()));
                ps.setString(44, row.getBookId());
                ps.setInt(45, row.getGraphCitationCount());
                ps.setInt(46, row.getScopusCitationCount());
                ps.setInt(47, row.getWosCitationCount());
                ps.setArray(48, textArray(ps.getConnection(), row.getCorrespondingAuthorIds()));
                ps.setString(49, row.getOriginalForumId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertCitationRows(List<ScholardexCitationFact> rows) {
        List<ScholardexCitationFact> dedupedRows = dedupeCitationFactsByPair(rows);
        if (dedupedRows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO reporting_read.scholardex_citation_fact (
                    id, cited_publication_id, citing_publication_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        batchInChunks(dedupedRows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
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

    private List<ScholardexCitationFact> loadBatchCitationFacts(Set<String> affectedPublicationIds) {
        if (affectedPublicationIds == null || affectedPublicationIds.isEmpty()) {
            return List.of();
        }
        Map<String, ScholardexCitationFact> factsById = new LinkedHashMap<>();
        for (ScholardexCitationFact fact : citationFactRepository.findByCitedPublicationIdIn(affectedPublicationIds)) {
            if (fact.getId() != null) {
                factsById.put(fact.getId(), fact);
            }
        }
        for (ScholardexCitationFact fact : citationFactRepository.findByCitingPublicationIdIn(affectedPublicationIds)) {
            if (fact.getId() != null) {
                factsById.put(fact.getId(), fact);
            }
        }
        return dedupeCitationFactsByPair(new ArrayList<>(factsById.values()));
    }

    private BatchRefreshState loadBatchRefreshState(String sourceBatchId, String buildVersion, Instant buildAt) {
        // H55.3: forums are keyed by canonical id, which has no per-Scopus-batch scoping; the canonical
        // forum set is small, so refresh all of it on each batch (upsert) to keep the view complete and
        // free of the stale dual-key rows that previously accumulated on the never-pruning batch path.
        List<ScholardexForumView> forumViews = buildCanonicalForumViews(buildVersion, buildAt);

        List<ScholardexAuthorFact> authorFacts = new ArrayList<>(authorFactRepository.findBySourceBatchId(sourceBatchId));
        authorFacts.sort(Comparator.comparing(ScholardexAuthorFact::getId, Comparator.nullsLast(String::compareTo)));
        List<ScholardexAuthorView> authorViews = authorFacts.stream()
                .map(fact -> toAuthorView(fact, buildVersion, buildAt))
                .toList();

        List<ScholardexAffiliationFact> affiliationFacts = new ArrayList<>(affiliationFactRepository.findBySourceBatchId(sourceBatchId));
        affiliationFacts.sort(Comparator.comparing(ScholardexAffiliationFact::getId, Comparator.nullsLast(String::compareTo)));
        List<ScholardexAffiliationView> affiliationViews = affiliationFacts.stream()
                .map(fact -> toAffiliationView(fact, buildVersion, buildAt))
                .toList();

        List<ScholardexPublicationFact> publicationFacts = new ArrayList<>(publicationFactRepository.findBySourceBatchId(sourceBatchId));
        publicationFacts.sort(Comparator.comparing(ScholardexPublicationFact::getEid, Comparator.nullsLast(String::compareTo)));
        Set<String> affectedPublicationIds = publicationFacts.stream()
                .map(ScholardexPublicationFact::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, List<String>> citingByCited = buildCitingMapForPublications(affectedPublicationIds);
        List<ScholardexPublicationView> publicationViews = new ArrayList<>(publicationFacts.size());
        for (ScholardexPublicationFact fact : publicationFacts) {
            publicationViews.add(toPublicationView(fact, citingByCited, buildVersion, buildAt));
        }

        Set<String> affectedAuthorIds = authorViews.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> affectedAffiliationIds = affiliationViews.stream()
                .map(ScholardexAffiliationView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<ScholardexCitationFact> citationFacts = loadBatchCitationFacts(affectedPublicationIds).stream()
                .filter(row -> row.getCitedPublicationId() != null
                        && row.getCitingPublicationId() != null
                        && (affectedPublicationIds.contains(row.getCitedPublicationId())
                        || affectedPublicationIds.contains(row.getCitingPublicationId())))
                .toList();
        citationFacts = dedupeCitationFactsByPair(citationFacts);
        List<ScholardexAuthorshipFact> authorshipFacts = affectedPublicationIds.isEmpty()
                ? List.of()
                : authorshipFactRepository.findByPublicationIdIn(affectedPublicationIds).stream()
                .filter(row -> row.getPublicationId() != null
                        && row.getAuthorId() != null
                        && affectedPublicationIds.contains(row.getPublicationId())
                        && affectedAuthorIds.contains(row.getAuthorId()))
                .toList();
        List<ScholardexAuthorAffiliationFact> authorAffiliationFacts = affectedAuthorIds.isEmpty()
                ? List.of()
                : authorAffiliationFactRepository.findByAuthorIdIn(affectedAuthorIds).stream()
                .filter(row -> row.getAuthorId() != null
                        && row.getAffiliationId() != null
                        && affectedAuthorIds.contains(row.getAuthorId())
                        && affectedAffiliationIds.contains(row.getAffiliationId()))
                .toList();
        // H63: denormalize corresponding-author ids onto the batch's pub views (mirror of the full-build pass), so a
        // batch refresh upsert carries them instead of wiping them to the empty default.
        applyCorrespondingAuthors(publicationViews, authorshipFacts);
        return new BatchRefreshState(
                affectedPublicationIds,
                affectedAuthorIds,
                affectedAffiliationIds,
                forumViews,
                authorViews,
                affiliationViews,
                publicationViews,
                citationFacts,
                authorshipFacts,
                authorAffiliationFacts
        );
    }

    // ---- H66 B2: forum-keyed WoS projection rows (canonical forum wosForumIds ⋈ WoS facts) ----

    private record ForumMetricRow(String id, String forumId, int year, String metricType, Double value, String source) {}
    private record ForumCategoryRow(String id, String forumId, int year, String edition, String category,
                                    String metricType, String quarter, Integer quartileRank, Integer rank, String source) {}
    record ForumMembershipRow(String id, String forumId, String database, String asOf, String source, Boolean apc) {}

    /** Map each canonical forum's WoS journal ids to its forum id (the FK B2 joins through). */
    private Map<String, String> buildJournalIdToForumId(List<ScholardexForumFact> forums) {
        Map<String, String> map = new HashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getWosForumIds() == null) {
                continue;
            }
            for (String journalId : forum.getWosForumIds()) {
                if (journalId != null && !journalId.isBlank()) {
                    map.put(journalId, forum.getId());
                }
            }
        }
        return map;
    }

    /** AIS/IF/RIS per (forum, year, metric); collapse by max value when a forum carries >1 journal id. */
    private List<ForumMetricRow> buildForumMetricRows(Map<String, String> journalIdToForumId, String buildVersion, Instant buildAt) {
        Map<String, ForumMetricRow> best = new LinkedHashMap<>();
        LinkedHashSet<String> orphanJournalIds = new LinkedHashSet<>();
        for (WosMetricFact fact : wosMetricFactRepository.findAll()) {
            String forumId = journalIdToForumId.get(fact.getJournalId());
            if (forumId == null) {
                addOrphanJournalId(orphanJournalIds, fact.getJournalId());
                continue;
            }
            if (fact.getYear() == null || fact.getMetricType() == null) {
                continue;
            }
            String metricType = fact.getMetricType().name();
            String key = forumId + "|" + fact.getYear() + "|" + metricType;
            ForumMetricRow existing = best.get(key);
            if (existing == null || compareNullableMax(fact.getValue(), existing.value()) > 0) {
                best.put(key, new ForumMetricRow(key, forumId, fact.getYear(), metricType, fact.getValue(), "JCR"));
            }
        }
        logOrphanJournals("metric", orphanJournalIds);
        return new ArrayList<>(best.values());
    }

    /** H66B M5: a WoS journalId carrying ranking/coverage facts that resolves to no forum (its rankings are dropped). */
    private static void addOrphanJournalId(LinkedHashSet<String> orphanJournalIds, String journalId) {
        if (journalId != null && !journalId.isBlank()) {
            orphanJournalIds.add(journalId);
        }
    }

    /**
     * H66B M5 — report (don't mint) ranking/coverage facts whose journal resolves to no forum. WoS identity
     * is create-or-match, so every metric-bearing journal should already have a forum; a non-zero orphan
     * count is a health signal (a journal whose forum was conflict-quarantined, or an identity gap), not a
     * reason to mint a forum in the ranking layer.
     */
    private void logOrphanJournals(String view, LinkedHashSet<String> orphanJournalIds) {
        if (orphanJournalIds.isEmpty()) {
            return;
        }
        List<String> sample = orphanJournalIds.stream().limit(10).toList();
        log.warn("Forum {} projection: {} WoS journals have {} facts but resolve to no forum (rankings dropped); sample={}",
                view, orphanJournalIds.size(), view, sample);
    }

    private static int compareNullableMax(Double incoming, Double existing) {
        double i = incoming == null ? Double.NEGATIVE_INFINITY : incoming;
        double e = existing == null ? Double.NEGATIVE_INFINITY : existing;
        return Double.compare(i, e);
    }

    /** JCR edition+category+quartile per (forum, year, edition, category, metric); dedup by key. */
    private List<ForumCategoryRow> buildForumCategoryRows(Map<String, String> journalIdToForumId, String buildVersion, Instant buildAt) {
        Map<String, ForumCategoryRow> rows = new LinkedHashMap<>();
        LinkedHashSet<String> orphanJournalIds = new LinkedHashSet<>();
        for (WosCategoryFact fact : wosCategoryFactRepository.findAll()) {
            String forumId = journalIdToForumId.get(fact.getJournalId());
            if (forumId == null) {
                addOrphanJournalId(orphanJournalIds, fact.getJournalId());
                continue;
            }
            if (fact.getYear() == null || fact.getEditionNormalized() == null
                    || fact.getCategoryNameCanonical() == null || fact.getMetricType() == null) {
                continue;
            }
            String edition = fact.getEditionNormalized().name();
            String metricType = fact.getMetricType().name();
            String key = forumId + "|" + fact.getYear() + "|" + edition + "|" + fact.getCategoryNameCanonical() + "|" + metricType;
            rows.putIfAbsent(key, new ForumCategoryRow(key, forumId, fact.getYear(), edition,
                    fact.getCategoryNameCanonical(), metricType, fact.getQuarter(), fact.getQuartileRank(), fact.getRank(), "JCR"));
        }
        logOrphanJournals("category", orphanJournalIds);
        return new ArrayList<>(rows.values());
    }

    /** MJL current edition coverage → snapshot membership per (forum, edition); category deferred. */
    private List<ForumMembershipRow> buildForumMembershipRows(Map<String, String> journalIdToForumId, String buildVersion, Instant buildAt) {
        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        LinkedHashSet<String> orphanJournalIds = new LinkedHashSet<>();
        for (WosCoverageFact fact : wosCoverageFactRepository.findAll()) {
            String forumId = journalIdToForumId.get(fact.getJournalId());
            if (forumId == null) {
                addOrphanJournalId(orphanJournalIds, fact.getJournalId());
                continue;
            }
            if (fact.getEditionNormalized() == null) {
                continue;
            }
            String database = fact.getEditionNormalized().name();
            String source = fact.getSourceType() == null ? "MJL" : fact.getSourceType().name();
            String asOf = fact.getYear() == null ? null : String.valueOf(fact.getYear());
            String key = forumId + "|" + database + "|" + source;
            rows.putIfAbsent(key, new ForumMembershipRow(key, forumId, database, asOf, source, null));
        }
        logOrphanJournals("coverage", orphanJournalIds);
        return new ArrayList<>(rows.values());
    }

    /**
     * H66 A4: DOAJ open-access membership. DOAJ carries no native forum id, so match its journals to forums
     * by normalized ISSN token (issn/eIssn/aliases) and emit one snapshot row per matched forum
     * ({@code database='DOAJ'}, {@code source='DOAJ'}). Match-only — never creates forums.
     */
    List<ForumMembershipRow> buildDoajMembershipRows(List<ScholardexForumFact> forums) {
        // ISSN token -> forum id. On collision keep the lexicographically smallest forum id (deterministic).
        Map<String, String> forumIdByIssnToken = new HashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() == null) {
                continue;
            }
            for (String token : forumIssnTokens(forum)) {
                forumIdByIssnToken.merge(token, forum.getId(),
                        (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }

        List<DoajJournalFact> doajFacts = new ArrayList<>(mongoTemplate.findAll(DoajJournalFact.class));
        doajFacts.sort(Comparator.comparing(DoajJournalFact::getId, Comparator.nullsLast(String::compareTo)));
        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        for (DoajJournalFact doaj : doajFacts) {
            String issnToken = normalizeIssnToken(doaj.getIssn());
            String forumId = issnToken == null ? null : forumIdByIssnToken.get(issnToken);
            if (forumId == null) {
                String eIssnToken = normalizeIssnToken(doaj.getEIssn());
                forumId = eIssnToken == null ? null : forumIdByIssnToken.get(eIssnToken);
            }
            if (forumId == null) {
                continue;
            }
            String key = forumId + "|DOAJ|DOAJ";
            rows.putIfAbsent(key, new ForumMembershipRow(key, forumId, "DOAJ", doaj.getAsOf(), "DOAJ", doaj.getApc()));
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * H79: OpenAlex gold-OA (APC) membership. OpenAlex source facts — derived offline from the works dumps —
     * carry {@code is_oa} + advertised {@code apcUsd} per venue. Match the <em>fee journals</em>
     * ({@code isOa && apcUsd > 0}) to forums by normalized ISSN token and emit one row
     * ({@code database='OPENALEX'}, {@code source='OPENALEX'}, {@code apc=true}). This catches gold-OA venues
     * that DOAJ misses (e.g. MDPI <i>Electronics</i>: is_oa yet absent from DOAJ). Match-only — never creates
     * forums, and hybrid journals (paid OA option but {@code is_oa=false}) are deliberately excluded.
     */
    List<ForumMembershipRow> buildOpenAlexApcMembershipRows(List<ScholardexForumFact> forums) {
        Map<String, String> forumIdByIssnToken = new HashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() == null) {
                continue;
            }
            for (String token : forumIssnTokens(forum)) {
                forumIdByIssnToken.merge(token, forum.getId(),
                        (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }

        List<OpenAlexSourceFact> sourceFacts =
                new ArrayList<>(mongoTemplate.findAll(OpenAlexSourceFact.class));
        sourceFacts.sort(Comparator.comparing(OpenAlexSourceFact::getId, Comparator.nullsLast(String::compareTo)));
        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        for (OpenAlexSourceFact src : sourceFacts) {
            if (!src.isFeeJournal() || src.getIssns() == null) {
                continue;
            }
            String forumId = null;
            for (String issn : src.getIssns()) {
                String token = normalizeIssnToken(issn);
                if (token != null && forumIdByIssnToken.containsKey(token)) {
                    forumId = forumIdByIssnToken.get(token);
                    break;
                }
            }
            if (forumId == null) {
                continue;
            }
            String asOf = src.getUpdatedAt() == null ? null : src.getUpdatedAt().toString();
            String key = forumId + "|OPENALEX|OPENALEX";
            rows.putIfAbsent(key, new ForumMembershipRow(key, forumId, "OPENALEX", asOf, "OPENALEX", Boolean.TRUE));
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * SCOPUS membership (database='SCOPUS') from each forum's stored {@code scopusForumIds} FK. The Scopus source
     * list is matched onto forums during canonicalization, so — unlike DOAJ/ERIH — there is no ISSN re-join here: a
     * non-empty {@code scopusForumIds} means the journal is in Scopus.
     */
    List<ForumMembershipRow> buildScopusMembershipRows(List<ScholardexForumFact> forums) {
        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() == null) {
                continue;
            }
            List<String> scopusForumIds = forum.getScopusForumIds();
            if (scopusForumIds == null || scopusForumIds.isEmpty()) {
                continue;
            }
            String key = forum.getId() + "|SCOPUS|SCOPUS";
            rows.putIfAbsent(key, new ForumMembershipRow(key, forum.getId(), "SCOPUS", null, "SCOPUS", null));
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * DBLP membership (database='DBLP') from each forum's stored {@code dblpIds} FK — the DBLP conference-series /
     * venue ids resolved during canonicalization (H66B Phase 4b). Like Scopus, this is a stored-FK presence check:
     * a non-empty {@code dblpIds} means the venue is indexed in DBLP.
     */
    List<ForumMembershipRow> buildDblpMembershipRows(List<ScholardexForumFact> forums) {
        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() == null) {
                continue;
            }
            List<String> dblpIds = forum.getDblpIds();
            if (dblpIds == null || dblpIds.isEmpty()) {
                continue;
            }
            String key = forum.getId() + "|DBLP|DBLP";
            rows.putIfAbsent(key, new ForumMembershipRow(key, forum.getId(), "DBLP", null, "DBLP", null));
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * H66 A5: ERIH+ membership from the forum's stored {@code erihIds} FK (database='ERIH'), plus a
     * DOAJ membership from ERIH's own open-access flag (database='DOAJ', source='ERIH' — coexists with
     * A4's source='DOAJ' under the unique key forum_id+database+source).
     */
    List<ForumMembershipRow> buildErihMembershipRows(List<ScholardexForumFact> forums) {
        // erihId -> its ERIH fact (for the oa_doaj flag + as_of stamp).
        Map<String, ErihJournalFact> erihById = new HashMap<>();
        for (ErihJournalFact fact : mongoTemplate.findAll(ErihJournalFact.class)) {
            if (fact.getId() != null) {
                erihById.put(fact.getId(), fact);
            }
        }

        Map<String, ForumMembershipRow> rows = new LinkedHashMap<>();
        for (ScholardexForumFact forum : forums) {
            if (forum.getId() == null || forum.getErihIds() == null || forum.getErihIds().isEmpty()) {
                continue;
            }
            List<String> erihIds = new ArrayList<>(forum.getErihIds());
            erihIds.sort(Comparator.nullsLast(String::compareTo));
            String asOf = null;
            boolean openAccess = false;
            for (String erihId : erihIds) {
                ErihJournalFact fact = erihById.get(erihId);
                if (fact == null) {
                    continue;
                }
                if (asOf == null) {
                    asOf = fact.getAsOf();
                }
                openAccess = openAccess || fact.isOaDoaj();
            }
            String erihKey = forum.getId() + "|ERIH|ERIH";
            rows.putIfAbsent(erihKey, new ForumMembershipRow(erihKey, forum.getId(), "ERIH", asOf, "ERIH", null));
            if (openAccess) {
                String doajKey = forum.getId() + "|DOAJ|ERIH";
                rows.putIfAbsent(doajKey, new ForumMembershipRow(doajKey, forum.getId(), "DOAJ", asOf, "ERIH", null));
            }
        }
        return new ArrayList<>(rows.values());
    }

    /** Normalized ISSN tokens (issn/eIssn/aliasIssns) carried by a canonical forum. */
    private static java.util.Set<String> forumIssnTokens(ScholardexForumFact forum) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        addIssnToken(tokens, forum.getIssn());
        addIssnToken(tokens, forum.getEIssn());
        if (forum.getAliasIssns() != null) {
            for (String alias : forum.getAliasIssns()) {
                addIssnToken(tokens, alias);
            }
        }
        return tokens;
    }

    private static void addIssnToken(java.util.Set<String> tokens, String raw) {
        String token = normalizeIssnToken(raw);
        if (token != null) {
            tokens.add(token);
        }
    }

    /** Compact, upper-cased ISSN (hyphens/spaces stripped); null unless exactly 8 ISSN chars. */
    private static String normalizeIssnToken(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[^0-9Xx]", "").toUpperCase(java.util.Locale.ROOT);
        return compact.length() == 8 ? compact : null;
    }

    private void executeFullReplacementWrite(
            List<ScholardexForumView> forumViews,
            List<ScholardexAuthorView> authorViews,
            List<ScholardexAffiliationView> affiliationViews,
            List<ScholardexPublicationView> publicationViews,
            List<ScholardexCitationFact> citationFacts,
            List<ScholardexAuthorshipFact> authorshipFacts,
            List<ScholardexAuthorAffiliationFact> authorAffiliationFacts,
            List<ForumMetricRow> forumMetricRows,
            List<ForumCategoryRow> forumCategoryRows,
            List<ForumMembershipRow> forumMembershipRows,
            String buildVersion,
            Instant buildAt
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            // H75: this whole rebuild is re-runnable, so skip per-commit fsync waits for the bulk load.
            jdbcTemplate.execute("SET LOCAL synchronous_commit = off");
            // H75 Tier-2: drop the secondary idx_* indexes (incl. GINs) so the bulk insert doesn't maintain them
            // per row; recreate them once over the full tables after the load. DDL is transactional in Postgres,
            // so a rollback restores them. Captured from live catalog so it never drifts from the Flyway DDL.
            List<Map<String, Object>> secondaryIndexes = captureSecondaryIndexes();
            long dropStart = System.nanoTime();
            for (Map<String, Object> ix : secondaryIndexes) {
                jdbcTemplate.execute("DROP INDEX IF EXISTS reporting_read." + ix.get("indexname"));
            }
            long dropMs = nanosToMillis(System.nanoTime() - dropStart);
            long insertStart = System.nanoTime();
            jdbcTemplate.execute("""
                    TRUNCATE TABLE
                        reporting_read.scholardex_author_affiliation_fact,
                        reporting_read.scholardex_authorship_fact,
                        reporting_read.scholardex_citation_fact,
                        reporting_read.scholardex_publication_view,
                        reporting_read.scholardex_affiliation_view,
                        reporting_read.scholardex_author_view,
                        reporting_read.scholardex_forum_view,
                        reporting_read.scholardex_forum_metric_view,
                        reporting_read.scholardex_forum_category_view,
                        reporting_read.scholardex_forum_membership_view
                    """);
            insertForumRows(forumViews);
            insertAuthorRows(authorViews);
            insertAffiliationRows(affiliationViews);
            insertPublicationRows(publicationViews);
            insertCitationRows(citationFacts);
            insertAuthorshipRows(authorshipFacts);
            insertAuthorAffiliationRows(authorAffiliationFacts);
            insertForumMetricRows(forumMetricRows, buildVersion, buildAt);
            insertForumCategoryRows(forumCategoryRows, buildVersion, buildAt);
            insertForumMembershipRows(forumMembershipRows, buildVersion, buildAt);
            long insertMs = nanosToMillis(System.nanoTime() - insertStart);
            // Recreate the secondary indexes once over the now-full tables.
            long recreateStart = System.nanoTime();
            for (Map<String, Object> ix : secondaryIndexes) {
                jdbcTemplate.execute((String) ix.get("indexdef"));
            }
            long recreateMs = nanosToMillis(System.nanoTime() - recreateStart);
            log.info("Projection full-replacement write sub-timings: dropIndexes={}ms insertRows={}ms recreateIndexes={}ms ({} secondary indexes)",
                    dropMs, insertMs, recreateMs, secondaryIndexes.size());
        });
    }

    private void executeBatchRefreshWrite(BatchRefreshState batchRefreshState) {
        transactionTemplate.executeWithoutResult(status -> {
            upsertForumRows(batchRefreshState.forumViews());
            upsertAuthorRows(batchRefreshState.authorViews());
            upsertAffiliationRows(batchRefreshState.affiliationViews());
            upsertPublicationRows(batchRefreshState.publicationViews());
            refreshCitationRowsForBatch(batchRefreshState.affectedPublicationIds(), batchRefreshState.citationFacts());
            refreshAuthorshipRowsForBatch(batchRefreshState.affectedPublicationIds(), batchRefreshState.authorshipFacts());
            refreshAuthorAffiliationRowsForBatch(batchRefreshState.affectedAuthorIds(), batchRefreshState.authorAffiliationFacts());
        });
    }

    private void refreshCitationRowsForBatch(Set<String> publicationIds, List<ScholardexCitationFact> citationFacts) {
        deleteByTextArray(
                "DELETE FROM reporting_read.scholardex_citation_fact WHERE cited_publication_id = ANY (?) OR citing_publication_id = ANY (?)",
                publicationIds,
                publicationIds
        );
        insertCitationRows(citationFacts);
    }

    private List<ScholardexCitationFact> dedupeCitationFactsByPair(List<ScholardexCitationFact> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, ScholardexCitationFact> deduped = new LinkedHashMap<>();
        for (ScholardexCitationFact row : rows) {
            if (row == null || row.getCitedPublicationId() == null || row.getCitingPublicationId() == null) {
                continue;
            }
            String pairKey = row.getCitedPublicationId() + "|" + row.getCitingPublicationId();
            deduped.putIfAbsent(pairKey, row);
        }
        return new ArrayList<>(deduped.values());
    }

    private void refreshAuthorshipRowsForBatch(Set<String> publicationIds, List<ScholardexAuthorshipFact> authorshipFacts) {
        deleteByTextArray(
                "DELETE FROM reporting_read.scholardex_authorship_fact WHERE publication_id = ANY (?)",
                publicationIds
        );
        insertAuthorshipRows(authorshipFacts);
    }

    private void refreshAuthorAffiliationRowsForBatch(Set<String> authorIds, List<ScholardexAuthorAffiliationFact> authorAffiliationFacts) {
        deleteByTextArray(
                "DELETE FROM reporting_read.scholardex_author_affiliation_fact WHERE author_id = ANY (?)",
                authorIds
        );
        insertAuthorAffiliationRows(authorAffiliationFacts);
    }

    private void deleteByTextArray(String sql, Set<String> firstValues, Set<String> secondValues) {
        if ((firstValues == null || firstValues.isEmpty()) && (secondValues == null || secondValues.isEmpty())) {
            return;
        }
        jdbcTemplate.execute((Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setArray(1, textArray(connection, firstValues == null ? List.of() : new ArrayList<>(firstValues)));
                ps.setArray(2, textArray(connection, secondValues == null ? List.of() : new ArrayList<>(secondValues)));
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void deleteByTextArray(String sql, Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        jdbcTemplate.execute((Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setArray(1, textArray(connection, new ArrayList<>(values)));
                ps.executeUpdate();
            }
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // JDBC utility helpers
    // -------------------------------------------------------------------------

    /**
     * H75 Tier-2: the secondary {@code idx_*} indexes (name + recreate DDL) on the full-replacement tables, read
     * live from the catalog so the drop/recreate never drifts from the Flyway-managed definitions. Excludes
     * {@code _pkey} and {@code uq_*} (those stay to guard correctness/dedup during the load).
     */
    private List<Map<String, Object>> captureSecondaryIndexes() {
        String placeholders = String.join(",", java.util.Collections.nCopies(FULL_REPLACEMENT_TABLES.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'reporting_read' "
                        + "AND indexname LIKE 'idx_%' AND tablename IN (" + placeholders + ")",
                FULL_REPLACEMENT_TABLES.toArray());
    }

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

    private record BatchRefreshState(
            Set<String> affectedPublicationIds,
            Set<String> affectedAuthorIds,
            Set<String> affectedAffiliationIds,
            List<ScholardexForumView> forumViews,
            List<ScholardexAuthorView> authorViews,
            List<ScholardexAffiliationView> affiliationViews,
            List<ScholardexPublicationView> publicationViews,
            List<ScholardexCitationFact> citationFacts,
            List<ScholardexAuthorshipFact> authorshipFacts,
            List<ScholardexAuthorAffiliationFact> authorAffiliationFacts
    ) {}
}
