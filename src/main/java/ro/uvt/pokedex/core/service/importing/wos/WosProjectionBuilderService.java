package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

@Service
public class WosProjectionBuilderService {

    private static final Logger log = LoggerFactory.getLogger(WosProjectionBuilderService.class);

    private final WosJournalIdentityRepository identityRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosRankingViewRepository rankingViewRepository;
    private final WosScoringViewRepository scoringViewRepository;

    public WosProjectionBuilderService(
            WosJournalIdentityRepository identityRepository,
            WosMetricFactRepository metricFactRepository,
            WosRankingViewRepository rankingViewRepository,
            WosScoringViewRepository scoringViewRepository
    ) {
        this.identityRepository = identityRepository;
        this.metricFactRepository = metricFactRepository;
        this.rankingViewRepository = rankingViewRepository;
        this.scoringViewRepository = scoringViewRepository;
    }

    public ImportProcessingResult rebuildWosProjections() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant buildAt = Instant.now();
        String buildVersion = buildAt.toString();
        try {
            List<WosJournalIdentity> identities = identityRepository.findAll();
            List<WosMetricFact> metricFacts = metricFactRepository.findAll();

            Map<String, List<WosMetricFact>> metricByJournal = new HashMap<>();

            for (WosMetricFact fact : metricFacts) {
                metricByJournal.computeIfAbsent(fact.getJournalId(), key -> new ArrayList<>()).add(fact);
            }

            List<WosRankingView> rankingViews = new ArrayList<>(identities.size());
            for (WosJournalIdentity identity : identities) {
                result.markProcessed();
                rankingViews.add(toRankingView(identity, metricByJournal.getOrDefault(identity.getId(), List.of()), buildVersion, buildAt));
            }

            List<WosScoringView> scoringViews = new ArrayList<>(metricFacts.size());
            for (WosMetricFact metricFact : metricFacts) {
                result.markProcessed();
                scoringViews.add(toScoringView(metricFact, buildVersion, buildAt));
            }

            rankingViewRepository.deleteAll();
            scoringViewRepository.deleteAll();
            rankingViewRepository.saveAll(rankingViews);
            scoringViewRepository.saveAll(scoringViews);
            for (int i = 0; i < rankingViews.size() + scoringViews.size(); i++) {
                result.markImported();
            }
            log.info("WoS projection rebuild complete: buildVersion={}, rankingRows={}, scoringRows={}",
                    buildVersion, rankingViews.size(), scoringViews.size());
        } catch (Exception e) {
            result.markError("projection-rebuild-error=" + e.getMessage());
            log.error("WoS projection rebuild failed", e);
        }
        return result;
    }

    private WosRankingView toRankingView(
            WosJournalIdentity identity,
            List<WosMetricFact> journalFacts,
            String buildVersion,
            Instant buildAt
    ) {
        WosRankingView view = new WosRankingView();
        view.setId(identity.getId());
        view.setName(identity.getTitle());
        view.setIssn(identity.getPrimaryIssn());
        view.setEIssn(identity.getEIssn());
        view.setAlternativeIssns(identity.getAliasIssns() == null ? List.of() : new ArrayList<>(identity.getAliasIssns()));
        view.setNameNorm(normalizeText(identity.getTitle()));
        view.setIssnNorm(normalizeIssn(identity.getPrimaryIssn()));
        view.setEIssnNorm(normalizeIssn(identity.getEIssn()));
        view.setAlternativeIssnsNorm(normalizeIssnList(identity.getAliasIssns()));
        view.setLatestAisYear(maxYearWithValue(journalFacts, MetricType.AIS));
        view.setLatestRisYear(maxYearWithValue(journalFacts, MetricType.RIS));
        view.setLatestEditionNormalized(resolveLatestEdition(journalFacts));
        view.setBuildVersion(buildVersion);
        view.setBuildAt(buildAt);
        view.setUpdatedAt(buildAt);
        return view;
    }

    private WosScoringView toScoringView(WosMetricFact metricFact, String buildVersion, Instant buildAt) {
        WosScoringView view = new WosScoringView();
        view.setId(scoringViewId(
                metricFact.getJournalId(),
                metricFact.getYear(),
                metricFact.getCategoryNameCanonical(),
                metricFact.getEditionNormalized(),
                metricFact.getMetricType()
        ));
        view.setJournalId(metricFact.getJournalId());
        view.setYear(metricFact.getYear());
        view.setCategoryNameCanonical(metricFact.getCategoryNameCanonical());
        view.setEditionNormalized(metricFact.getEditionNormalized());
        view.setMetricType(metricFact.getMetricType());
        view.setQuarter(metricFact.getQuarter());
        view.setRank(metricFact.getRank());
        view.setValue(metricFact.getValue());
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

    private EditionNormalized resolveLatestEdition(List<WosMetricFact> facts) {
        WosMetricFact latestAis = latestMetricWithValue(facts, MetricType.AIS);
        if (latestAis != null && latestAis.getEditionNormalized() != null) {
            return latestAis.getEditionNormalized();
        }
        WosMetricFact latestRis = latestMetricWithValue(facts, MetricType.RIS);
        if (latestRis != null && latestRis.getEditionNormalized() != null) {
            return latestRis.getEditionNormalized();
        }
        return EditionNormalized.UNKNOWN;
    }

    private WosMetricFact latestMetricWithValue(List<WosMetricFact> facts, MetricType metricType) {
        return facts.stream()
                .filter(f -> f.getMetricType() == metricType)
                .filter(f -> f.getValue() != null)
                .max(Comparator
                        .comparing(WosMetricFact::getYear, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(WosMetricFact::getSourceVersion, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WosMetricFact::getSourceRowItem, Comparator.nullsFirst(String::compareTo)))
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
}
