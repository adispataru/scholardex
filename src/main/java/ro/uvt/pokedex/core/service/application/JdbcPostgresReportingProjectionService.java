package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Sorts.descending;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
@ConditionalOnProperty(prefix = "core.h22.projection", name = "enabled", havingValue = "true")
public class JdbcPostgresReportingProjectionService implements PostgresReportingProjectionService {

    private static final Logger log = LoggerFactory.getLogger(JdbcPostgresReportingProjectionService.class);

    private static final String SLICE_WOS = "wos";
    private static final String SLICE_SCOPUS = "scopus";

    private static final String MODE_FULL_REBUILD = "FULL_REBUILD";
    private static final String MODE_INCREMENTAL_SYNC = "INCREMENTAL_SYNC";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final MongoTemplate mongoTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PostgresReportingProjectionProperties properties;
    private final PostgresMaterializedViewRefreshService materializedViewRefreshService;

    public JdbcPostgresReportingProjectionService(
            MongoTemplate mongoTemplate,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            PostgresReportingProjectionProperties properties,
            PostgresMaterializedViewRefreshService materializedViewRefreshService
    ) {
        this.mongoTemplate = mongoTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.materializedViewRefreshService = materializedViewRefreshService;
    }

    @Override
    public ProjectionRunSummary runFullRebuild() {
        return runProjection(MODE_FULL_REBUILD, true);
    }

    @Override
    public ProjectionRunSummary runIncrementalSync() {
        return runProjection(MODE_INCREMENTAL_SYNC, false);
    }

    @Override
    public ProjectionStatusSnapshot latestRunStatus() {
        ProjectionRunSummary latestRun = queryLatestRun();
        Map<String, CheckpointSummary> checkpoints = queryCheckpoints();
        return new ProjectionStatusSnapshot(latestRun, checkpoints);
    }

    @Override
    public void resetProjectionState() {
        jdbcTemplate.update("DELETE FROM reporting_read.projection_checkpoint");
        log.info("H22.3 projection checkpoints reset");
    }

    static boolean shouldRebuildSlice(boolean fullRebuild, String sourceFingerprint, String checkpointFingerprint) {
        return fullRebuild || !Objects.equals(sourceFingerprint, checkpointFingerprint);
    }

