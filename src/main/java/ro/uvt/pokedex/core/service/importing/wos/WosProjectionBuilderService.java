package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class WosProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(WosProjectionBuilderService.class);

    private final WosJournalIdentityRepository identityRepository;
    private final MongoTemplate mongoTemplate;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;
    private final WosOptimizationProperties optimizationProperties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public WosProjectionBuilderService(
            WosJournalIdentityRepository identityRepository,
            MongoTemplate mongoTemplate,
            WosIndexMaintenanceService wosIndexMaintenanceService,
            WosOptimizationProperties optimizationProperties,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.identityRepository = identityRepository;
        this.mongoTemplate = mongoTemplate;
        this.wosIndexMaintenanceService = wosIndexMaintenanceService;
        this.optimizationProperties = optimizationProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ImportProcessingResult rebuildWosProjections() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            if (optimizationProperties.isPreflightIndexesEnabled()) {
                wosIndexMaintenanceService.ensureWosIndexesForStage("wos-projection-builder");
            }
            int chunkSize = Math.max(1, optimizationProperties.getProjectionWriteChunkSize());
            log.info("WoS projection rebuild started: buildVersion={}, chunkSize={}", buildVersion, chunkSize);
            long totalStartedAtNanos = System.nanoTime();

            // --- load all MongoDB facts upfront (full fields for PG insertion) ---
            long loadMetricNs = System.nanoTime();
            List<WosMetricFact> allMetricFacts = mongoTemplate.find(
                    new Query().with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("metricType"))),
                    WosMetricFact.class
            );
            long loadMetricMs = nanosToMillis(System.nanoTime() - loadMetricNs);

            long loadCategoryNs = System.nanoTime();
            List<WosCategoryFact> allCategoryFacts = mongoTemplate.find(
                    new Query().with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("metricType"))),
                    WosCategoryFact.class
            );
            long loadCategoryMs = nanosToMillis(System.nanoTime() - loadCategoryNs);

            // Build lookup maps for derivation
            Map<String, List<WosMetricFact>> metricByJournal = new HashMap<>();
            for (WosMetricFact fact : allMetricFacts) {
                if (fact.getJournalId() != null) {
                    metricByJournal.computeIfAbsent(fact.getJournalId(), k -> new ArrayList<>()).add(fact);
                }
            }
            Map<String, List<WosCategoryFact>> categoryByJournal = new HashMap<>();
            for (WosCategoryFact fact : allCategoryFacts) {
                if (fact.getJournalId() != null) {
                    categoryByJournal.computeIfAbsent(fact.getJournalId(), k -> new ArrayList<>()).add(fact);
                }
            }
            Map<ScoreKey, WosMetricFact> scoreByKey = new HashMap<>();
            for (WosMetricFact fact : allMetricFacts) {
                if (fact.getJournalId() != null && fact.getYear() != null && fact.getMetricType() != null) {
                    scoreByKey.put(new ScoreKey(fact.getJournalId(), fact.getYear(), fact.getMetricType()), fact);
                }
            }

            log.info("WoS projection facts loaded: metricFacts={} loadMs={}, categoryFacts={} loadMs={}",
                    allMetricFacts.size(), loadMetricMs, allCategoryFacts.size(), loadCategoryMs);

            // --- assemble ranking views (paginate over journal identities) ---
            long assembleRankingNs = System.nanoTime();
            List<WosRankingView> allRankingViews = new ArrayList<>();
            int page = 0;
            int rankingChunkNo = 0;
            while (true) {
                long chunkNs = System.nanoTime();
                Page<WosJournalIdentity> identityPage = identityRepository.findAll(
                        PageRequest.of(page, chunkSize, Sort.by(Sort.Order.asc("id")))
                );
                List<WosJournalIdentity> identities = identityPage.getContent();
                if (identities.isEmpty()) {
                    break;
                }
                for (WosJournalIdentity identity : identities) {
                    result.markProcessed();
                    allRankingViews.add(toRankingView(
                            identity,
                            metricByJournal.getOrDefault(identity.getId(), List.of()),
                            categoryByJournal.getOrDefault(identity.getId(), List.of()),
                            buildVersion,
                            buildAt
                    ));
                    result.markImported();
                }
                rankingChunkNo++;
                log.info("WoS projection ranking chunk {} [page={}]: rows={} cumulative={} chunkMs={}",
                        rankingChunkNo, page, identities.size(), allRankingViews.size(), nanosToMillis(System.nanoTime() - chunkNs));
                if (!identityPage.hasNext()) {
                    break;
                }
                page++;
            }
            long assembleRankingMs = nanosToMillis(System.nanoTime() - assembleRankingNs);

            // --- assemble scoring views (iterate over all category facts) ---
            long assembleScoringNs = System.nanoTime();
            List<WosScoringView> allScoringViews = new ArrayList<>(allCategoryFacts.size());
            for (WosCategoryFact categoryFact : allCategoryFacts) {
                result.markProcessed();
                WosMetricFact score = scoreByKey.get(
                        new ScoreKey(categoryFact.getJournalId(), categoryFact.getYear(), categoryFact.getMetricType())
                );
                allScoringViews.add(toScoringView(categoryFact, score, buildVersion, buildAt));
                result.markImported();
            }
            long assembleScoringMs = nanosToMillis(System.nanoTime() - assembleScoringNs);

            log.info("WoS projection assembly complete: rankingRows={} scoringRows={} assembleRankingMs={} assembleScoringMs={}",
                    allRankingViews.size(), allScoringViews.size(), assembleRankingMs, assembleScoringMs);

            // --- write to PostgreSQL atomically ---
            long writePgNs = System.nanoTime();
            List<WosRankingView> rankingViewsForWrite = allRankingViews;
            List<WosScoringView> scoringViewsForWrite = allScoringViews;
            List<WosMetricFact> metricFactsForWrite = allMetricFacts;
            List<WosCategoryFact> categoryFactsForWrite = allCategoryFacts;
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("""
                        TRUNCATE TABLE
                            reporting_read.wos_scoring_view,
                            reporting_read.wos_category_fact,
                            reporting_read.wos_metric_fact,
                            reporting_read.wos_ranking_view
                        """);
                insertWosRankingRows(rankingViewsForWrite);
                insertWosMetricRows(metricFactsForWrite);
                insertWosCategoryRows(categoryFactsForWrite);
                insertWosScoringRows(scoringViewsForWrite);
                rebuildCategoryMetricAgg();
            });
            long writePgMs = nanosToMillis(System.nanoTime() - writePgNs);

            long totalMs = nanosToMillis(System.nanoTime() - totalStartedAtNanos);
            log.info("WoS projection rebuild complete: buildVersion={} rankingRows={} scoringRows={} metricRows={} categoryRows={} timingsMs[loadMetric={} loadCategory={} assembleRanking={} assembleScoring={} writePg={} total={}]",
                    buildVersion, allRankingViews.size(), allScoringViews.size(),
                    allMetricFacts.size(), allCategoryFacts.size(),
                    loadMetricMs, loadCategoryMs, assembleRankingMs, assembleScoringMs, writePgMs, totalMs);

        } catch (Exception e) {
            result.markError("projection-rebuild-error=" + e.getMessage());
            log.error("WoS projection rebuild failed", e);
        }
        return result;
    }

    /**
     * Full recompute of {@code wos_category_metric_agg} from the just-written journal-year facts — the source
     * for the signed-in categories list (avg/top IF &amp; AIS), per-category trend charts and row sparklines.
     * Joins {@code wos_category_fact} (category membership per metric) to {@code wos_metric_fact} (numeric value)
     * on journal+year+metric and groups by category/edition/year/metric. Cheap (a few tens of thousands of rows);
     * runs inside the projection write transaction so it is atomic with the facts it derives from.
     */
    void rebuildCategoryMetricAgg() {
        jdbcTemplate.execute("TRUNCATE TABLE reporting_read.wos_category_metric_agg");
        jdbcTemplate.execute("""
                INSERT INTO reporting_read.wos_category_metric_agg
                    (category_name_canonical, edition_normalized, year, metric_type, journal_count, avg_value, max_value, median_value)
                SELECT cf.category_name_canonical, cf.edition_normalized, cf.year, mf.metric_type,
                       COUNT(DISTINCT mf.journal_id), AVG(mf.value), MAX(mf.value),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY mf.value)
                FROM reporting_read.wos_category_fact cf
                JOIN reporting_read.wos_metric_fact mf
                  ON cf.journal_id = mf.journal_id AND cf.year = mf.year AND cf.metric_type = mf.metric_type
                WHERE cf.category_name_canonical IS NOT NULL AND cf.category_name_canonical <> ''
                  AND mf.value IS NOT NULL
                GROUP BY cf.category_name_canonical, cf.edition_normalized, cf.year, mf.metric_type
                """);
    }

    public ImportProcessingResult rebuildWosProjectionsForJournals(Set<String> affectedJournalIds) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        if (affectedJournalIds == null || affectedJournalIds.isEmpty()) {
            return result;
        }

        List<String> journalIds = affectedJournalIds.stream()
                .filter(journalId -> journalId != null && !journalId.isBlank())
                .sorted()
                .toList();
        if (journalIds.isEmpty()) {
            return result;
        }

        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            if (optimizationProperties.isPreflightIndexesEnabled()) {
                wosIndexMaintenanceService.ensureWosIndexesForStage("wos-projection-builder");
            }

            List<WosMetricFact> scopedMetricFacts = mongoTemplate.find(
                    new Query(Criteria.where("journalId").in(journalIds))
                            .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("metricType"))),
                    WosMetricFact.class
            );
            List<WosCategoryFact> scopedCategoryFacts = mongoTemplate.find(
                    new Query(Criteria.where("journalId").in(journalIds))
                            .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("metricType"))),
                    WosCategoryFact.class
            );

            Map<String, List<WosMetricFact>> metricByJournal = new HashMap<>();
            for (WosMetricFact fact : scopedMetricFacts) {
                if (fact.getJournalId() != null) {
                    metricByJournal.computeIfAbsent(fact.getJournalId(), ignored -> new ArrayList<>()).add(fact);
                }
            }
            Map<String, List<WosCategoryFact>> categoryByJournal = new HashMap<>();
            for (WosCategoryFact fact : scopedCategoryFacts) {
                if (fact.getJournalId() != null) {
                    categoryByJournal.computeIfAbsent(fact.getJournalId(), ignored -> new ArrayList<>()).add(fact);
                }
            }
            Map<ScoreKey, WosMetricFact> scoreByKey = new HashMap<>();
            for (WosMetricFact fact : scopedMetricFacts) {
                if (fact.getJournalId() != null && fact.getYear() != null && fact.getMetricType() != null) {
                    scoreByKey.put(new ScoreKey(fact.getJournalId(), fact.getYear(), fact.getMetricType()), fact);
                }
            }

            List<WosJournalIdentity> identities = new ArrayList<>();
            identityRepository.findAllById(journalIds).forEach(identities::add);
            identities.sort(Comparator.comparing(WosJournalIdentity::getId, Comparator.nullsFirst(String::compareTo)));

            List<WosRankingView> rankingViews = new ArrayList<>();
            for (WosJournalIdentity identity : identities) {
                result.markProcessed();
                rankingViews.add(toRankingView(
                        identity,
                        metricByJournal.getOrDefault(identity.getId(), List.of()),
                        categoryByJournal.getOrDefault(identity.getId(), List.of()),
                        buildVersion,
                        buildAt
                ));
                result.markImported();
            }

            List<WosScoringView> scoringViews = new ArrayList<>(scopedCategoryFacts.size());
            for (WosCategoryFact categoryFact : scopedCategoryFacts) {
                result.markProcessed();
                WosMetricFact score = scoreByKey.get(
                        new ScoreKey(categoryFact.getJournalId(), categoryFact.getYear(), categoryFact.getMetricType())
                );
                scoringViews.add(toScoringView(categoryFact, score, buildVersion, buildAt));
                result.markImported();
            }

            transactionTemplate.executeWithoutResult(status -> {
                deleteAllProjectionRowsForJournals(journalIds);
                insertWosRankingRows(rankingViews);
                insertWosMetricRows(scopedMetricFacts);
                insertWosCategoryRows(scopedCategoryFacts);
                insertWosScoringRows(scoringViews);
            });
        } catch (Exception e) {
            result.markError("projection-rebuild-error=" + e.getMessage());
            log.error("WoS scoped projection rebuild failed: journalCount={}", journalIds.size(), e);
        }
        return result;
    }

    public ImportProcessingResult rebuildWosProjectionsForScope(
            Set<String> affectedJournalIds,
            List<WosMetricFact> lineageMetricFacts,
            List<WosCategoryFact> lineageCategoryFacts
    ) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<String> journalIds = normalizeJournalIds(affectedJournalIds);
        if (journalIds.isEmpty()) {
            return result;
        }

        List<MetricSliceKey> metricSliceKeys = lineageMetricFacts == null ? List.of() : lineageMetricFacts.stream()
                .filter(fact -> fact.getJournalId() != null && fact.getYear() != null && fact.getMetricType() != null)
                .map(fact -> new MetricSliceKey(fact.getJournalId(), fact.getYear(), fact.getMetricType()))
                .distinct()
                .toList();
        List<CategorySliceKey> categorySliceKeys = lineageCategoryFacts == null ? List.of() : lineageCategoryFacts.stream()
                .filter(fact -> fact.getJournalId() != null
                        && fact.getYear() != null
                        && fact.getCategoryNameCanonical() != null
                        && fact.getEditionNormalized() != null
                        && fact.getMetricType() != null)
                .map(fact -> new CategorySliceKey(
                        fact.getJournalId(),
                        fact.getYear(),
                        fact.getCategoryNameCanonical(),
                        fact.getEditionNormalized(),
                        fact.getMetricType()
                ))
                .distinct()
                .toList();

        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            if (optimizationProperties.isPreflightIndexesEnabled()) {
                wosIndexMaintenanceService.ensureWosIndexesForStage("wos-projection-builder");
            }

            List<WosMetricFact> scopedMetricFacts = loadMetricFactsForSlices(metricSliceKeys);
            List<WosCategoryFact> scopedCategoryFacts = loadCategoryFactsForSlices(categorySliceKeys);

            Map<ScoreKey, WosMetricFact> scoreByKey = new HashMap<>();
            for (WosMetricFact fact : scopedMetricFacts) {
                if (fact.getJournalId() != null && fact.getYear() != null && fact.getMetricType() != null) {
                    scoreByKey.put(new ScoreKey(fact.getJournalId(), fact.getYear(), fact.getMetricType()), fact);
                }
            }

            List<WosJournalIdentity> identities = new ArrayList<>();
            identityRepository.findAllById(journalIds).forEach(identities::add);
            identities.sort(Comparator.comparing(WosJournalIdentity::getId, Comparator.nullsFirst(String::compareTo)));

            List<WosRankingView> rankingViews = new ArrayList<>();
            for (WosJournalIdentity identity : identities) {
                result.markProcessed();
                List<WosMetricFact> journalMetricFacts = mongoTemplate.find(
                        new Query(Criteria.where("journalId").is(identity.getId()))
                                .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("metricType"))),
                        WosMetricFact.class
                );
                List<WosCategoryFact> journalCategoryFacts = mongoTemplate.find(
                        new Query(Criteria.where("journalId").is(identity.getId()))
                                .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("metricType"))),
                        WosCategoryFact.class
                );
                rankingViews.add(toRankingView(identity, journalMetricFacts, journalCategoryFacts, buildVersion, buildAt));
                result.markImported();
            }

            List<WosScoringView> scoringViews = new ArrayList<>(scopedCategoryFacts.size());
            for (WosCategoryFact categoryFact : scopedCategoryFacts) {
                result.markProcessed();
                WosMetricFact score = scoreByKey.get(
                        new ScoreKey(categoryFact.getJournalId(), categoryFact.getYear(), categoryFact.getMetricType())
                );
                scoringViews.add(toScoringView(categoryFact, score, buildVersion, buildAt));
                result.markImported();
            }

            transactionTemplate.executeWithoutResult(status -> {
                deleteMetricFactRows(metricSliceKeys);
                deleteCategoryFactRows(categorySliceKeys);
                deleteScoringRows(categorySliceKeys);
                upsertWosRankingRows(rankingViews);
                insertWosMetricRows(scopedMetricFacts);
                insertWosCategoryRows(scopedCategoryFacts);
                insertWosScoringRows(scoringViews);
            });
        } catch (Exception e) {
            result.markError("projection-rebuild-error=" + e.getMessage());
            log.error(
                    "WoS slice-scoped projection rebuild failed: journalCount={}, metricSlices={}, categorySlices={}",
                    journalIds.size(),
                    metricSliceKeys.size(),
                    categorySliceKeys.size(),
                    e
            );
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Derivation helpers (unchanged)
    // -------------------------------------------------------------------------

    private WosRankingView toRankingView(
            WosJournalIdentity identity,
            List<WosMetricFact> journalScoreFacts,
            List<WosCategoryFact> journalCategoryFacts,
            String buildVersion,
            Instant buildAt
    ) {
        WosRankingView view = new WosRankingView();
        view.setId(identity.getId());
        view.setName(identity.getTitle());
        view.setIssn(identity.getPrimaryIssn());
        view.setEIssn(identity.getEIssn());
        view.setAlternativeIssns(identity.getAliasIssns() == null ? List.of() : new ArrayList<>(identity.getAliasIssns()));
        view.setAlternativeNames(identity.getAlternativeNames() == null ? List.of() : new ArrayList<>(identity.getAlternativeNames()));
        view.setNameNorm(normalizeText(identity.getTitle()));
        view.setIssnNorm(normalizeIssn(identity.getPrimaryIssn()));
        view.setEIssnNorm(normalizeIssn(identity.getEIssn()));
        view.setAlternativeIssnsNorm(normalizeIssnList(identity.getAliasIssns()));
        view.setLatestAisYear(maxYearWithValue(journalScoreFacts, MetricType.AIS));
        view.setLatestRisYear(maxYearWithValue(journalScoreFacts, MetricType.RIS));
        view.setLatestEditionNormalized(resolveLatestEdition(journalCategoryFacts));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        return view;
    }

    private WosScoringView toScoringView(
            WosCategoryFact categoryFact,
            WosMetricFact scoreFact,
            String buildVersion,
            Instant buildAt
    ) {
        WosScoringView view = new WosScoringView();
        view.setId(scoringViewId(
                categoryFact.getJournalId(),
                categoryFact.getYear(),
                categoryFact.getCategoryNameCanonical(),
                categoryFact.getEditionNormalized(),
                categoryFact.getMetricType()
        ));
        view.setJournalId(categoryFact.getJournalId());
        view.setYear(categoryFact.getYear());
        view.setCategoryNameCanonical(categoryFact.getCategoryNameCanonical());
        view.setEditionNormalized(categoryFact.getEditionNormalized());
        view.setMetricType(categoryFact.getMetricType());
        view.setQuarter(categoryFact.getQuarter());
        view.setQuartileRank(categoryFact.getQuartileRank());
        view.setRank(categoryFact.getRank());
        view.setValue(scoreFact == null ? null : scoreFact.getValue());
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        return view;
    }

    private Integer maxYearWithValue(List<WosMetricFact> facts, MetricType metricType) {
        return facts.stream()
                .filter(f -> f.getMetricType() == metricType)
                .filter(f -> f.getValue() != null)
                .map(WosMetricFact::getYear)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private EditionNormalized resolveLatestEdition(List<WosCategoryFact> facts) {
        WosCategoryFact latestAis = latestCategoryFact(facts, MetricType.AIS);
        if (latestAis != null && latestAis.getEditionNormalized() != null) {
            return latestAis.getEditionNormalized();
        }
        WosCategoryFact latestRis = latestCategoryFact(facts, MetricType.RIS);
        if (latestRis != null && latestRis.getEditionNormalized() != null) {
            return latestRis.getEditionNormalized();
        }
        return EditionNormalized.UNKNOWN;
    }

    private WosCategoryFact latestCategoryFact(List<WosCategoryFact> facts, MetricType metricType) {
        return facts.stream()
                .filter(f -> f.getMetricType() == metricType)
                .max(Comparator
                        .comparing(WosCategoryFact::getYear, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(WosCategoryFact::getSourceVersion, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WosCategoryFact::getSourceRowItem, Comparator.nullsFirst(String::compareTo)))
                .orElse(null);
    }

    private String scoringViewId(
            String journalId,
            Integer year,
            String category,
            EditionNormalized editionNormalized,
            MetricType metricType
    ) {
        String raw = String.join("|",
                journalId == null ? "" : journalId,
                year == null ? "" : year.toString(),
                category == null ? "" : category,
                editionNormalized == null ? "" : editionNormalized.name(),
                metricType == null ? "" : metricType.name()
        );
        return sha256Hex(raw);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String normalizeText(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeIssn(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "");
        return normalized.isBlank() ? null : normalized;
    }

    private List<String> normalizeIssnList(List<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String token : rawTokens) {
            String value = normalizeIssn(token);
            if (value != null) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private record ScoreKey(String journalId, Integer year, MetricType metricType) {
    }

    private record MetricSliceKey(String journalId, Integer year, MetricType metricType) {
    }

    private record CategorySliceKey(
            String journalId,
            Integer year,
            String categoryNameCanonical,
            EditionNormalized editionNormalized,
            MetricType metricType
    ) {
    }

    // -------------------------------------------------------------------------
    // PostgreSQL write methods
    // -------------------------------------------------------------------------

    private void insertWosRankingRows(List<WosRankingView> rows) {
        String sql = """
                INSERT INTO reporting_read.wos_ranking_view (
                    journal_id, name, issn, e_issn, alternative_issns, alternative_names,
                    name_norm, issn_norm, e_issn_norm, alternative_issns_norm,
                    latest_ais_year, latest_ris_year, latest_edition_normalized,
                    build_version, build_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeRankingRows(rows, sql);
    }

    private void upsertWosRankingRows(List<WosRankingView> rows) {
        String sql = """
                INSERT INTO reporting_read.wos_ranking_view (
                    journal_id, name, issn, e_issn, alternative_issns, alternative_names,
                    name_norm, issn_norm, e_issn_norm, alternative_issns_norm,
                    latest_ais_year, latest_ris_year, latest_edition_normalized,
                    build_version, build_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (journal_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    issn = EXCLUDED.issn,
                    e_issn = EXCLUDED.e_issn,
                    alternative_issns = EXCLUDED.alternative_issns,
                    alternative_names = EXCLUDED.alternative_names,
                    name_norm = EXCLUDED.name_norm,
                    issn_norm = EXCLUDED.issn_norm,
                    e_issn_norm = EXCLUDED.e_issn_norm,
                    alternative_issns_norm = EXCLUDED.alternative_issns_norm,
                    latest_ais_year = EXCLUDED.latest_ais_year,
                    latest_ris_year = EXCLUDED.latest_ris_year,
                    latest_edition_normalized = EXCLUDED.latest_edition_normalized,
                    build_version = EXCLUDED.build_version,
                    build_at = EXCLUDED.build_at,
                    updated_at = EXCLUDED.updated_at
                """;
        writeRankingRows(rows, sql);
    }

    private void writeRankingRows(List<WosRankingView> rows, String sql) {
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
                ps.setString(14, row.getBuildVersion());
                setInstant(ps, 15, row.getBuildAt());
                setInstant(ps, 16, row.getUpdatedAt());
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        }));
    }

    private void deleteProjectionRowsForJournals(List<String> journalIds) {
        batchDeleteByJournalId("DELETE FROM reporting_read.wos_ranking_view WHERE journal_id = ?", journalIds);
    }

    private void deleteAllProjectionRowsForJournals(List<String> journalIds) {
        batchDeleteByJournalId("DELETE FROM reporting_read.wos_scoring_view WHERE journal_id = ?", journalIds);
        batchDeleteByJournalId("DELETE FROM reporting_read.wos_category_fact WHERE journal_id = ?", journalIds);
        batchDeleteByJournalId("DELETE FROM reporting_read.wos_metric_fact WHERE journal_id = ?", journalIds);
        batchDeleteByJournalId("DELETE FROM reporting_read.wos_ranking_view WHERE journal_id = ?", journalIds);
    }

    private void batchDeleteByJournalId(String sql, List<String> journalIds) {
        jdbcTemplate.batchUpdate(
                sql,
                journalIds,
                Math.max(1, optimizationProperties.getProjectionWriteChunkSize()),
                (ps, journalId) -> ps.setString(1, journalId)
        );
    }

    private void deleteMetricFactRows(List<MetricSliceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "DELETE FROM reporting_read.wos_metric_fact WHERE journal_id = ? AND year = ? AND metric_type = ?",
                keys,
                Math.max(1, optimizationProperties.getProjectionWriteChunkSize()),
                (ps, key) -> {
                    ps.setString(1, key.journalId());
                    setInteger(ps, 2, key.year());
                    setEnum(ps, 3, key.metricType() == null ? null : key.metricType().name());
                }
        );
    }

    private void deleteCategoryFactRows(List<CategorySliceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "DELETE FROM reporting_read.wos_category_fact WHERE journal_id = ? AND year = ? AND category_name_canonical = ? AND edition_normalized = ? AND metric_type = ?",
                keys,
                Math.max(1, optimizationProperties.getProjectionWriteChunkSize()),
                (ps, key) -> {
                    ps.setString(1, key.journalId());
                    setInteger(ps, 2, key.year());
                    ps.setString(3, key.categoryNameCanonical());
                    setEnum(ps, 4, key.editionNormalized() == null ? null : key.editionNormalized().name());
                    setEnum(ps, 5, key.metricType() == null ? null : key.metricType().name());
                }
        );
    }

    private void deleteScoringRows(List<CategorySliceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "DELETE FROM reporting_read.wos_scoring_view WHERE journal_id = ? AND year = ? AND category_name_canonical = ? AND edition_normalized = ? AND metric_type = ?",
                keys,
                Math.max(1, optimizationProperties.getProjectionWriteChunkSize()),
                (ps, key) -> {
                    ps.setString(1, key.journalId());
                    setInteger(ps, 2, key.year());
                    ps.setString(3, key.categoryNameCanonical());
                    setEnum(ps, 4, key.editionNormalized() == null ? null : key.editionNormalized().name());
                    setEnum(ps, 5, key.metricType() == null ? null : key.metricType().name());
                }
        );
    }

    private List<String> normalizeJournalIds(Set<String> affectedJournalIds) {
        if (affectedJournalIds == null || affectedJournalIds.isEmpty()) {
            return List.of();
        }
        return affectedJournalIds.stream()
                .filter(journalId -> journalId != null && !journalId.isBlank())
                .sorted()
                .toList();
    }

    private List<WosMetricFact> loadMetricFactsForSlices(List<MetricSliceKey> keys) {
        List<WosMetricFact> rows = new ArrayList<>();
        for (MetricSliceKey key : keys) {
            rows.addAll(mongoTemplate.find(
                    new Query(Criteria.where("journalId").is(key.journalId())
                            .and("year").is(key.year())
                            .and("metricType").is(key.metricType()))
                            .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("metricType"))),
                    WosMetricFact.class
            ));
        }
        return rows;
    }

    private List<WosCategoryFact> loadCategoryFactsForSlices(List<CategorySliceKey> keys) {
        List<WosCategoryFact> rows = new ArrayList<>();
        for (CategorySliceKey key : keys) {
            rows.addAll(mongoTemplate.find(
                    new Query(Criteria.where("journalId").is(key.journalId())
                            .and("year").is(key.year())
                            .and("categoryNameCanonical").is(key.categoryNameCanonical())
                            .and("editionNormalized").is(key.editionNormalized())
                            .and("metricType").is(key.metricType()))
                            .with(Sort.by(Sort.Order.asc("journalId"), Sort.Order.asc("year"), Sort.Order.asc("categoryNameCanonical"), Sort.Order.asc("metricType"))),
                    WosCategoryFact.class
            ));
        }
        return rows;
    }

    private void insertWosMetricRows(List<WosMetricFact> rows) {
        String sql = """
                INSERT INTO reporting_read.wos_metric_fact (
                    id, journal_id, year, metric_type, value,
                    source_type, source_event_id, source_file, source_version, source_row_item, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
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

    private void insertWosCategoryRows(List<WosCategoryFact> rows) {
        String sql = """
                INSERT INTO reporting_read.wos_category_fact (
                    id, journal_id, year, category_name_canonical,
                    edition_raw, edition_normalized, metric_type,
                    quarter, quartile_rank, rank,
                    source_type, source_event_id, source_file, source_version, source_row_item, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
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

    private void insertWosScoringRows(List<WosScoringView> rows) {
        String sql = """
                INSERT INTO reporting_read.wos_scoring_view (
                    id, journal_id, year, category_name_canonical,
                    edition_normalized, metric_type, value, quarter,
                    quartile_rank, rank, build_version, build_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
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
                ps.setString(11, row.getBuildVersion());
                setInstant(ps, 12, row.getBuildAt());
                setInstant(ps, 13, row.getUpdatedAt());
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
        int batchSize = Math.max(1, optimizationProperties.getProjectionWriteChunkSize());
        for (int i = 0; i < rows.size(); i += batchSize) {
            int to = Math.min(rows.size(), i + batchSize);
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

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
