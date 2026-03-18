package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class JdbcDualReadGateService implements DualReadGateService {
    private static final Logger log = LoggerFactory.getLogger(JdbcDualReadGateService.class);

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String SCENARIO_WOS_ISSN = "wos.issn-ranking.lookup";
    private static final String SCENARIO_WOS_TOP = "wos.top-rankings.count";
    private static final String SCENARIO_SCOPUS_AUTHOR = "scopus.author.search";
    private static final String SCENARIO_SCOPUS_FORUM = "scopus.forum.search";
    private static final String SCENARIO_SCOPUS_AFFILIATION = "scopus.affiliation.search";
    private static final String SCENARIO_ADMIN_PUBLICATION = "admin.scopus.publication.search";
    private static final String SCENARIO_ADMIN_CITATIONS = "admin.scopus.citations.view";
    private static final String SCENARIO_GROUP_REPORT_REFRESH = "admin.group.report.refresh";

    private static final String SCENARIO_TYPE_DUAL_PARITY = "DUAL_PARITY";
    private static final String SCENARIO_TYPE_PERF_ONLY = "PERF_ONLY";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DualReadGateProperties properties;
    private final MongoTemplate mongoTemplate;

    private final ProjectionBackedReportingLookupFacade mongoReportingLookup;
    private final ObjectProvider<PostgresReportingLookupFacade> postgresReportingLookupProvider;

    private final MongoScholardexAuthorReadPort mongoScholardexAuthorReadPort;
    private final ObjectProvider<PostgresScholardexAuthorReadPort> postgresScholardexAuthorReadPortProvider;

    private final MongoScholardexForumReadPort mongoScholardexForumReadPort;
    private final ObjectProvider<PostgresScholardexForumReadPort> postgresScholardexForumReadPortProvider;

    private final MongoScholardexAffiliationReadPort mongoScholardexAffiliationReadPort;
    private final ObjectProvider<PostgresScholardexAffiliationReadPort> postgresScholardexAffiliationReadPortProvider;

    private final MongoScholardexAdminReadPort mongoScholardexAdminReadPort;
    private final ObjectProvider<PostgresScholardexAdminReadPort> postgresScholardexAdminReadPortProvider;
    private final GroupReportFacade groupReportFacade;
    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;

    public JdbcDualReadGateService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DualReadGateProperties properties,
            MongoTemplate mongoTemplate,
            ProjectionBackedReportingLookupFacade mongoReportingLookup,
            ObjectProvider<PostgresReportingLookupFacade> postgresReportingLookupProvider,
            MongoScholardexAuthorReadPort mongoScholardexAuthorReadPort,
            ObjectProvider<PostgresScholardexAuthorReadPort> postgresScholardexAuthorReadPortProvider,
            MongoScholardexForumReadPort mongoScholardexForumReadPort,
            ObjectProvider<PostgresScholardexForumReadPort> postgresScholardexForumReadPortProvider,
            MongoScholardexAffiliationReadPort mongoScholardexAffiliationReadPort,
            ObjectProvider<PostgresScholardexAffiliationReadPort> postgresScholardexAffiliationReadPortProvider,
            MongoScholardexAdminReadPort mongoScholardexAdminReadPort,
            ObjectProvider<PostgresScholardexAdminReadPort> postgresScholardexAdminReadPortProvider,
            GroupReportFacade groupReportFacade,
            GroupRepository groupRepository,
            IndividualReportRepository individualReportRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mongoTemplate = mongoTemplate;
        this.mongoReportingLookup = mongoReportingLookup;
        this.postgresReportingLookupProvider = postgresReportingLookupProvider;
        this.mongoScholardexAuthorReadPort = mongoScholardexAuthorReadPort;
        this.postgresScholardexAuthorReadPortProvider = postgresScholardexAuthorReadPortProvider;
        this.mongoScholardexForumReadPort = mongoScholardexForumReadPort;
        this.postgresScholardexForumReadPortProvider = postgresScholardexForumReadPortProvider;
        this.mongoScholardexAffiliationReadPort = mongoScholardexAffiliationReadPort;
        this.postgresScholardexAffiliationReadPortProvider = postgresScholardexAffiliationReadPortProvider;
        this.mongoScholardexAdminReadPort = mongoScholardexAdminReadPort;
        this.postgresScholardexAdminReadPortProvider = postgresScholardexAdminReadPortProvider;
        this.groupReportFacade = groupReportFacade;
        this.groupRepository = groupRepository;
        this.individualReportRepository = individualReportRepository;
    }

    @Override
    public DualReadGateRunSummary runFullGate() {
        int sampleSize = Math.max(1, properties.getSampleSize());
        double p95RatioThreshold = properties.getP95RatioThreshold();
        String runId = "h22-dual-read-gate-" + UUID.randomUUID();
        Instant startedAt = Instant.now();

        jdbcTemplate.update(
                """
                        INSERT INTO reporting_read.dual_read_gate_run
                        (run_id, status, sample_size, p95_ratio_threshold, started_at, failed_scenarios)
                        VALUES (?, ?, ?, ?, ?, 0)
                        """,
                runId,
                STATUS_RUNNING,
                sampleSize,
                p95RatioThreshold,
                timestamp(startedAt)
        );

        List<DualReadScenarioResult> scenarioResults = new ArrayList<>();
        int failedScenarios = 0;
        String errorSample = null;
        String runStatus = STATUS_SUCCESS;

        try {
            List<DualReadScenario> scenarios = buildScenarios();
            for (DualReadScenario scenario : scenarios) {
                DualReadScenarioResult result = executeScenario(runId, scenario, sampleSize, p95RatioThreshold);
                scenarioResults.add(result);
                if (!STATUS_SUCCESS.equals(result.status())) {
                    failedScenarios++;
                    if (errorSample == null && result.mismatchSample() != null) {
                        errorSample = result.mismatchSample();
                    }
                }
            }
            if (failedScenarios > 0) {
                runStatus = STATUS_FAILED;
            }
        } catch (Exception e) {
            runStatus = STATUS_FAILED;
            errorSample = trim(e.getMessage());
        }

        Instant completedAt = Instant.now();

        jdbcTemplate.update(
                """
                        UPDATE reporting_read.dual_read_gate_run
                        SET status = ?, completed_at = ?, failed_scenarios = ?, error_sample = ?
                        WHERE run_id = ?
                        """,
                runStatus,
                timestamp(completedAt),
                failedScenarios,
                trim(errorSample),
                runId
        );

        return new DualReadGateRunSummary(
                runId,
                runStatus,
                sampleSize,
                p95RatioThreshold,
                startedAt,
                completedAt,
                scenarioResults,
                trim(errorSample)
        );
    }

    @Override
    public DualReadGateStatusSnapshot latestStatus() {
        List<DualReadGateRunSummary> rows = jdbcTemplate.query(
                """
                        SELECT run_id, status, sample_size, p95_ratio_threshold, started_at, completed_at, error_sample
                        FROM reporting_read.dual_read_gate_run
                        ORDER BY started_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> {
                    String runId = rs.getString("run_id");
                    List<DualReadScenarioResult> scenarios = queryScenarioRuns(runId);
                    return new DualReadGateRunSummary(
                            runId,
                            rs.getString("status"),
                            rs.getInt("sample_size"),
                            rs.getDouble("p95_ratio_threshold"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("completed_at")),
                            scenarios,
                            rs.getString("error_sample")
                    );
                }
        );

        return new DualReadGateStatusSnapshot(rows.isEmpty() ? null : rows.getFirst());
    }

    static double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    static double p95(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(0.95d * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    static Double ratio(double numerator, double denominator) {
        if (denominator <= 0d) {
            return numerator <= 0d ? 1d : Double.POSITIVE_INFINITY;
        }
        return numerator / denominator;
    }

    private DualReadScenarioResult executeScenario(
            String runId,
            DualReadScenario scenario,
            int sampleSize,
            double p95RatioThreshold
    ) {
        Instant startedAt = Instant.now();
        String datasetNotReadyReason = datasetNotReadyReason(scenario);
        if (datasetNotReadyReason != null) {
            Instant completedAt = Instant.now();
            DualReadScenarioResult result = new DualReadScenarioResult(
                    scenario.scenarioId(),
                    scenario.scenarioType(),
                    STATUS_FAILED,
                    false,
                    false,
                    0d,
                    0d,
                    0d,
                    0d,
                    null,
                    scenario.p95ThresholdMs(),
                    datasetNotReadyReason,
                    startedAt,
                    completedAt
            );
            persistScenarioRun(runId, result);
            log.warn("H22.6 dual-read dataset not ready: runId={} scenario={} reason={}", runId, scenario.scenarioId(), datasetNotReadyReason);
            return result;
        }

        if (SCENARIO_TYPE_PERF_ONLY.equals(scenario.scenarioType())) {
            DualReadScenarioResult result = executePerfOnlyScenario(runId, scenario, sampleSize, startedAt);
            persistScenarioRun(runId, result);
            return result;
        }

        DualReadScenarioResult result = executeDualParityScenario(runId, scenario, sampleSize, p95RatioThreshold, startedAt);
        persistScenarioRun(runId, result);
        return result;
    }

    private DualReadScenarioResult executeDualParityScenario(
            String runId,
            DualReadScenario scenario,
            int sampleSize,
            double p95RatioThreshold,
            Instant startedAt
    ) {
        boolean parityPassed = true;
        String mismatchSample = null;
        List<Double> mongoLatencies = new ArrayList<>();
        List<Double> postgresLatencies = new ArrayList<>();

        if (SCENARIO_WOS_ISSN.equals(scenario.scenarioId()) && properties.isWosIssnWarmupEnabled()) {
            TimedCall warmupMongo = timedCall(scenario.mongoSupplier());
            TimedCall warmupPostgres = timedCall(scenario.postgresSupplier());
            if (log.isDebugEnabled()) {
                log.debug(
                        "H22.6 dual-read warm-up excluded from metrics: runId={} scenario={} mongoElapsedMs={} postgresElapsedMs={} mongoError={} postgresError={}",
                        runId,
                        scenario.scenarioId(),
                        String.format(Locale.ROOT, "%.1f", warmupMongo.elapsedMs()),
                        String.format(Locale.ROOT, "%.1f", warmupPostgres.elapsedMs()),
                        errorSummary(warmupMongo.error()),
                        errorSummary(warmupPostgres.error())
                );
            }
        }

        for (int i = 0; i < sampleSize; i++) {
            TimedCall mongoCall = timedCall(scenario.mongoSupplier());
            TimedCall postgresCall = timedCall(scenario.postgresSupplier());
            mongoLatencies.add(mongoCall.elapsedMs());
            postgresLatencies.add(postgresCall.elapsedMs());

            if (mongoCall.error() != null || postgresCall.error() != null) {
                String runtimeDiff = runtimeMismatch(mongoCall.error(), postgresCall.error());
                if (runtimeDiff != null) {
                    parityPassed = false;
                    if (mismatchSample == null) {
                        mismatchSample = scenario.scenarioId() + " runtime mismatch: " + runtimeDiff;
                        log.warn(
                                "H22.6 dual-read runtime mismatch: runId={} scenario={} mongoError={} postgresError={}",
                                runId,
                                scenario.scenarioId(),
                                errorSummary(mongoCall.error()),
                                errorSummary(postgresCall.error())
                        );
                    }
                }
                continue;
            }

            String parityMismatch = scenario.comparator().compare(mongoCall.value(), postgresCall.value());
            if (parityMismatch != null) {
                parityPassed = false;
                if (mismatchSample == null) {
                    mismatchSample = scenario.scenarioId() + " parity mismatch: " + parityMismatch;
                }
            }
        }

        double mongoAvg = average(mongoLatencies);
        double mongoP95 = p95(mongoLatencies);
        double postgresAvg = average(postgresLatencies);
        double postgresP95 = p95(postgresLatencies);
        Double p95Ratio = ratio(postgresP95, mongoP95);
        boolean performancePassed = p95Ratio <= p95RatioThreshold;

        String status = parityPassed && performancePassed ? STATUS_SUCCESS : STATUS_FAILED;
        if (!performancePassed && mismatchSample == null) {
            mismatchSample = String.format(
                    Locale.ROOT,
                    "performance ratio %.4f exceeded threshold %.4f",
                    p95Ratio,
                    p95RatioThreshold
            );
        }

        Instant completedAt = Instant.now();
        DualReadScenarioResult result = new DualReadScenarioResult(
                scenario.scenarioId(),
                scenario.scenarioType(),
                status,
                parityPassed,
                performancePassed,
                mongoAvg,
                mongoP95,
                postgresAvg,
                postgresP95,
                p95Ratio,
                null,
                trim(mismatchSample),
                startedAt,
                completedAt
        );
        return result;
    }

    private DualReadScenarioResult executePerfOnlyScenario(
            String runId,
            DualReadScenario scenario,
            int sampleSize,
            Instant startedAt
    ) {
        boolean parityPassed = true;
        String mismatchSample = null;
        List<Double> latencies = new ArrayList<>();

        for (int i = 0; i < sampleSize; i++) {
            TimedCall timedCall = timedCall(scenario.mongoSupplier());
            latencies.add(timedCall.elapsedMs());
            log.debug(
                    "H22.6 perf-only sample: runId={} scenario={} sample={}/{} elapsedMs={}",
                    runId,
                    scenario.scenarioId(),
                    i + 1,
                    sampleSize,
                    String.format(Locale.ROOT, "%.1f", timedCall.elapsedMs())
            );
            if (timedCall.error() != null) {
                parityPassed = false;
                if (mismatchSample == null) {
                    mismatchSample = scenario.scenarioId() + " runtime error: " + errorSummary(timedCall.error());
                }
            }
        }

        double postgresAvg = average(latencies);
        double postgresP95 = p95(latencies);
        double p95ThresholdMs = scenario.p95ThresholdMs() == null ? 0d : scenario.p95ThresholdMs();
        boolean performancePassed = parityPassed && postgresP95 <= p95ThresholdMs;
        if (parityPassed && !performancePassed) {
            mismatchSample = String.format(
                    Locale.ROOT,
                    "p95 %.1fms exceeded threshold %.1fms",
                    postgresP95,
                    p95ThresholdMs
            );
        }

        String status = parityPassed && performancePassed ? STATUS_SUCCESS : STATUS_FAILED;
        if (!performancePassed) {
            log.warn(
                    "H22.6 perf-only threshold breach: runId={} scenario={} p95Ms={} thresholdMs={} sampleSize={}",
                    runId,
                    scenario.scenarioId(),
                    String.format(Locale.ROOT, "%.1f", postgresP95),
                    String.format(Locale.ROOT, "%.1f", p95ThresholdMs),
                    sampleSize
            );
        } else {
            log.info(
                    "H22.6 perf-only scenario passed: runId={} scenario={} p95Ms={} thresholdMs={} sampleSize={}",
                    runId,
                    scenario.scenarioId(),
                    String.format(Locale.ROOT, "%.1f", postgresP95),
                    String.format(Locale.ROOT, "%.1f", p95ThresholdMs),
                    sampleSize
            );
        }
        Instant completedAt = Instant.now();
        DualReadScenarioResult result = new DualReadScenarioResult(
                scenario.scenarioId(),
                scenario.scenarioType(),
                status,
                parityPassed,
                performancePassed,
                0d,
                0d,
                postgresAvg,
                postgresP95,
                null,
                p95ThresholdMs,
                trim(mismatchSample),
                startedAt,
                completedAt
        );
        return result;
    }

    private List<DualReadScenarioResult> queryScenarioRuns(String runId) {
        return jdbcTemplate.query(
                """
                        SELECT scenario_id, status, parity_passed, performance_passed,
                               mongo_avg_ms, mongo_p95_ms, postgres_avg_ms, postgres_p95_ms,
                               p95_ratio, mismatch_sample, started_at, completed_at
                        FROM reporting_read.dual_read_gate_scenario_run
                        WHERE run_id = ?
                        ORDER BY started_at ASC
                        """,
                ps -> ps.setString(1, runId),
                (rs, rowNum) -> new DualReadScenarioResult(
                        rs.getString("scenario_id"),
                        resolveScenarioType(rs.getString("scenario_id")),
                        rs.getString("status"),
                        rs.getBoolean("parity_passed"),
                        rs.getBoolean("performance_passed"),
                        rs.getDouble("mongo_avg_ms"),
                        rs.getDouble("mongo_p95_ms"),
                        rs.getDouble("postgres_avg_ms"),
                        rs.getDouble("postgres_p95_ms"),
                        (Double) rs.getObject("p95_ratio"),
                        resolveP95ThresholdMs(rs.getString("scenario_id")),
                        rs.getString("mismatch_sample"),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at"))
                )
        );
    }

    private void persistScenarioRun(String runId, DualReadScenarioResult summary) {
        jdbcTemplate.update(
                """
                        INSERT INTO reporting_read.dual_read_gate_scenario_run
                        (run_id, scenario_id, status, parity_passed, performance_passed,
                         mongo_avg_ms, mongo_p95_ms, postgres_avg_ms, postgres_p95_ms,
                         p95_ratio, mismatch_sample, started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId,
                summary.scenarioId(),
                summary.status(),
                summary.parityPassed(),
                summary.performancePassed(),
                summary.mongoAvgMs(),
                summary.mongoP95Ms(),
                summary.postgresAvgMs(),
                summary.postgresP95Ms(),
                summary.p95Ratio(),
                summary.mismatchSample(),
                timestamp(summary.startedAt()),
                timestamp(summary.completedAt())
        );
    }

    private List<DualReadScenario> buildScenarios() {
        PostgresReportingLookupFacade postgresReportingLookup = requirePostgres(postgresReportingLookupProvider, "PostgresReportingLookupFacade");
        PostgresScholardexAuthorReadPort postgresAuthorReadPort = requirePostgres(postgresScholardexAuthorReadPortProvider, "PostgresScholardexAuthorReadPort");
        PostgresScholardexForumReadPort postgresForumReadPort = requirePostgres(postgresScholardexForumReadPortProvider, "PostgresScholardexForumReadPort");
        PostgresScholardexAffiliationReadPort postgresAffiliationReadPort = requirePostgres(postgresScholardexAffiliationReadPortProvider, "PostgresScholardexAffiliationReadPort");
        PostgresScholardexAdminReadPort postgresAdminReadPort = requirePostgres(postgresScholardexAdminReadPortProvider, "PostgresScholardexAdminReadPort");

        ScenarioInput input = resolveScenarioInput();

        List<DualReadScenario> scenarios = new ArrayList<>(List.of(
                new DualReadScenario(
                        SCENARIO_WOS_ISSN,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoReportingLookup.getRankingsByIssn(input.issn()),
                        () -> postgresReportingLookup.getRankingsByIssn(input.issn()),
                        (mongo, postgres) -> compareJson(normalizeWosRankings(cast(mongo)), normalizeWosRankings(cast(postgres))),
                        null
                ),
                new DualReadScenario(
                        SCENARIO_WOS_TOP,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoReportingLookup.getTopRankings(input.categoryIndex(), input.year()),
                        () -> postgresReportingLookup.getTopRankings(input.categoryIndex(), input.year()),
                        this::compareJson,
                        null
                ),
                new DualReadScenario(
                        SCENARIO_SCOPUS_AUTHOR,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoScholardexAuthorReadPort.search(input.afid(), 0, 25, "name", "asc", null),
                        () -> postgresAuthorReadPort.search(input.afid(), 0, 25, "name", "asc", null),
                        (mongo, postgres) -> compareAuthorPage(cast(mongo), cast(postgres)),
                        null
                ),
                new DualReadScenario(
                        SCENARIO_SCOPUS_FORUM,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoScholardexForumReadPort.search(0, 25, "publicationName", "asc", input.forumQuery()),
                        () -> postgresForumReadPort.search(0, 25, "publicationName", "asc", input.forumQuery()),
                        this::compareJson,
                        null
                ),
                new DualReadScenario(
                        SCENARIO_SCOPUS_AFFILIATION,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoScholardexAffiliationReadPort.search(0, 25, "name", "asc", input.affiliationQuery()),
                        () -> postgresAffiliationReadPort.search(0, 25, "name", "asc", input.affiliationQuery()),
                        this::compareJson,
                        null
                ),
                new DualReadScenario(
                        SCENARIO_ADMIN_PUBLICATION,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoScholardexAdminReadPort.buildPublicationSearchView(input.publicationTitleQuery()),
                        () -> postgresAdminReadPort.buildPublicationSearchView(input.publicationTitleQuery()),
                        (mongo, postgres) -> comparePublicationSearch(cast(mongo), cast(postgres)),
                        null
                ),
                new DualReadScenario(
                        SCENARIO_ADMIN_CITATIONS,
                        SCENARIO_TYPE_DUAL_PARITY,
                        () -> mongoScholardexAdminReadPort.buildPublicationCitationsView(input.citationPublicationId()),
                        () -> postgresAdminReadPort.buildPublicationCitationsView(input.citationPublicationId()),
                        (mongo, postgres) -> compareCitationsView(cast(mongo), cast(postgres)),
                        null
                )
        ));

        if (properties.isGroupReportRefreshEnabled()) {
            scenarios.add(new DualReadScenario(
                    SCENARIO_GROUP_REPORT_REFRESH,
                    SCENARIO_TYPE_PERF_ONLY,
                    () -> groupReportFacade.refreshGroupIndividualReportView(
                            input.groupReportRefreshGroupId(),
                            input.groupReportRefreshReportId()
                    ),
                    null,
                    null,
                    properties.getGroupReportRefreshP95ThresholdMs()
            ));
        }
        return scenarios;
    }

    private String compareAuthorPage(ScopusAuthorPageResponse mongo, ScopusAuthorPageResponse postgres) {
        List<Map<String, Object>> mongoItems = mongo.items().stream()
                .map(item -> Map.<String, Object>of(
                        "id", item.id(),
                        "name", item.name(),
                        "affiliations", item.affiliations().stream().sorted().toList()
                ))
                .toList();
        List<Map<String, Object>> postgresItems = postgres.items().stream()
                .map(item -> Map.<String, Object>of(
                        "id", item.id(),
                        "name", item.name(),
                        "affiliations", item.affiliations().stream().sorted().toList()
                ))
                .toList();

        return compareJson(
                Map.of(
                        "items", mongoItems,
                        "page", mongo.page(),
                        "size", mongo.size(),
                        "totalItems", mongo.totalItems(),
                        "totalPages", mongo.totalPages()
                ),
                Map.of(
                        "items", postgresItems,
                        "page", postgres.page(),
                        "size", postgres.size(),
                        "totalItems", postgres.totalItems(),
                        "totalPages", postgres.totalPages()
                )
        );
    }

    private String comparePublicationSearch(AdminScopusPublicationSearchViewModel mongo, AdminScopusPublicationSearchViewModel postgres) {
        Map<String, Object> mongoNormalized = Map.of(
                "publications", normalizePublications(mongo.publications()),
                "authorMap", normalizeMapByKey(mongo.authorMap())
        );
        Map<String, Object> postgresNormalized = Map.of(
                "publications", normalizePublications(postgres.publications()),
                "authorMap", normalizeMapByKey(postgres.authorMap())
        );
        return compareJson(mongoNormalized, postgresNormalized);
    }

    private String compareCitationsView(Optional<AdminScopusCitationsViewModel> mongo, Optional<AdminScopusCitationsViewModel> postgres) {
        if (mongo.isEmpty() || postgres.isEmpty()) {
            if (mongo.isEmpty() && postgres.isEmpty()) {
                return null;
            }
            return "one side returned empty citation view while the other returned data";
        }
        AdminScopusCitationsViewModel mongoView = mongo.get();
        AdminScopusCitationsViewModel postgresView = postgres.get();

        Map<String, Object> mongoNormalized = new LinkedHashMap<>();
        mongoNormalized.put("publication", mongoView.publication());
        mongoNormalized.put("publicationForum", mongoView.publicationForum());
        mongoNormalized.put("citations", normalizePublications(mongoView.citations()));
        mongoNormalized.put("authorMap", normalizeMapByKey(mongoView.authorMap()));
        mongoNormalized.put("forumMap", normalizeMapByKey(mongoView.forumMap()));

        Map<String, Object> postgresNormalized = new LinkedHashMap<>();
        postgresNormalized.put("publication", postgresView.publication());
        postgresNormalized.put("publicationForum", postgresView.publicationForum());
        postgresNormalized.put("citations", normalizePublications(postgresView.citations()));
        postgresNormalized.put("authorMap", normalizeMapByKey(postgresView.authorMap()));
        postgresNormalized.put("forumMap", normalizeMapByKey(postgresView.forumMap()));

        return compareJson(mongoNormalized, postgresNormalized);
    }

    private List<Map<String, Object>> normalizeWosRankings(List<WoSRanking> rankings) {
        if (rankings == null) {
            return List.of();
        }
        return rankings.stream()
                .sorted(Comparator.comparing(WoSRanking::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ranking -> {
                    Map<String, Object> score = Map.of(
                            "ais", new java.util.TreeMap<>(ranking.getScore() == null ? Map.of() : ranking.getScore().getAis()),
                            "ris", new java.util.TreeMap<>(ranking.getScore() == null ? Map.of() : ranking.getScore().getRis()),
                            "if", new java.util.TreeMap<>(ranking.getScore() == null ? Map.of() : ranking.getScore().getIF())
                    );

                    Map<String, Object> categoryIndex = new java.util.TreeMap<String, Object>();
                    (ranking.getWebOfScienceCategoryIndex() == null ? Map.<String, WoSRanking.Rank>of() : ranking.getWebOfScienceCategoryIndex())
                            .forEach((key, value) -> categoryIndex.put(key, Map.of(
                                    "qAis", new java.util.TreeMap<>(value.getQAis()),
                                    "qIF", new java.util.TreeMap<>(value.getQIF()),
                                    "qRis", new java.util.TreeMap<>(value.getQRis()),
                                    "rankAis", new java.util.TreeMap<>(value.getRankAis()),
                                    "rankIF", new java.util.TreeMap<>(value.getRankIF()),
                                    "rankRis", new java.util.TreeMap<>(value.getRankRis()),
                                    "quartileRankAis", new java.util.TreeMap<>(value.getQuartileRankAis()),
                                    "quartileRankIF", new java.util.TreeMap<>(value.getQuartileRankIF()),
                                    "quartileRankRis", new java.util.TreeMap<>(value.getQuartileRankRis())
                            )));

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", ranking.getId());
                    out.put("name", ranking.getName());
                    out.put("issn", ranking.getIssn());
                    out.put("eIssn", ranking.getEIssn());
                    out.put("alternativeIssns", ranking.getAlternativeIssns() == null
                            ? List.of()
                            : ranking.getAlternativeIssns().stream().sorted().toList());
                    out.put("alternativeNames", ranking.getAlternativeNames() == null
                            ? List.of()
                            : ranking.getAlternativeNames().stream().sorted().toList());
                    out.put("score", score);
                    out.put("categoryIndex", categoryIndex);
                    return out;
                })
                .toList();
    }

    private List<?> normalizePublications(List<?> publications) {
        if (publications == null) {
            return List.of();
        }
        return publications.stream()
                .sorted(Comparator.comparing(item -> {
                    if (item instanceof ro.uvt.pokedex.core.model.scopus.Publication publication) {
                        return publication.getId() == null ? "" : publication.getId();
                    }
                    return "";
                }))
                .toList();
    }

    private Map<String, Object> normalizeMapByKey(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String compareJson(Object mongo, Object postgres) {
        JsonNode left = objectMapper.valueToTree(mongo);
        JsonNode right = objectMapper.valueToTree(postgres);
        if (left.equals(right)) {
            return null;
        }
        return trim("mongo=" + toJson(left) + " | postgres=" + toJson(right));
    }

    private String runtimeMismatch(Throwable mongoError, Throwable postgresError) {
        if (mongoError == null && postgresError == null) {
            return null;
        }
        if (mongoError != null && postgresError != null) {
            String mongoClass = mongoError.getClass().getName();
            String postgresClass = postgresError.getClass().getName();
            String mongoMessage = trim(mongoError.getMessage());
            String postgresMessage = trim(postgresError.getMessage());
            if (mongoClass.equals(postgresClass)
                    && (mongoMessage == null ? postgresMessage == null : mongoMessage.equals(postgresMessage))) {
                return null;
            }
            return "mongoError=" + mongoClass + ":" + mongoMessage
                    + " postgresError=" + postgresClass + ":" + postgresMessage;
        }
        if (mongoError != null) {
            return "mongoError=" + mongoError.getClass().getName() + ":" + trim(mongoError.getMessage())
                    + " postgresError=none";
        }
        return "mongoError=none postgresError=" + postgresError.getClass().getName() + ":" + trim(postgresError.getMessage());
    }

    private ScenarioInput resolveScenarioInput() {
        String issn = resolveIssn();
        CategoryInput category = resolveCategory();
        String afid = resolveAfid();
        String forumQuery = firstToken(firstForumName());
        String affiliationQuery = firstToken(firstAffiliationName());
        String publicationTitleQuery = firstToken(firstPublicationTitle());
        String citationPublicationId = firstPublicationId();
        GroupReportRefreshIds refreshIds = resolveGroupReportRefreshIds();
        String groupReportRefreshGroupId = refreshIds.groupId();
        String groupReportRefreshReportId = refreshIds.reportId();

        log.info(
                "H22.6 dual-read scenario inputs: issn={}, categoryIndex={}, year={}, afid={}, forumQuery={}, affiliationQuery={}, publicationTitleQuery={}, citationPublicationId={}, groupReportRefreshGroupId={}, groupReportRefreshReportId={}",
                valueOrPlaceholder(issn),
                valueOrPlaceholder(category.categoryIndex()),
                category.year(),
                valueOrPlaceholder(afid),
                valueOrPlaceholder(forumQuery),
                valueOrPlaceholder(affiliationQuery),
                valueOrPlaceholder(publicationTitleQuery),
                valueOrPlaceholder(citationPublicationId),
                valueOrPlaceholder(groupReportRefreshGroupId),
                valueOrPlaceholder(groupReportRefreshReportId)
        );

        if (!hasText(forumQuery)) {
            log.warn("H22.6 dual-read forumQuery resolved to null/blank.");
        }
        if (!hasText(affiliationQuery)) {
            log.warn("H22.6 dual-read affiliationQuery resolved to null/blank.");
        }

        return new ScenarioInput(
                issn,
                category.categoryIndex(),
                category.year(),
                afid,
                forumQuery,
                affiliationQuery,
                publicationTitleQuery,
                citationPublicationId,
                groupReportRefreshGroupId,
                groupReportRefreshReportId
        );
    }

    private String resolveIssn() {
        Optional<WosRankingView> selected = findFirstDeterministic(
                new Query(),
                WosRankingView.class,
                Comparator.comparing((WosRankingView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getIssnNorm()))
                        .thenComparing(view -> sortKey(view.getEIssnNorm())),
                view -> hasText(view.getIssn())
                        || hasText(view.getEIssn())
                        || hasText(firstNonBlank(view.getAlternativeIssns()))
                        || hasText(view.getIssnNorm())
        );
        if (selected.isEmpty()) {
            return "00000000";
        }
        WosRankingView view = selected.get();
        if (hasText(view.getIssn())) {
            return view.getIssn();
        }
        if (hasText(view.getEIssn())) {
            return view.getEIssn();
        }
        String alternativeIssn = firstNonBlank(view.getAlternativeIssns());
        if (hasText(alternativeIssn)) {
            return alternativeIssn;
        }
        return hasText(view.getIssnNorm()) ? view.getIssnNorm() : "00000000";
    }

    private CategoryInput resolveCategory() {
        Query query = new Query();
        query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("metricType").is("AIS"));
        query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("quarter").is("Q1"));
        Optional<WosScoringView> selected = findFirstDeterministic(
                query,
                WosScoringView.class,
                Comparator.comparing((WosScoringView view) -> view.getYear() == null ? Integer.MAX_VALUE : view.getYear())
                        .thenComparing(view -> sortKey(view.getCategoryNameCanonical()))
                        .thenComparing(view -> view.getEditionNormalized() == null ? "" : view.getEditionNormalized().name())
                        .thenComparing(view -> sortKey(view.getId())),
                view -> hasText(view.getCategoryNameCanonical()) && view.getYear() != null && view.getEditionNormalized() != null
        );
        if (selected.isEmpty()) {
            return new CategoryInput("COMPUTER SCIENCE - SCIE", 2025);
        }
        WosScoringView view = selected.get();
        String categoryIndex = view.getCategoryNameCanonical() + " - " + view.getEditionNormalized();
        return new CategoryInput(categoryIndex, view.getYear());
    }

    private String resolveAfid() {
        Optional<ScholardexAuthorView> selected = findFirstDeterministic(
                new Query(),
                ScholardexAuthorView.class,
                Comparator.comparing((ScholardexAuthorView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getName())),
                view -> hasText(firstNonBlank(view.getAffiliationIds()))
        );
        if (selected.isEmpty()) {
            return "60000434";
        }
        return firstNonBlank(selected.get().getAffiliationIds());
    }

    private String firstForumName() {
        return findFirstDeterministic(
                new Query(),
                ScholardexForumView.class,
                Comparator.comparing((ScholardexForumView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getPublicationName())),
                view -> hasText(view.getPublicationName())
        ).map(ScholardexForumView::getPublicationName).orElse(null);
    }

    private String firstAffiliationName() {
        return findFirstDeterministic(
                new Query(),
                ScholardexAffiliationView.class,
                Comparator.comparing((ScholardexAffiliationView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getName())),
                view -> hasText(view.getName())
        ).map(ScholardexAffiliationView::getName).orElse(null);
    }

    private String firstPublicationTitle() {
        return findFirstDeterministic(
                new Query(),
                ScholardexPublicationView.class,
                Comparator.comparing((ScholardexPublicationView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getTitle())),
                view -> hasText(view.getTitle())
        ).map(ScholardexPublicationView::getTitle).orElse(null);
    }

    private String firstPublicationId() {
        return findFirstDeterministic(
                new Query(),
                ScholardexPublicationView.class,
                Comparator.comparing((ScholardexPublicationView view) -> sortKey(view.getId()))
                        .thenComparing(view -> sortKey(view.getTitle())),
                view -> hasText(view.getId())
        ).map(ScholardexPublicationView::getId).orElse("missing-publication-id");
    }

    private GroupReportRefreshIds resolveGroupReportRefreshIds() {
        String groupId = groupRepository.findAll().stream()
                .map(ro.uvt.pokedex.core.model.reporting.Group::getId)
                .filter(this::hasText)
                .sorted()
                .findFirst()
                .orElse(null);
        String reportId = individualReportRepository.findAll().stream()
                .map(ro.uvt.pokedex.core.model.reporting.IndividualReport::getId)
                .filter(this::hasText)
                .sorted()
                .findFirst()
                .orElse(null);
        return new GroupReportRefreshIds(groupId, reportId);
    }

    private <T> Optional<T> findFirstDeterministic(
            Query baseQuery,
            Class<T> entityType,
            Comparator<T> comparator,
            Predicate<T> predicate
    ) {
        Query query = baseQuery == null ? new Query() : baseQuery;
        query.with(Sort.by(Sort.Direction.ASC, "_id"));
        List<T> rows = mongoTemplate.find(query, entityType);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return rows.stream()
                .sorted(comparator)
                .filter(predicate)
                .findFirst();
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static String sortKey(String value) {
        return value == null ? "" : value;
    }

    private String firstToken(String input) {
        if (!hasText(input)) {
            return null;
        }
        return input.trim().split("\\s+")[0];
    }

    private boolean hasText(String input) {
        return input != null && !input.trim().isEmpty();
    }

    private String datasetNotReadyReason(DualReadScenario scenario) {
        String scenarioId = scenario.scenarioId();
        if (SCENARIO_SCOPUS_FORUM.equals(scenarioId)) {
            long count = mongoTemplate.count(new Query(), ScholardexForumView.class);
            if (count <= 0L) {
                return "dataset not ready: Mongo scholardex.forum_view is empty";
            }
            return null;
        }
        if (SCENARIO_SCOPUS_AFFILIATION.equals(scenarioId)) {
            long count = mongoTemplate.count(new Query(), ScholardexAffiliationView.class);
            if (count <= 0L) {
                return "dataset not ready: Mongo scholardex.affiliation_view is empty";
            }
            return null;
        }
        if (SCENARIO_SCOPUS_AUTHOR.equals(scenarioId)) {
            long count = mongoTemplate.count(new Query(), ScholardexAuthorView.class);
            if (count <= 0L) {
                return "dataset not ready: Mongo scholardex.author_view is empty";
            }
            return null;
        }
        if (SCENARIO_GROUP_REPORT_REFRESH.equals(scenarioId)) {
            GroupReportRefreshIds refreshIds = resolveGroupReportRefreshIds();
            String groupId = refreshIds.groupId();
            String reportId = refreshIds.reportId();
            if (!hasText(groupId) || !hasText(reportId)) {
                return "dataset not ready: no group/report ids available for admin.group.report.refresh";
            }
            if (!groupRepository.existsById(groupId) || !individualReportRepository.existsById(reportId)) {
                return "dataset not ready: report not found for configured ids groupId="
                        + valueOrPlaceholder(groupId) + ", reportId=" + valueOrPlaceholder(reportId);
            }
            return null;
        }
        return null;
    }

    private String resolveScenarioType(String scenarioId) {
        if (SCENARIO_GROUP_REPORT_REFRESH.equals(scenarioId)) {
            return SCENARIO_TYPE_PERF_ONLY;
        }
        return SCENARIO_TYPE_DUAL_PARITY;
    }

    private Double resolveP95ThresholdMs(String scenarioId) {
        if (SCENARIO_GROUP_REPORT_REFRESH.equals(scenarioId)) {
            return properties.getGroupReportRefreshP95ThresholdMs();
        }
        return null;
    }

    private String valueOrPlaceholder(String value) {
        return hasText(value) ? value : "<null>";
    }

    private TimedCall timedCall(Supplier<Object> supplier) {
        long startNanos = System.nanoTime();
        try {
            Object value = supplier.get();
            long elapsedNanos = System.nanoTime() - startNanos;
            return new TimedCall(value, null, elapsedNanos / 1_000_000d);
        } catch (Exception e) {
            long elapsedNanos = System.nanoTime() - startNanos;
            return new TimedCall(null, e, elapsedNanos / 1_000_000d);
        }
    }

    private <T> T requirePostgres(ObjectProvider<T> provider, String beanName) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException("Dual-read gate requires " + beanName + " bean.");
        }
        return bean;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() <= 2000) {
            return normalized;
        }
        return normalized.substring(0, 2000);
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return "none";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String top = error.getClass().getName() + ":" + trim(error.getMessage());
        if (root == error) {
            return top;
        }
        return top + " | root=" + root.getClass().getName() + ":" + trim(root.getMessage());
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object value) {
        return (T) value;
    }

    private record TimedCall(
            Object value,
            Exception error,
            double elapsedMs
    ) {
    }

    private record CategoryInput(
            String categoryIndex,
            int year
    ) {
    }

    private record GroupReportRefreshIds(
            String groupId,
            String reportId
    ) {
    }

    private record ScenarioInput(
            String issn,
            String categoryIndex,
            int year,
            String afid,
            String forumQuery,
            String affiliationQuery,
            String publicationTitleQuery,
            String citationPublicationId,
            String groupReportRefreshGroupId,
            String groupReportRefreshReportId
    ) {
    }

    private record DualReadScenario(
            String scenarioId,
            String scenarioType,
            Supplier<Object> mongoSupplier,
            Supplier<Object> postgresSupplier,
            ScenarioComparator comparator,
            Double p95ThresholdMs
    ) {
    }

    @FunctionalInterface
    private interface ScenarioComparator {
        String compare(Object mongo, Object postgres);
    }
}
