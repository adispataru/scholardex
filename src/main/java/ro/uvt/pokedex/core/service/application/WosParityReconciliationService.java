package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WosParityReconciliationService {

    private static final int DEFAULT_SAMPLE_LIMIT = 20;

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final WosImportEventRepository importEventRepository;
    private final WosMetricFactRepository metricFactRepository;
    private final WosCategoryFactRepository categoryFactRepository;
    private final WosRankingViewRepository rankingViewRepository;
    private final WosScoringViewRepository scoringViewRepository;

    @Value("${h14.wos.parity.baseline:classpath:wos/parity/baseline-v1.json}")
    private String baselineLocation;

    public ParityReconciliationResult runEligibilityCheck() {
        List<String> executedChecks = List.of("eligibility");
        try {
            Baseline baseline = loadBaseline();
            List<String> notes = new ArrayList<>();
            if (baseline.allowlistedMismatches().isEmpty()) {
                notes.add("eligibility.baselineLoaded: OK (no allowlist entries)");
            } else {
                notes.add("eligibility.baselineLoaded: OK (allowlist=" + baseline.allowlistedMismatches().size() + ")");
            }
            return new ParityReconciliationResult(true, true, executedChecks, 0, 0, capAndSort(notes));
        } catch (Exception ex) {
            return new ParityReconciliationResult(false, false, executedChecks, 1, 0,
                    List.of("eligibility.baselineLoadFailed: " + ex.getMessage()));
        }
    }

    public ParityReconciliationResult runFullParity() {
        Baseline baseline;
        try {
            baseline = loadBaseline();
        } catch (Exception ex) {
            return new ParityReconciliationResult(false, false, List.of("baseline-load"), 1, 0,
                    List.of("baseline.load: " + ex.getMessage()));
        }

        Set<String> allowlist = baseline.allowlistedMismatches();
        List<String> executedChecks = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        int allowlistedMismatchCount = 0;

        executedChecks.add("counts");
        allowlistedMismatchCount += compareCount("counts.importEvents", baseline.root().path("counts").path("importEvents"), importEventRepository.count(), allowlist, mismatches);
        allowlistedMismatchCount += compareCount("counts.metricFacts", baseline.root().path("counts").path("metricFacts"), metricFactRepository.count(), allowlist, mismatches);
        allowlistedMismatchCount += compareCount("counts.categoryFacts", baseline.root().path("counts").path("categoryFacts"), categoryFactRepository.count(), allowlist, mismatches);
        allowlistedMismatchCount += compareCount("counts.rankingView", baseline.root().path("counts").path("rankingView"), rankingViewRepository.count(), allowlist, mismatches);
        allowlistedMismatchCount += compareCount("counts.scoringView", baseline.root().path("counts").path("scoringView"), scoringViewRepository.count(), allowlist, mismatches);

        List<WosMetricFact> metricFacts = metricFactRepository.findAll();
        List<WosCategoryFact> categoryFacts = categoryFactRepository.findAll();
        List<WosScoringView> scoringRows = scoringViewRepository.findAll();

        executedChecks.add("edition-normalization");
        allowlistedMismatchCount += checkEditionCounts(baseline.root().path("editionCounts"), categoryFacts, allowlist, mismatches);
        allowlistedMismatchCount += checkScienceToScieCount(baseline.root().path("scienceToScieExpectedCount"), categoryFacts, allowlist, mismatches);
        allowlistedMismatchCount += checkBundledSplit(baseline.root().path("bundledSplitChecks"), categoryFacts, allowlist, mismatches);

        executedChecks.add("timelines");
        allowlistedMismatchCount += checkMetricTimelines(baseline.root().path("timelineChecks"), metricFacts, allowlist, mismatches);

        executedChecks.add("categories");
        allowlistedMismatchCount += checkCategoryTimelines(baseline.root().path("categoryChecks"), categoryFacts, allowlist, mismatches);

        executedChecks.add("scores");
        allowlistedMismatchCount += checkScoringViewTopCounts(baseline.root().path("scoringChecks"), scoringRows, allowlist, mismatches);
        allowlistedMismatchCount += checkIfMissing(baseline.root().path("ifMissingChecks"), metricFacts, allowlist, mismatches);

        executedChecks.add("replay-determinism");
        allowlistedMismatchCount += checkReplayDeterminism(baseline.root().path("replayChecks"), metricFacts, categoryFacts, allowlist, mismatches);

        List<String> normalized = capAndSort(mismatches);
        return new ParityReconciliationResult(
                normalized.isEmpty(),
                true,
                List.copyOf(executedChecks),
                normalized.size(),
                allowlistedMismatchCount,
                normalized
        );
    }

    private Baseline loadBaseline() throws IOException {
        Resource resource = resourceLoader.getResource(baselineLocation);
        if (!resource.exists()) {
            throw new IOException("baseline not found at " + baselineLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (root == null || root.isMissingNode()) {
                throw new IOException("baseline is empty: " + baselineLocation);
            }
            Set<String> allowlisted = new LinkedHashSet<>();
            JsonNode allowlistedNode = root.path("allowlistedMismatches");
            if (allowlistedNode.isArray()) {
                for (JsonNode node : allowlistedNode) {
                    if (node != null && node.isTextual() && !node.asText().isBlank()) {
                        allowlisted.add(node.asText().trim());
                    }
                }
            }
            return new Baseline(root, allowlisted);
        }
    }

    private int compareCount(String key, JsonNode expectedNode, long actual, Set<String> allowlist, List<String> mismatches) {
        if (expectedNode == null || expectedNode.isMissingNode() || !expectedNode.canConvertToLong()) {
            return 0;
        }
        long expected = expectedNode.asLong();
        if (expected == actual) {
            return 0;
        }
        String mismatch = key + " expected=" + expected + " actual=" + actual;
        if (allowlist.contains(key)) {
            return 1;
        }
        mismatches.add(mismatch);
        return 0;
    }

    private int checkEditionCounts(JsonNode editionCountsNode, List<WosCategoryFact> categoryFacts, Set<String> allowlist, List<String> mismatches) {
        if (editionCountsNode == null || !editionCountsNode.isObject()) {
            return 0;
        }
        Map<String, Long> actual = categoryFacts.stream()
                .collect(Collectors.groupingBy(f -> normalizeEditionName(f.getEditionNormalized()), Collectors.counting()));

        int allowlisted = 0;
        var fields = editionCountsNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().canConvertToLong()) {
                continue;
            }
            String edition = entry.getKey().trim().toUpperCase(Locale.ROOT);
            long expected = entry.getValue().asLong();
            long actualCount = actual.getOrDefault(edition, 0L);
            String key = "editionCounts." + edition;
            if (expected != actualCount) {
                if (allowlist.contains(key)) {
                    allowlisted++;
                } else {
                    mismatches.add(key + " expected=" + expected + " actual=" + actualCount);
                }
            }
        }
        return allowlisted;
    }

    private int checkScienceToScieCount(JsonNode scienceExpectedNode, List<WosCategoryFact> categoryFacts, Set<String> allowlist, List<String> mismatches) {
        if (scienceExpectedNode == null || scienceExpectedNode.isMissingNode() || !scienceExpectedNode.canConvertToLong()) {
            return 0;
        }
        long actual = categoryFacts.stream()
                .filter(f -> f.getEditionRaw() != null)
                .filter(f -> f.getEditionRaw().toUpperCase(Locale.ROOT).contains("SCIENCE"))
                .filter(f -> f.getEditionNormalized() == EditionNormalized.SCIE)
                .count();
        long expected = scienceExpectedNode.asLong();
        if (actual == expected) {
            return 0;
        }
        String key = "editionNormalization.scienceToScie";
        if (allowlist.contains(key)) {
            return 1;
        }
        mismatches.add(key + " expected=" + expected + " actual=" + actual);
        return 0;
    }

    private int checkBundledSplit(JsonNode bundledChecksNode, List<WosCategoryFact> categoryFacts, Set<String> allowlist, List<String> mismatches) {
        if (bundledChecksNode == null || !bundledChecksNode.isArray()) {
            return 0;
        }
        int allowlisted = 0;
        for (int i = 0; i < bundledChecksNode.size(); i++) {
            JsonNode check = bundledChecksNode.get(i);
            String sourceEventId = text(check, "sourceEventId");
            Integer year = intOrNull(check, "year");
            String category = text(check, "categoryNameCanonical");
            MetricType metricType = metricTypeOrNull(text(check, "metricType"));
            Integer expectedEditionCount = intOrNull(check, "expectedEditionCount");
            if (sourceEventId == null || year == null || category == null || metricType == null || expectedEditionCount == null) {
                continue;
            }

            long actualDistinctEditions = categoryFacts.stream()
                    .filter(f -> sourceEventId.equals(f.getSourceEventId()))
                    .filter(f -> Objects.equals(year, f.getYear()))
                    .filter(f -> category.equals(f.getCategoryNameCanonical()))
                    .filter(f -> metricType == f.getMetricType())
                    .map(f -> normalizeEditionName(f.getEditionNormalized()))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .count();

            String key = "bundledSplit[" + i + "]";
            if (actualDistinctEditions != expectedEditionCount.longValue()) {
                if (allowlist.contains(key)) {
                    allowlisted++;
                } else {
                    mismatches.add(key + " expected=" + expectedEditionCount + " actual=" + actualDistinctEditions);
                }
            }
        }
        return allowlisted;
    }

    private int checkMetricTimelines(JsonNode timelineChecksNode, List<WosMetricFact> metricFacts, Set<String> allowlist, List<String> mismatches) {
        if (timelineChecksNode == null || !timelineChecksNode.isArray()) {
            return 0;
        }
        int allowlisted = 0;
        for (int i = 0; i < timelineChecksNode.size(); i++) {
            JsonNode check = timelineChecksNode.get(i);
            String journalId = text(check, "journalId");
            MetricType metricType = metricTypeOrNull(text(check, "metricType"));
            EditionNormalized edition = editionOrNull(text(check, "edition"));
            JsonNode expectedNode = check.path("expected");
            if (journalId == null || metricType == null || edition == null || !expectedNode.isObject()) {
                continue;
            }

            Map<Integer, Double> actualByYear = metricFacts.stream()
                    .filter(f -> journalId.equals(f.getJournalId()))
                    .filter(f -> metricType == f.getMetricType())
                    .filter(f -> edition == f.getEditionNormalized())
                    .filter(f -> f.getYear() != null)
                    .collect(Collectors.toMap(
                            WosMetricFact::getYear,
                            f -> f.getValue() == null ? Double.NaN : f.getValue(),
                            (left, right) -> Double.isNaN(left) ? right : left
                    ));

            var fields = expectedNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                Integer year = parseYear(entry.getKey());
                if (year == null || !entry.getValue().isNumber()) {
                    continue;
                }
                double expectedValue = entry.getValue().asDouble();
                Double actualValue = actualByYear.get(year);
                String key = "timeline[" + i + "]." + year;
                if (actualValue == null || Math.abs(actualValue - expectedValue) > 1e-9) {
                    if (allowlist.contains(key)) {
                        allowlisted++;
                    } else {
                        mismatches.add(key + " expected=" + expectedValue + " actual=" + (actualValue == null ? "<missing>" : actualValue));
                    }
                }
            }
        }
        return allowlisted;
    }

    private int checkCategoryTimelines(JsonNode categoryChecksNode, List<WosCategoryFact> categoryFacts, Set<String> allowlist, List<String> mismatches) {
        if (categoryChecksNode == null || !categoryChecksNode.isArray()) {
            return 0;
        }
        int allowlisted = 0;
        for (int i = 0; i < categoryChecksNode.size(); i++) {
            JsonNode check = categoryChecksNode.get(i);
            String journalId = text(check, "journalId");
            String category = text(check, "categoryNameCanonical");
            MetricType metricType = metricTypeOrNull(text(check, "metricType"));
            EditionNormalized edition = editionOrNull(text(check, "edition"));
            JsonNode expected = check.path("expected");
            if (journalId == null || category == null || metricType == null || edition == null || !expected.isObject()) {
                continue;
            }

            List<WosCategoryFact> filtered = categoryFacts.stream()
                    .filter(f -> journalId.equals(f.getJournalId()))
                    .filter(f -> category.equals(f.getCategoryNameCanonical()))
                    .filter(f -> metricType == f.getMetricType())
                    .filter(f -> edition == f.getEditionNormalized())
                    .toList();

            Map<Integer, WosCategoryFact> byYear = filtered.stream()
                    .filter(f -> f.getYear() != null)
                    .collect(Collectors.toMap(WosCategoryFact::getYear, f -> f, (a, b) -> a));

            var fields = expected.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                Integer year = parseYear(entry.getKey());
                JsonNode yearExpected = entry.getValue();
                if (year == null || !yearExpected.isObject()) {
                    continue;
                }
                WosCategoryFact fact = byYear.get(year);
                String expectedQuarter = text(yearExpected, "quarter");
                Integer expectedRank = intOrNull(yearExpected, "rank");
                String key = "category[" + i + "]." + year;

                boolean mismatch = fact == null
                        || !Objects.equals(expectedQuarter, fact.getQuarter())
                        || !Objects.equals(expectedRank, fact.getRank());
                if (mismatch) {
                    if (allowlist.contains(key)) {
                        allowlisted++;
                    } else {
                        mismatches.add(key + " expectedQuarter=" + expectedQuarter
                                + " expectedRank=" + expectedRank
                                + " actualQuarter=" + (fact == null ? "<missing>" : fact.getQuarter())
                                + " actualRank=" + (fact == null ? "<missing>" : fact.getRank()));
                    }
                }
            }
        }
        return allowlisted;
    }

    private int checkScoringViewTopCounts(JsonNode scoringChecksNode, List<WosScoringView> scoringRows, Set<String> allowlist, List<String> mismatches) {
        if (scoringChecksNode == null || !scoringChecksNode.isArray()) {
            return 0;
        }
        int allowlisted = 0;
        for (int i = 0; i < scoringChecksNode.size(); i++) {
            JsonNode check = scoringChecksNode.get(i);
            String category = text(check, "categoryNameCanonical");
            EditionNormalized edition = editionOrNull(text(check, "edition"));
            MetricType metricType = metricTypeOrNull(text(check, "metricType"));
            String quarter = text(check, "quarter");
            Integer year = intOrNull(check, "year");
            Integer expectedTopCount = intOrNull(check, "expectedTopCount");
            if (category == null || edition == null || metricType == null || quarter == null || year == null || expectedTopCount == null) {
                continue;
            }

            long actualCount = scoringRows.stream()
                    .filter(s -> category.equals(s.getCategoryNameCanonical()))
                    .filter(s -> edition == s.getEditionNormalized())
                    .filter(s -> metricType == s.getMetricType())
                    .filter(s -> year.equals(s.getYear()))
                    .filter(s -> quarter.equals(s.getQuarter()))
                    .map(WosScoringView::getJournalId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();

            String key = "scoring[" + i + "]";
            if (actualCount != expectedTopCount.longValue()) {
                if (allowlist.contains(key)) {
                    allowlisted++;
                } else {
                    mismatches.add(key + " expected=" + expectedTopCount + " actual=" + actualCount);
                }
            }
        }
        return allowlisted;
    }

    private int checkIfMissing(JsonNode ifMissingChecksNode, List<WosMetricFact> metricFacts, Set<String> allowlist, List<String> mismatches) {
        if (ifMissingChecksNode == null || !ifMissingChecksNode.isArray()) {
            return 0;
        }
        int allowlisted = 0;
        for (int i = 0; i < ifMissingChecksNode.size(); i++) {
            JsonNode check = ifMissingChecksNode.get(i);
            String journalId = text(check, "journalId");
            Integer year = intOrNull(check, "year");
            EditionNormalized edition = editionOrNull(text(check, "edition"));
            Boolean expectedMissing = boolOrNull(check, "expectedMissing");
            if (journalId == null || year == null || edition == null || expectedMissing == null) {
                continue;
            }

            boolean hasIf = metricFacts.stream()
                    .anyMatch(f -> journalId.equals(f.getJournalId())
                            && year.equals(f.getYear())
                            && f.getMetricType() == MetricType.IF
                            && f.getEditionNormalized() == edition
                            && f.getValue() != null);

            boolean missing = !hasIf;
            String key = "ifMissing[" + i + "]";
            if (!Objects.equals(expectedMissing, missing)) {
                if (allowlist.contains(key)) {
                    allowlisted++;
                } else {
                    mismatches.add(key + " expectedMissing=" + expectedMissing + " actualMissing=" + missing);
                }
            }
        }
        return allowlisted;
    }

    private int checkReplayDeterminism(
            JsonNode replayChecksNode,
            List<WosMetricFact> metricFacts,
            List<WosCategoryFact> categoryFacts,
            Set<String> allowlist,
            List<String> mismatches
    ) {
        if (replayChecksNode == null || replayChecksNode.isMissingNode() || !replayChecksNode.isObject()) {
            return 0;
        }
        int allowlisted = 0;

        Integer expectedDuplicateImportEventKeys = intOrNull(replayChecksNode, "expectedDuplicateImportEventKeys");
        Integer expectedDuplicateMetricFactKeys = intOrNull(replayChecksNode, "expectedDuplicateMetricFactKeys");
        Integer expectedDuplicateCategoryFactKeys = intOrNull(replayChecksNode, "expectedDuplicateCategoryFactKeys");

        if (expectedDuplicateImportEventKeys != null) {
            Set<String> seen = new HashSet<>();
            int duplicates = 0;
            for (var event : importEventRepository.findAll()) {
                String key = String.join("|",
                        String.valueOf(event.getSourceType()),
                        String.valueOf(event.getSourceFile()),
                        String.valueOf(event.getSourceVersion()),
                        String.valueOf(event.getSourceRowItem()));
                if (!seen.add(key)) {
                    duplicates++;
                }
            }
            allowlisted += mismatchInt("replay.duplicateImportEventKeys", expectedDuplicateImportEventKeys, duplicates, allowlist, mismatches);
        }

        if (expectedDuplicateMetricFactKeys != null) {
            Set<String> seen = new HashSet<>();
            int duplicates = 0;
            for (WosMetricFact fact : metricFacts) {
                String key = String.join("|",
                        String.valueOf(fact.getJournalId()),
                        String.valueOf(fact.getYear()),
                        String.valueOf(fact.getMetricType()),
                        String.valueOf(fact.getEditionNormalized()));
                if (!seen.add(key)) {
                    duplicates++;
                }
            }
            allowlisted += mismatchInt("replay.duplicateMetricFactKeys", expectedDuplicateMetricFactKeys, duplicates, allowlist, mismatches);
        }

        if (expectedDuplicateCategoryFactKeys != null) {
            Set<String> seen = new HashSet<>();
            int duplicates = 0;
            for (WosCategoryFact fact : categoryFacts) {
                String key = String.join("|",
                        String.valueOf(fact.getJournalId()),
                        String.valueOf(fact.getYear()),
                        String.valueOf(fact.getCategoryNameCanonical()),
                        String.valueOf(fact.getEditionNormalized()),
                        String.valueOf(fact.getMetricType()));
                if (!seen.add(key)) {
                    duplicates++;
                }
            }
            allowlisted += mismatchInt("replay.duplicateCategoryFactKeys", expectedDuplicateCategoryFactKeys, duplicates, allowlist, mismatches);
        }

        return allowlisted;
    }

    private int mismatchInt(String key, int expected, int actual, Set<String> allowlist, List<String> mismatches) {
        if (expected == actual) {
            return 0;
        }
        if (allowlist.contains(key)) {
            return 1;
        }
        mismatches.add(key + " expected=" + expected + " actual=" + actual);
        return 0;
    }

    private List<String> capAndSort(List<String> input) {
        return input.stream()
                .filter(s -> s != null && !s.isBlank())
                .sorted(Comparator.naturalOrder())
                .limit(DEFAULT_SAMPLE_LIMIT)
                .toList();
    }

    private String normalizeEditionName(EditionNormalized edition) {
        return Optional.ofNullable(edition)
                .map(Enum::name)
                .orElse(EditionNormalized.UNKNOWN.name());
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (!child.isTextual()) {
            return null;
        }
        String value = child.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (!child.canConvertToInt()) {
            return null;
        }
        return child.asInt();
    }

    private Boolean boolOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (!child.isBoolean()) {
            return null;
        }
        return child.asBoolean();
    }

    private Integer parseYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private MetricType metricTypeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MetricType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private EditionNormalized editionOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EditionNormalized.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record Baseline(JsonNode root, Set<String> allowlistedMismatches) {
    }

    public record ParityReconciliationResult(
            boolean passed,
            boolean baselineAvailable,
            List<String> executedChecks,
            int mismatchCount,
            int allowlistedMismatchCount,
            List<String> mismatches
    ) {
    }
}