    private ProjectionRunSummary runProjection(String mode, boolean fullRebuild) {
        String runId = "h22-projection-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        long runStartedNs = System.nanoTime();

        log.info(
                "H22.3 projection run started: runId={} mode={} fullRebuild={} dryRun={} chunkSize={} statementTimeoutMs={}",
                runId, mode, fullRebuild, properties.isDryRun(), properties.getChunkSize(), properties.getStatementTimeoutMs()
        );

        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_run
                (run_id, mode, status, started_at)
                VALUES (?, ?, ?, ?)
                """, runId, mode, STATUS_RUNNING, timestamp(startedAt));

        Map<String, String> sourceFingerprints = Map.of(
                SLICE_WOS, computeWosFingerprint(),
                SLICE_SCOPUS, computeScopusFingerprint()
        );
        Map<String, CheckpointSummary> checkpoints = queryCheckpoints();

        List<SliceRunSummary> slices = new ArrayList<>();
        String errorSample = null;
        String finalStatus = STATUS_SUCCESS;

        for (String slice : List.of(SLICE_WOS, SLICE_SCOPUS)) {
            String sourceFingerprint = sourceFingerprints.get(slice);
            CheckpointSummary checkpoint = checkpoints.get(slice);
            String checkpointFingerprint = checkpoint == null ? null : checkpoint.sourceFingerprint();
            boolean rebuildSlice = shouldRebuildSlice(fullRebuild, sourceFingerprint, checkpointFingerprint);

            Instant sliceStartedAt = Instant.now();
            long sliceStartedNs = System.nanoTime();
            log.info(
                    "H22.3 projection slice planned: runId={} slice={} rebuild={} sourceFingerprint={} checkpointFingerprint={}",
                    runId, slice, rebuildSlice, shortFingerprint(sourceFingerprint), shortFingerprint(checkpointFingerprint)
            );

            if (!rebuildSlice) {
                SliceRunSummary summary = new SliceRunSummary(
                        slice, STATUS_SKIPPED, sourceFingerprint, 0, "source fingerprint unchanged", sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, summary);
                slices.add(summary);
                log.info(
                        "H22.3 projection slice skipped: runId={} slice={} durationMs={} reason={}",
                        runId, slice, elapsedMs(sliceStartedNs), summary.note()
                );
                continue;
            }

            try {
                SliceProjectionResult result = SLICE_WOS.equals(slice)
                        ? rebuildWosSlice(runId, startedAt)
                        : rebuildScopusSlice(runId, startedAt);
                if (!properties.isDryRun()) {
                    materializedViewRefreshService.refreshForSlices(Set.of(slice), runId);
                }
                upsertCheckpoint(slice, sourceFingerprint, runId, mode);
                SliceRunSummary summary = new SliceRunSummary(
                        slice, STATUS_SUCCESS, sourceFingerprint, result.insertedRows(), result.note(), sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, summary);
                slices.add(summary);
                log.info(
                        "H22.3 projection slice completed: runId={} slice={} insertedRows={} durationMs={} note={}",
                        runId, slice, summary.insertedRows(), elapsedMs(sliceStartedNs), summary.note()
                );
            } catch (Exception e) {
                finalStatus = STATUS_FAILED;
                errorSample = trimError(e.getMessage());
                log.error("H22.3 projection slice failed: runId={} slice={} mode={}", runId, slice, mode, e);

                SliceRunSummary failedSlice = new SliceRunSummary(
                        slice, STATUS_FAILED, sourceFingerprint, 0, errorSample, sliceStartedAt, Instant.now()
                );
                persistSliceRun(runId, failedSlice);
                slices.add(failedSlice);
                break;
            }
        }

        Instant completedAt = Instant.now();
        jdbcTemplate.update("""
                UPDATE reporting_read.projection_run
                SET status = ?, completed_at = ?, error_sample = ?
                WHERE run_id = ?
                """, finalStatus, timestamp(completedAt), errorSample, runId);

        long successSlices = slices.stream().filter(slice -> STATUS_SUCCESS.equals(slice.status())).count();
        long skippedSlices = slices.stream().filter(slice -> STATUS_SKIPPED.equals(slice.status())).count();
        long failedSlices = slices.stream().filter(slice -> STATUS_FAILED.equals(slice.status())).count();
        log.info(
                "H22.3 projection run completed: runId={} status={} durationMs={} successSlices={} skippedSlices={} failedSlices={} error={}",
                runId, finalStatus, elapsedMs(runStartedNs), successSlices, skippedSlices, failedSlices, errorSample == null ? "none" : errorSample
        );

        return new ProjectionRunSummary(runId, mode, finalStatus, startedAt, completedAt, slices, errorSample);
    }

    private SliceProjectionResult rebuildWosSlice(String runId, Instant runStartedAt) {
        long sourceLoadNs = System.nanoTime();

        long rankingLoadNs = System.nanoTime();
        List<WosRankingView> rankingViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("id"))),
                WosRankingView.class
        );
        long rankingLoadMs = elapsedMs(rankingLoadNs);

        long metricLoadNs = System.nanoTime();
        List<WosMetricFact> metricFacts = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("metricType"), Sort.Order.asc("id"))),
                WosMetricFact.class
        );
        long metricLoadMs = elapsedMs(metricLoadNs);

        long categoryLoadNs = System.nanoTime();
        List<WosCategoryFact> categoryFacts = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("editionNormalized"), Sort.Order.asc("metricType"), Sort.Order.asc("id"))),
                WosCategoryFact.class
        );
        long categoryLoadMs = elapsedMs(categoryLoadNs);

        long scoringLoadNs = System.nanoTime();
        List<WosScoringView> scoringViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("editionNormalized"), Sort.Order.asc("metricType"), Sort.Order.asc("id"))),
                WosScoringView.class
        );
        long scoringLoadMs = elapsedMs(scoringLoadNs);

        log.info(
                "H22.3 wos source load: runId={} rankingRows={} metricRows={} categoryRows={} scoringRows={} loadMs(total={} ranking={} metric={} category={} scoring={})",
                runId,
                rankingViews.size(),
                metricFacts.size(),
                categoryFacts.size(),
                scoringViews.size(),
                elapsedMs(sourceLoadNs),
                rankingLoadMs,
                metricLoadMs,
                categoryLoadMs,
                scoringLoadMs
        );

        if (properties.isDryRun()) {
            long totalRows = rankingViews.size() + metricFacts.size() + categoryFacts.size() + scoringViews.size();
            return new SliceProjectionResult(totalRows, "dry-run: no SQL writes");
        }

        String token = tempSuffix(runId);
        long[] phaseMs = new long[4];
        transactionTemplate.executeWithoutResult(status -> {
            applyStatementTimeout();

            long setupNs = System.nanoTime();
            String tmpRanking = "tmp_wos_ranking_view_" + token;
            String tmpMetric = "tmp_wos_metric_fact_" + token;
            String tmpCategory = "tmp_wos_category_fact_" + token;
            String tmpScoring = "tmp_wos_scoring_view_" + token;

            createTempLike(tmpRanking, "reporting_read.wos_ranking_view");
            createTempLike(tmpMetric, "reporting_read.wos_metric_fact");
            createTempLike(tmpCategory, "reporting_read.wos_category_fact");
            createTempLike(tmpScoring, "reporting_read.wos_scoring_view");
            phaseMs[0] = elapsedMs(setupNs);

            long stageNs = System.nanoTime();
            long tableNs = System.nanoTime();
            insertWosRankingRows(tmpRanking, rankingViews, runId, runStartedAt);
            log.info("H22.3 wos stage load: runId={} table=wos_ranking_view rows={} durationMs={}", runId, rankingViews.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertWosMetricRows(tmpMetric, metricFacts);
            log.info("H22.3 wos stage load: runId={} table=wos_metric_fact rows={} durationMs={}", runId, metricFacts.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertWosCategoryRows(tmpCategory, categoryFacts);
            log.info("H22.3 wos stage load: runId={} table=wos_category_fact rows={} durationMs={}", runId, categoryFacts.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertWosScoringRows(tmpScoring, scoringViews, runId, runStartedAt);
            log.info("H22.3 wos stage load: runId={} table=wos_scoring_view rows={} durationMs={}", runId, scoringViews.size(), elapsedMs(tableNs));
            phaseMs[1] = elapsedMs(stageNs);

            long verifyNs = System.nanoTime();
            verifyCount(tmpRanking, rankingViews.size());
            verifyCount(tmpMetric, metricFacts.size());
            verifyCount(tmpCategory, categoryFacts.size());
            verifyCount(tmpScoring, scoringViews.size());
            phaseMs[2] = elapsedMs(verifyNs);

            long swapNs = System.nanoTime();
            jdbcTemplate.execute("""
                    TRUNCATE TABLE
                        reporting_read.wos_scoring_view,
                        reporting_read.wos_category_fact,
                        reporting_read.wos_metric_fact,
                        reporting_read.wos_ranking_view
                    """);

            jdbcTemplate.execute("INSERT INTO reporting_read.wos_ranking_view SELECT * FROM " + tmpRanking);
            jdbcTemplate.execute("INSERT INTO reporting_read.wos_metric_fact SELECT * FROM " + tmpMetric);
            jdbcTemplate.execute("INSERT INTO reporting_read.wos_category_fact SELECT * FROM " + tmpCategory);
            jdbcTemplate.execute("INSERT INTO reporting_read.wos_scoring_view SELECT * FROM " + tmpScoring);
            phaseMs[3] = elapsedMs(swapNs);
        });

        long totalRows = rankingViews.size() + metricFacts.size() + categoryFacts.size() + scoringViews.size();
        log.info(
                "H22.3 wos sql phases: runId={} tempSetupMs={} stageLoadMs={} verifyMs={} swapMs={} totalRows={}",
                runId, phaseMs[0], phaseMs[1], phaseMs[2], phaseMs[3], totalRows
        );
        return new SliceProjectionResult(totalRows, "wos slice projected");
    }

    private SliceProjectionResult rebuildScopusSlice(String runId, Instant runStartedAt) {
        long sourceLoadNs = System.nanoTime();

        long publicationLoadNs = System.nanoTime();
        List<ScholardexPublicationView> publicationViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("id"))),
                ScholardexPublicationView.class
        );
        long publicationLoadMs = elapsedMs(publicationLoadNs);

        long authorLoadNs = System.nanoTime();
        List<ScholardexAuthorView> authorViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("id"))),
                ScholardexAuthorView.class
        );
        long authorLoadMs = elapsedMs(authorLoadNs);

        long forumLoadNs = System.nanoTime();
        List<ScholardexForumView> forumViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("id"))),
                ScholardexForumView.class
        );
        long forumLoadMs = elapsedMs(forumLoadNs);

        long affiliationLoadNs = System.nanoTime();
        List<ScholardexAffiliationView> affiliationViews = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("id"))),
                ScholardexAffiliationView.class
        );
        long affiliationLoadMs = elapsedMs(affiliationLoadNs);

        long citationLoadNs = System.nanoTime();
        List<ScholardexCitationFact> citationFacts = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("citedPublicationId"), Sort.Order.asc("citingPublicationId"), Sort.Order.asc("source"), Sort.Order.asc("id"))),
                ScholardexCitationFact.class
        );
        long citationLoadMs = elapsedMs(citationLoadNs);

        long authorshipLoadNs = System.nanoTime();
        List<ScholardexAuthorshipFact> authorshipFacts = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("publicationId"), Sort.Order.asc("authorId"), Sort.Order.asc("source"), Sort.Order.asc("id"))),
                ScholardexAuthorshipFact.class
        );
        long authorshipLoadMs = elapsedMs(authorshipLoadNs);

        long authorAffiliationLoadNs = System.nanoTime();
        List<ScholardexAuthorAffiliationFact> authorAffiliationFacts = mongoTemplate.find(
                new Query().with(Sort.by(Sort.Order.asc("authorId"), Sort.Order.asc("affiliationId"), Sort.Order.asc("source"), Sort.Order.asc("id"))),
                ScholardexAuthorAffiliationFact.class
        );
        long authorAffiliationLoadMs = elapsedMs(authorAffiliationLoadNs);

        log.info(
                "H22.3 scopus source load: runId={} publicationRows={} authorRows={} forumRows={} affiliationRows={} citationRows={} authorshipRows={} authorAffiliationRows={} loadMs(total={} publication={} author={} forum={} affiliation={} citation={} authorship={} authorAffiliation={})",
                runId,
                publicationViews.size(),
                authorViews.size(),
                forumViews.size(),
                affiliationViews.size(),
                citationFacts.size(),
                authorshipFacts.size(),
                authorAffiliationFacts.size(),
                elapsedMs(sourceLoadNs),
                publicationLoadMs,
                authorLoadMs,
                forumLoadMs,
                affiliationLoadMs,
                citationLoadMs,
                authorshipLoadMs,
                authorAffiliationLoadMs
        );

        Set<String> publicationIds = new HashSet<>();
        for (ScholardexPublicationView row : publicationViews) {
            if (row.getId() != null && !row.getId().isBlank()) {
                publicationIds.add(row.getId());
            }
        }
        Set<String> authorIds = new HashSet<>();
        for (ScholardexAuthorView row : authorViews) {
            if (row.getId() != null && !row.getId().isBlank()) {
                authorIds.add(row.getId());
            }
        }
        Set<String> affiliationIds = new HashSet<>();
        for (ScholardexAffiliationView row : affiliationViews) {
            if (row.getId() != null && !row.getId().isBlank()) {
                affiliationIds.add(row.getId());
            }
        }

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
            log.warn(
                    "H22.3 scopus edge filter: runId={} droppedCitations={} droppedAuthorship={} droppedAuthorAffiliation={} reason=missing-parent-rows",
                    runId,
                    droppedCitations,
                    droppedAuthorship,
                    droppedAuthorAffiliation
            );
        }

        if (properties.isDryRun()) {
            long totalRows = publicationViews.size() + authorViews.size() + forumViews.size()
                    + affiliationViews.size() + validCitationFacts.size() + validAuthorshipFacts.size() + validAuthorAffiliationFacts.size();
            return new SliceProjectionResult(totalRows, "dry-run: no SQL writes");
        }

        String token = tempSuffix(runId);
        long[] phaseMs = new long[4];
        transactionTemplate.executeWithoutResult(status -> {
            applyStatementTimeout();

            long setupNs = System.nanoTime();
            String tmpPublication = "tmp_scholardex_publication_view_" + token;
            String tmpAuthor = "tmp_scholardex_author_view_" + token;
            String tmpForum = "tmp_scholardex_forum_view_" + token;
            String tmpAffiliation = "tmp_scholardex_affiliation_view_" + token;
            String tmpCitation = "tmp_scholardex_citation_fact_" + token;
            String tmpAuthorship = "tmp_scholardex_authorship_fact_" + token;
            String tmpAuthorAffiliation = "tmp_scholardex_author_affiliation_fact_" + token;

            createTempLike(tmpPublication, "reporting_read.scholardex_publication_view");
            createTempLike(tmpAuthor, "reporting_read.scholardex_author_view");
            createTempLike(tmpForum, "reporting_read.scholardex_forum_view");
            createTempLike(tmpAffiliation, "reporting_read.scholardex_affiliation_view");
            createTempLike(tmpCitation, "reporting_read.scholardex_citation_fact");
            createTempLike(tmpAuthorship, "reporting_read.scholardex_authorship_fact");
            createTempLike(tmpAuthorAffiliation, "reporting_read.scholardex_author_affiliation_fact");
            phaseMs[0] = elapsedMs(setupNs);

            long stageNs = System.nanoTime();
            long tableNs = System.nanoTime();
            insertPublicationRows(tmpPublication, publicationViews, runId, runStartedAt);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_publication_view rows={} durationMs={}", runId, publicationViews.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertAuthorRows(tmpAuthor, authorViews, runId, runStartedAt);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_author_view rows={} durationMs={}", runId, authorViews.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertForumRows(tmpForum, forumViews, runId, runStartedAt);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_forum_view rows={} durationMs={}", runId, forumViews.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertAffiliationRows(tmpAffiliation, affiliationViews, runId, runStartedAt);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_affiliation_view rows={} durationMs={}", runId, affiliationViews.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertCitationRows(tmpCitation, validCitationFacts);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_citation_fact rows={} durationMs={}", runId, validCitationFacts.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertAuthorshipRows(tmpAuthorship, validAuthorshipFacts);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_authorship_fact rows={} durationMs={}", runId, validAuthorshipFacts.size(), elapsedMs(tableNs));
            tableNs = System.nanoTime();
            insertAuthorAffiliationRows(tmpAuthorAffiliation, validAuthorAffiliationFacts);
            log.info("H22.3 scopus stage load: runId={} table=scholardex_author_affiliation_fact rows={} durationMs={}", runId, validAuthorAffiliationFacts.size(), elapsedMs(tableNs));
            phaseMs[1] = elapsedMs(stageNs);

            long verifyNs = System.nanoTime();
            verifyCount(tmpPublication, publicationViews.size());
            verifyCount(tmpAuthor, authorViews.size());
            verifyCount(tmpForum, forumViews.size());
            verifyCount(tmpAffiliation, affiliationViews.size());
            verifyCount(tmpCitation, validCitationFacts.size());
            verifyCount(tmpAuthorship, validAuthorshipFacts.size());
            verifyCount(tmpAuthorAffiliation, validAuthorAffiliationFacts.size());
            phaseMs[2] = elapsedMs(verifyNs);

            long swapNs = System.nanoTime();
            jdbcTemplate.execute("""
                    TRUNCATE TABLE
                        reporting_read.scholardex_author_affiliation_fact,
                        reporting_read.scholardex_authorship_fact,
                        reporting_read.scholardex_citation_fact,
                        reporting_read.scholardex_forum_view,
                        reporting_read.scholardex_author_view,
                        reporting_read.scholardex_affiliation_view,
                        reporting_read.scholardex_publication_view
                    """);

            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_publication_view SELECT * FROM " + tmpPublication);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_author_view SELECT * FROM " + tmpAuthor);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_forum_view SELECT * FROM " + tmpForum);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_affiliation_view SELECT * FROM " + tmpAffiliation);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_citation_fact SELECT * FROM " + tmpCitation);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_authorship_fact SELECT * FROM " + tmpAuthorship);
            jdbcTemplate.execute("INSERT INTO reporting_read.scholardex_author_affiliation_fact SELECT * FROM " + tmpAuthorAffiliation);
            phaseMs[3] = elapsedMs(swapNs);
        });

        long totalRows = publicationViews.size() + authorViews.size() + forumViews.size()
                + affiliationViews.size() + validCitationFacts.size() + validAuthorshipFacts.size() + validAuthorAffiliationFacts.size();
        log.info(
                "H22.3 scopus sql phases: runId={} tempSetupMs={} stageLoadMs={} verifyMs={} swapMs={} totalRows={}",
                runId, phaseMs[0], phaseMs[1], phaseMs[2], phaseMs[3], totalRows
        );
        return new SliceProjectionResult(
                totalRows,
                "scopus slice projected (dropped: citations=" + droppedCitations
                        + ", authorship=" + droppedAuthorship
                        + ", authorAffiliation=" + droppedAuthorAffiliation + ")"
        );
    }

    private void insertWosRankingRows(String tempTable, List<WosRankingView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (
                    journal_id, name, issn, e_issn, alternative_issns, alternative_names,
                    name_norm, issn_norm, e_issn_norm, alternative_issns_norm,
                    latest_ais_year, latest_ris_year, latest_edition_normalized,
                    build_version, build_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                WosRankingView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setString(3, row.getIssn());
                ps.setString(4, row.getEIssn());
                ps.setArray(5, textArray(ps.getConnection(), row.getAlternativeIssns()));
                ps.setArray(6, textArray(ps.getConnection(), row.getAlternativeNames()));
                ps.setString(7, row.getNameNorm());
                ps.setString(8, row.getIssnNorm());
                ps.setString(9, row.getEIssnNorm());
                ps.setArray(10, textArray(ps.getConnection(), row.getAlternativeIssnsNorm()));
                setInteger(ps, 11, row.getLatestAisYear());
                setInteger(ps, 12, row.getLatestRisYear());
                setEnum(ps, 13, row.getLatestEditionNormalized() == null ? null : row.getLatestEditionNormalized().name());
                ps.setString(14, runId);
                setInstant(ps, 15, runStartedAt);
                setInstant(ps, 16, runStartedAt);
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertWosMetricRows(String tempTable, List<WosMetricFact> rows) {
        String sql = """
                INSERT INTO %s (
                    id, journal_id, year, metric_type, value,
                    source_type, source_event_id, source_file, source_version, source_row_item, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                WosMetricFact row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getJournalId());
                setInteger(ps, 3, row.getYear());
                setEnum(ps, 4, row.getMetricType() == null ? null : row.getMetricType().name());
                setDouble(ps, 5, row.getValue());
                setEnum(ps, 6, row.getSourceType() == null ? null : row.getSourceType().name());
                ps.setString(7, row.getSourceEventId());
                ps.setString(8, row.getSourceFile());
                ps.setString(9, row.getSourceVersion());
                ps.setString(10, row.getSourceRowItem());
                setInstant(ps, 11, row.getCreatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertWosCategoryRows(String tempTable, List<WosCategoryFact> rows) {
        String sql = """
                INSERT INTO %s (
                    id, journal_id, year, category_name_canonical,
                    edition_raw, edition_normalized, metric_type,
                    quarter, quartile_rank, rank,
                    source_type, source_event_id, source_file, source_version, source_row_item, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                WosCategoryFact row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getJournalId());
                setInteger(ps, 3, row.getYear());
                ps.setString(4, row.getCategoryNameCanonical());
                ps.setString(5, row.getEditionRaw());
                setEnum(ps, 6, row.getEditionNormalized() == null ? null : row.getEditionNormalized().name());
                setEnum(ps, 7, row.getMetricType() == null ? null : row.getMetricType().name());
                ps.setString(8, row.getQuarter());
                setInteger(ps, 9, row.getQuartileRank());
                setInteger(ps, 10, row.getRank());
                setEnum(ps, 11, row.getSourceType() == null ? null : row.getSourceType().name());
                ps.setString(12, row.getSourceEventId());
                ps.setString(13, row.getSourceFile());
                ps.setString(14, row.getSourceVersion());
                ps.setString(15, row.getSourceRowItem());
                setInstant(ps, 16, row.getCreatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertWosScoringRows(String tempTable, List<WosScoringView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (
                    id, journal_id, year, category_name_canonical,
                    edition_normalized, metric_type, value, quarter,
                    quartile_rank, rank, build_version, build_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                WosScoringView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getJournalId());
                setInteger(ps, 3, row.getYear());
                ps.setString(4, row.getCategoryNameCanonical());
                setEnum(ps, 5, row.getEditionNormalized() == null ? null : row.getEditionNormalized().name());
                setEnum(ps, 6, row.getMetricType() == null ? null : row.getMetricType().name());
                setDouble(ps, 7, row.getValue());
                ps.setString(8, row.getQuarter());
                setInteger(ps, 9, row.getQuartileRank());
                setInteger(ps, 10, row.getRank());
                ps.setString(11, runId);
                setInstant(ps, 12, runStartedAt);
                setInstant(ps, 13, runStartedAt);
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertPublicationRows(String tempTable, List<ScholardexPublicationView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (
                    id, doi, doi_normalized, eid, title, subtype, subtype_description,
                    scopus_subtype, scopus_subtype_description, creator, cover_date, cover_display_date,
                    volume, issue_identifier, description, author_count, corresponding_authors,
                    open_access, freetoread, freetoread_label, funding_id, article_number, page_range,
                    approved, author_ids, affiliation_ids, forum_id, citing_publication_ids, cited_by_count,
                    wos_id, google_scholar_id, build_version, build_at, updated_at,
                    scopus_lineage, wos_lineage, scholar_lineage, linker_version, linker_run_id, linked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
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
                ps.setString(32, runId);
                setInstant(ps, 33, runStartedAt);
                setInstant(ps, 34, runStartedAt);
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

    private void insertAuthorRows(String tempTable, List<ScholardexAuthorView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (id, name, affiliation_ids, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAuthorView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setArray(3, textArray(ps.getConnection(), row.getAffiliationIds()));
                ps.setString(4, runId);
                setInstant(ps, 5, runStartedAt);
                setInstant(ps, 6, runStartedAt);
                ps.setString(7, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertForumRows(String tempTable, List<ScholardexForumView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (id, publication_name, issn, e_issn, aggregation_type, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexForumView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getPublicationName());
                ps.setString(3, row.getIssn());
                ps.setString(4, row.getEIssn());
                ps.setString(5, row.getAggregationType());
                ps.setString(6, runId);
                setInstant(ps, 7, runStartedAt);
                setInstant(ps, 8, runStartedAt);
                ps.setString(9, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertAffiliationRows(String tempTable, List<ScholardexAffiliationView> rows, String runId, Instant runStartedAt) {
        String sql = """
                INSERT INTO %s (id, name, city, country, build_version, build_at, updated_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
        batchInChunks(rows, chunk -> jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ScholardexAffiliationView row = chunk.get(i);
                ps.setString(1, row.getId());
                ps.setString(2, row.getName());
                ps.setString(3, row.getCity());
                ps.setString(4, row.getCountry());
                ps.setString(5, runId);
                setInstant(ps, 6, runStartedAt);
                setInstant(ps, 7, runStartedAt);
                ps.setString(8, row.getSourceEventId());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void insertCitationRows(String tempTable, List<ScholardexCitationFact> rows) {
        String sql = """
                INSERT INTO %s (
                    id, cited_publication_id, citing_publication_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
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

    private void insertAuthorshipRows(String tempTable, List<ScholardexAuthorshipFact> rows) {
        String sql = """
                INSERT INTO %s (
                    id, publication_id, author_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    link_state, link_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
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

    private void insertAuthorAffiliationRows(String tempTable, List<ScholardexAuthorAffiliationFact> rows) {
        String sql = """
                INSERT INTO %s (
                    id, author_id, affiliation_id, source,
                    source_record_id, source_event_id, source_batch_id, source_correlation_id,
                    link_state, link_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tempTable);
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

    private void createTempLike(String tempTable, String sourceTable) {
        jdbcTemplate.execute("CREATE TEMP TABLE " + tempTable + " (LIKE " + sourceTable + " INCLUDING DEFAULTS) ON COMMIT DROP");
    }

    private void verifyCount(String tableName, int expectedCount) {
        Long actualCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        long actual = actualCount == null ? 0 : actualCount;
        if (actual != expectedCount) {
            throw new IllegalStateException("staging count mismatch for " + tableName + ": expected=" + expectedCount + " actual=" + actual);
        }
    }

    private void applyStatementTimeout() {
        int timeoutMs = Math.max(1000, properties.getStatementTimeoutMs());
        jdbcTemplate.execute("SET LOCAL statement_timeout = " + timeoutMs);
    }

    private String computeWosFingerprint() {
        String raw = String.join("|",
                "journal.count=" + countDocuments("wos.journal_identity"),
                "journal.maxUpdated=" + normalizeInstant(maxInstant("wos.journal_identity", "updatedAt")),
                "metric.count=" + countDocuments("wos.metric_facts"),
                "metric.maxCreated=" + normalizeInstant(maxInstant("wos.metric_facts", "createdAt")),
                "metric.maxVersion=" + nullToEmpty(maxString("wos.metric_facts", "sourceVersion")),
                "category.count=" + countDocuments("wos.category_facts"),
                "category.maxCreated=" + normalizeInstant(maxInstant("wos.category_facts", "createdAt")),
                "category.maxVersion=" + nullToEmpty(maxString("wos.category_facts", "sourceVersion"))
        );
        return sha256Hex(raw);
    }

    private String computeScopusFingerprint() {
        String raw = String.join("|",
                fingerprintPart("scholardex.publication_view", "updatedAt", "buildAt"),
                fingerprintPart("scholardex.author_view", "updatedAt", "buildAt"),
                fingerprintPart("scholardex.forum_view", "updatedAt", "buildAt"),
                fingerprintPart("scholardex.affiliation_view", "updatedAt", "buildAt"),
                fingerprintPart("scholardex.citation_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.authorship_facts", "updatedAt", "createdAt"),
                fingerprintPart("scholardex.author_affiliation_facts", "updatedAt", "createdAt")
        );
        return sha256Hex(raw);
    }

    private String fingerprintPart(String collectionName, String maxPrimaryField, String maxSecondaryField) {
        return collectionName + ":count=" + countDocuments(collectionName)
                + ",maxPrimary=" + normalizeInstant(maxInstant(collectionName, maxPrimaryField))
                + ",maxSecondary=" + normalizeInstant(maxInstant(collectionName, maxSecondaryField));
    }

    private long countDocuments(String collectionName) {
        return mongoTemplate.getCollection(collectionName).countDocuments();
    }

    private Instant maxInstant(String collectionName, String fieldName) {
        Document doc = mongoTemplate.getCollection(collectionName)
                .find(and(exists(fieldName), ne(fieldName, null)))
                .sort(descending(fieldName))
                .projection(new Document(fieldName, 1).append("_id", 0))
                .first();
        if (doc == null) {
            return null;
        }
        Object value = doc.get(fieldName);
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private String maxString(String collectionName, String fieldName) {
        Document doc = mongoTemplate.getCollection(collectionName)
                .find(and(exists(fieldName), ne(fieldName, null)))
                .sort(descending(fieldName))
                .projection(new Document(fieldName, 1).append("_id", 0))
                .first();
        if (doc == null) {
            return null;
        }
        Object value = doc.get(fieldName);
        return value == null ? null : value.toString();
    }

    private void persistSliceRun(String runId, SliceRunSummary summary) {
        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_slice_run
                (run_id, slice_name, status, source_fingerprint, inserted_rows, note, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                summary.sliceName(),
                summary.status(),
                summary.sourceFingerprint(),
                summary.insertedRows(),
                summary.note(),
                timestamp(summary.startedAt()),
                timestamp(summary.completedAt())
        );
    }

    private void upsertCheckpoint(String sliceName, String sourceFingerprint, String runId, String mode) {
        jdbcTemplate.update("""
                INSERT INTO reporting_read.projection_checkpoint
                (slice_name, source_fingerprint, last_run_id, last_success_at, last_mode)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (slice_name)
                DO UPDATE SET
                  source_fingerprint = EXCLUDED.source_fingerprint,
                  last_run_id = EXCLUDED.last_run_id,
                  last_success_at = EXCLUDED.last_success_at,
                  last_mode = EXCLUDED.last_mode
                """, sliceName, sourceFingerprint, runId, timestamp(Instant.now()), mode);
    }

    private ProjectionRunSummary queryLatestRun() {
        List<ProjectionRunSummary> runs = jdbcTemplate.query("""
                SELECT run_id, mode, status, started_at, completed_at, error_sample
                FROM reporting_read.projection_run
                ORDER BY started_at DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            String runId = rs.getString("run_id");
            List<SliceRunSummary> slices = querySliceRuns(runId);
            return new ProjectionRunSummary(
                    runId,
                    rs.getString("mode"),
                    rs.getString("status"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    slices,
                    rs.getString("error_sample")
            );
        });
        return runs.isEmpty() ? null : runs.get(0);
    }

    private List<SliceRunSummary> querySliceRuns(String runId) {
        return jdbcTemplate.query("""
                SELECT slice_name, status, source_fingerprint, inserted_rows, note, started_at, completed_at
                FROM reporting_read.projection_slice_run
                WHERE run_id = ?
                ORDER BY started_at ASC
                """, new Object[]{runId}, (rs, rowNum) -> new SliceRunSummary(
                rs.getString("slice_name"),
                rs.getString("status"),
                rs.getString("source_fingerprint"),
                rs.getLong("inserted_rows"),
                rs.getString("note"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at"))
        ));
    }

    private Map<String, CheckpointSummary> queryCheckpoints() {
        List<CheckpointSummary> rows = jdbcTemplate.query("""
                SELECT slice_name, source_fingerprint, last_run_id, last_success_at, last_mode
                FROM reporting_read.projection_checkpoint
                """, checkpointRowMapper());
        Map<String, CheckpointSummary> out = new LinkedHashMap<>();
        for (CheckpointSummary row : rows) {
            out.put(row.sliceName(), row);
        }
        return out;
    }

    private RowMapper<CheckpointSummary> checkpointRowMapper() {
        return (rs, rowNum) -> new CheckpointSummary(
                rs.getString("slice_name"),
                rs.getString("source_fingerprint"),
                rs.getString("last_run_id"),
                toInstant(rs.getTimestamp("last_success_at")),
                rs.getString("last_mode")
        );
    }

    private <T> void batchInChunks(List<T> rows, Consumer<List<T>> chunkWriter) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int chunkSize = Math.max(1, properties.getChunkSize());
        for (int i = 0; i < rows.size(); i += chunkSize) {
            int to = Math.min(rows.size(), i + chunkSize);
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

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setEnum(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.OTHER);
        } else {
            ps.setObject(index, value, java.sql.Types.OTHER);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trimError(String raw) {
        if (raw == null) {
            return "unknown error";
        }
        String trimmed = raw.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500);
    }

    private static String normalizeInstant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String tempSuffix(String runId) {
        String normalized = runId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isBlank()) {
            normalized = UUID.randomUUID().toString().replace("-", "");
        }
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private record SliceProjectionResult(long insertedRows, String note) {
    }
}
