package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
import ro.uvt.pokedex.core.service.application.WosIndexMaintenanceService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

@Service
public class WosProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(WosProjectionBuilderService.class);

    private final WosJournalIdentityRepository identityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosRankingViewRepository rankingViewRepository;
    private final WosScoringViewRepository scoringViewRepository;
    private final MongoTemplate mongoTemplate;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;
    private final WosOptimizationProperties optimizationProperties;

    public WosProjectionBuilderService(
            WosJournalIdentityRepository identityRepository,
            WosMetricFactRepository metricFactRepository,
            WosCategoryFactRepository categoryFactRepository,
            WosRankingViewRepository rankingViewRepository,
            WosScoringViewRepository scoringViewRepository,
            MongoTemplate mongoTemplate,
            WosIndexMaintenanceService wosIndexMaintenanceService,
            WosOptimizationProperties optimizationProperties
    ) {
        this.identityRepository = identityRepository;
        this.metricFactRepository = metricFactRepository;
        this.categoryFactRepository = categoryFactRepository;
        this.rankingViewRepository = rankingViewRepository;
        this.scoringViewRepository = scoringViewRepository;
        this.mongoTemplate = mongoTemplate;
        this.wosIndexMaintenanceService = wosIndexMaintenanceService;
        this.optimizationProperties = optimizationProperties;
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
            long loadIdentityNs = 0L;
            long loadMetricNs = 0L;
            long loadCategoryNs = 0L;
            long assembleRankingNs = 0L;
            long assembleScoringNs = 0L;
            long writeRankingNs = 0L;
            long writeScoringNs = 0L;

            rankingViewRepository.deleteAll();
            scoringViewRepository.deleteAll();

            int page = 0;
            long rankingRows = 0L;
            int rankingChunkNo = 0;
            while (true) {
                long rankingChunkStartedAtNanos = System.nanoTime();
                long loadIdentityStartedAtNanos = System.nanoTime();
                Page<WosJournalIdentity> identityPage = identityRepository.findAll(PageRequest.of(page, chunkSize, Sort.by(Sort.Order.asc("id"))));
                long loadIdentityFinishedAtNanos = System.nanoTime();
                loadIdentityNs += System.nanoTime() - loadIdentityStartedAtNanos;
                List<WosJournalIdentity> identities = identityPage.getContent();
                if (identities.isEmpty()) {
                    break;
                }

                Set<String> journalIds = identities.stream()
                        .map(WosJournalIdentity::getId)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

                long loadMetricStartedAtNanos = System.nanoTime();
                List<WosMetricFact> metricFacts = findMetricFactsByJournalIds(journalIds);
                long loadMetricFinishedAtNanos = System.nanoTime();
                loadMetricNs += System.nanoTime() - loadMetricStartedAtNanos;
                long loadCategoryStartedAtNanos = System.nanoTime();
                List<WosCategoryFact> categoryFacts = findCategoryFactsByJournalIds(journalIds);
                long loadCategoryFinishedAtNanos = System.nanoTime();
                loadCategoryNs += System.nanoTime() - loadCategoryStartedAtNanos;

                Map<String, List<WosMetricFact>> metricByJournal = new HashMap<>();
                for (WosMetricFact fact : metricFacts) {
                    metricByJournal.computeIfAbsent(fact.getJournalId(), key -> new ArrayList<>()).add(fact);
                }
                Map<String, List<WosCategoryFact>> categoryByJournal = new HashMap<>();
                for (WosCategoryFact fact : categoryFacts) {
                    categoryByJournal.computeIfAbsent(fact.getJournalId(), key -> new ArrayList<>()).add(fact);
                }

                long assembleRankingStartedAtNanos = System.nanoTime();
                List<WosRankingView> rankingViews = new ArrayList<>(identities.size());
                for (WosJournalIdentity identity : identities) {
                    result.markProcessed();
                    rankingViews.add(toRankingView(
                            identity,
                            metricByJournal.getOrDefault(identity.getId(), List.of()),
                            categoryByJournal.getOrDefault(identity.getId(), List.of()),
                            buildVersion,
                            buildAt
                    ));
                }
                long assembleRankingFinishedAtNanos = System.nanoTime();
                assembleRankingNs += assembleRankingFinishedAtNanos - assembleRankingStartedAtNanos;

                long writeRankingStartedAtNanos = System.nanoTime();
                rankingViewRepository.saveAll(rankingViews);
                long writeRankingFinishedAtNanos = System.nanoTime();
                writeRankingNs += writeRankingFinishedAtNanos - writeRankingStartedAtNanos;
                rankingRows += rankingViews.size();
                for (int i = 0; i < rankingViews.size(); i++) {
                    result.markImported();
                }
                rankingChunkNo++;
                long rankingChunkTotalMs = nanosToMillis(System.nanoTime() - rankingChunkStartedAtNanos);
                String rankingChunkHealth = rankingChunkTotalMs >= optimizationProperties.getSlowChunkThresholdMs() ? "SLOW" : "OK";
                log.info("WoS projection ranking chunk {} complete [page={}]: rows={} cumulativeRows={} health={} timingsMs[loadIdentity={}, loadMetric={}, loadCategory={}, assembleRanking={}, writeRanking={}, total={}]",
                        rankingChunkNo,
                        page,
                        rankingViews.size(),
                        rankingRows,
                        rankingChunkHealth,
                        nanosToMillis(loadIdentityFinishedAtNanos - loadIdentityStartedAtNanos),
                        nanosToMillis(loadMetricFinishedAtNanos - loadMetricStartedAtNanos),
                        nanosToMillis(loadCategoryFinishedAtNanos - loadCategoryStartedAtNanos),
                        nanosToMillis(assembleRankingFinishedAtNanos - assembleRankingStartedAtNanos),
                        nanosToMillis(writeRankingFinishedAtNanos - writeRankingStartedAtNanos),
                        rankingChunkTotalMs);
                if (!identityPage.hasNext()) {
                    break;
                }
                page++;
            }

            page = 0;
            long scoringRows = 0L;
            int scoringChunkNo = 0;
            String lastCategoryFactId = null;
            while (true) {
                long scoringChunkStartedAtNanos = System.nanoTime();
                long loadCategoryStartedAtNanos = System.nanoTime();
                List<WosCategoryFact> categoryFacts = findScoringCategoryFactChunk(lastCategoryFactId, chunkSize);
                long loadCategoryFinishedAtNanos = System.nanoTime();
                loadCategoryNs += System.nanoTime() - loadCategoryStartedAtNanos;
                if (categoryFacts.isEmpty()) {
                    break;
                }
                lastCategoryFactId = categoryFacts.getLast().getId();

                long loadMetricStartedAtNanos = System.nanoTime();
                Map<ScoreKey, WosMetricFact> scoreByKey = preloadMetricFactsByScoreKey(categoryFacts);
                long loadMetricFinishedAtNanos = System.nanoTime();
                loadMetricNs += System.nanoTime() - loadMetricStartedAtNanos;

                long assembleScoringStartedAtNanos = System.nanoTime();
                List<WosScoringView> scoringViews = new ArrayList<>(categoryFacts.size());
                for (WosCategoryFact categoryFact : categoryFacts) {
                    result.markProcessed();
                    WosMetricFact score = scoreByKey.get(new ScoreKey(categoryFact.getJournalId(), categoryFact.getYear(), categoryFact.getMetricType()));
                    scoringViews.add(toScoringView(categoryFact, score, buildVersion, buildAt));
                }
                long assembleScoringFinishedAtNanos = System.nanoTime();
                assembleScoringNs += assembleScoringFinishedAtNanos - assembleScoringStartedAtNanos;

                long writeScoringStartedAtNanos = System.nanoTime();
                BulkOperations bulkOperations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, WosScoringView.class);
                bulkOperations.insert(scoringViews);
                bulkOperations.execute();
                long writeScoringFinishedAtNanos = System.nanoTime();
                writeScoringNs += writeScoringFinishedAtNanos - writeScoringStartedAtNanos;
                scoringRows += scoringViews.size();
                for (int i = 0; i < scoringViews.size(); i++) {
                    result.markImported();
                }
                scoringChunkNo++;
                long scoringChunkTotalMs = nanosToMillis(System.nanoTime() - scoringChunkStartedAtNanos);
                String scoringChunkHealth = scoringChunkTotalMs >= optimizationProperties.getSlowChunkThresholdMs() ? "SLOW" : "OK";
                log.info("WoS projection scoring chunk {} complete [page={}]: rows={} cumulativeRows={} health={} timingsMs[loadCategory={}, loadMetric={}, assembleScoring={}, writeScoring={}, total={}]",
                        scoringChunkNo,
                        page,
                        scoringViews.size(),
                        scoringRows,
                        scoringChunkHealth,
                        nanosToMillis(loadCategoryFinishedAtNanos - loadCategoryStartedAtNanos),
                        nanosToMillis(loadMetricFinishedAtNanos - loadMetricStartedAtNanos),
                        nanosToMillis(assembleScoringFinishedAtNanos - assembleScoringStartedAtNanos),
                        nanosToMillis(writeScoringFinishedAtNanos - writeScoringStartedAtNanos),
                        scoringChunkTotalMs);
                if (categoryFacts.size() < chunkSize) {
                    break;
                }
                page++;
            }

            long totalNs = System.nanoTime() - totalStartedAtNanos;
            log.info("WoS projection rebuild complete: buildVersion={}, rankingRows={}, scoringRows={} timingsMs[loadIdentity={}, loadMetric={}, loadCategory={}, assembleRanking={}, assembleScoring={}, writeRanking={}, writeScoring={}, total={}]",
                    buildVersion, rankingRows, scoringRows,
                    nanosToMillis(loadIdentityNs),
                    nanosToMillis(loadMetricNs),
                    nanosToMillis(loadCategoryNs),
                    nanosToMillis(assembleRankingNs),
                    nanosToMillis(assembleScoringNs),
                    nanosToMillis(writeRankingNs),
                    nanosToMillis(writeScoringNs),
                    nanosToMillis(totalNs));
        } catch (Exception e) {
            result.markError("projection-rebuild-error=" + e.getMessage());
            log.error("WoS projection rebuild failed", e);
        }
        return result;
    }

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

    private List<WosMetricFact> findMetricFactsByJournalIds(Set<String> journalIds) {
        if (journalIds == null || journalIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("journalId").in(journalIds));
        query.fields()
                .include("journalId")
                .include("year")
                .include("metricType")
                .include("value");
        return mongoTemplate.find(query, WosMetricFact.class);
    }

    private List<WosCategoryFact> findCategoryFactsByJournalIds(Set<String> journalIds) {
        if (journalIds == null || journalIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("journalId").in(journalIds));
        query.fields()
                .include("journalId")
                .include("year")
                .include("metricType")
                .include("editionNormalized")
                .include("sourceVersion")
                .include("sourceRowItem");
        return mongoTemplate.find(query, WosCategoryFact.class);
    }

    private List<WosCategoryFact> findScoringCategoryFactChunk(String lastId, int chunkSize) {
        Query query = new Query();
        if (lastId != null) {
            query.addCriteria(Criteria.where("_id").gt(lastId));
        }
        query.limit(chunkSize);
        query.with(Sort.by(Sort.Order.asc("_id")));
        query.fields()
                .include("journalId")
                .include("year")
                .include("metricType")
                .include("categoryNameCanonical")
                .include("editionNormalized")
                .include("quarter")
                .include("quartileRank")
                .include("rank");
        return mongoTemplate.find(query, WosCategoryFact.class);
    }

    private Map<ScoreKey, WosMetricFact> preloadMetricFactsByScoreKey(List<WosCategoryFact> categoryFacts) {
        if (categoryFacts == null || categoryFacts.isEmpty()) {
            return Map.of();
        }
        Map<MetricPreloadGroup, Set<String>> groupToJournalIds = new HashMap<>();
        for (WosCategoryFact fact : categoryFacts) {
            if (fact.getJournalId() == null || fact.getYear() == null || fact.getMetricType() == null) {
                continue;
            }
            MetricPreloadGroup group = new MetricPreloadGroup(fact.getYear(), fact.getMetricType());
            groupToJournalIds.computeIfAbsent(group, ignored -> new LinkedHashSet<>()).add(fact.getJournalId());
        }
        Map<ScoreKey, WosMetricFact> out = new HashMap<>();
        for (Map.Entry<MetricPreloadGroup, Set<String>> entry : groupToJournalIds.entrySet()) {
            MetricPreloadGroup group = entry.getKey();
            Query query = new Query(new Criteria().andOperator(
                    Criteria.where("year").is(group.year()),
                    Criteria.where("metricType").is(group.metricType()),
                    Criteria.where("journalId").in(entry.getValue())
            ));
            for (WosMetricFact fact : mongoTemplate.find(query, WosMetricFact.class)) {
                out.put(new ScoreKey(fact.getJournalId(), fact.getYear(), fact.getMetricType()), fact);
            }
        }
        return out;
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private record MetricPreloadGroup(Integer year, MetricType metricType) {
    }
}
