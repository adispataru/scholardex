package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosScoringView;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosParityReconciliationServiceTest {

    @Mock private WosImportEventRepository importEventRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void fullParityPassesWhenBaselineMatches() {
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "baselineLocation", "classpath:wos/parity/baseline-test-pass.json");

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("j1");
        metricFact.setYear(2023);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(1.5);

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setJournalId("j1");
        categoryFact.setYear(2023);
        categoryFact.setCategoryNameCanonical("Computer Science, Theory & Methods");
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setEditionRaw("SCIENCE");
        categoryFact.setQuarter("Q1");
        categoryFact.setRank(1);

        when(importEventRepository.count()).thenReturn(1L);
        WosImportEvent importEvent = new WosImportEvent();
        importEvent.setSourceType(WosSourceType.GOV_AIS_RIS);
        importEvent.setSourceFile("AIS_2023.xlsx");
        importEvent.setSourceVersion("v2023");
        importEvent.setSourceRowItem("1");
        when(importEventRepository.findAll()).thenReturn(List.of(importEvent));
        when(metricFactRepository.count()).thenReturn(1L);
        when(categoryFactRepository.count()).thenReturn(1L);
        WosScoringView scoringView = new WosScoringView();
        scoringView.setJournalId("j1");
        scoringView.setCategoryNameCanonical("Computer Science, Theory & Methods");
        scoringView.setEditionNormalized(EditionNormalized.SCIE);
        scoringView.setMetricType(MetricType.AIS);
        scoringView.setYear(2023);
        scoringView.setQuarter("Q1");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(scoringView));
        when(metricFactRepository.findAll()).thenReturn(List.of(metricFact));
        when(categoryFactRepository.findAll()).thenReturn(List.of(categoryFact));

        var result = service.runFullParity();

        assertTrue(result.baselineAvailable());
        assertTrue(result.passed());
        assertTrue(result.mismatches().isEmpty());
    }

    @Test
    void fullParityFailsDeterministicallyWhenCountsDiffer() {
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "baselineLocation", "classpath:wos/parity/baseline-test-fail.json");

        when(importEventRepository.count()).thenReturn(0L);
        when(importEventRepository.findAll()).thenReturn(List.of());
        when(metricFactRepository.count()).thenReturn(0L);
        when(categoryFactRepository.count()).thenReturn(0L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(metricFactRepository.findAll()).thenReturn(List.of());
        when(categoryFactRepository.findAll()).thenReturn(List.of());

        var result = service.runFullParity();

        assertTrue(result.baselineAvailable());
        assertFalse(result.passed());
        assertTrue(result.mismatchCount() > 0);
        assertTrue(result.mismatches().stream().anyMatch(m -> m.startsWith("counts.importEvents")));
    }

    @Test
    void eligibilityFailsWhenBaselineMissing() {
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "baselineLocation", "classpath:wos/parity/does-not-exist.json");

        var result = service.runEligibilityCheck();

        assertFalse(result.baselineAvailable());
        assertFalse(result.passed());
        assertTrue(result.mismatchCount() > 0);
    }

    @Test
    void privateChecksAndParsingHelpersCoverBranches() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );

        List<String> mismatches = new ArrayList<>();
        Set<String> allowlist = Set.of("counts.importEvents");

        JsonNode expectedCount = mapper.readTree("10");
        int allowlistedCount = ReflectionTestUtils.invokeMethod(
                service, "compareCount", "counts.importEvents", expectedCount, 3L, allowlist, mismatches
        );
        assertEquals(1, allowlistedCount);

        WosCategoryFact category = new WosCategoryFact();
        category.setJournalId("j1");
        category.setYear(2024);
        category.setCategoryNameCanonical("Cat");
        category.setMetricType(MetricType.AIS);
        category.setEditionNormalized(EditionNormalized.SCIE);
        category.setEditionRaw("SCIENCE");
        category.setQuarter("Q1");
        category.setRank(1);
        category.setSourceEventId("ev1");

        JsonNode editionCounts = mapper.readTree("{\"SCIE\":1}");
        int editionAllow = ReflectionTestUtils.invokeMethod(
                service, "checkEditionCounts", editionCounts, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, editionAllow);

        JsonNode scienceCount = mapper.readTree("1");
        int scienceAllow = ReflectionTestUtils.invokeMethod(
                service, "checkScienceToScieCount", scienceCount, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, scienceAllow);

        JsonNode bundledChecks = mapper.readTree("[{\"sourceEventId\":\"ev1\",\"year\":2024,\"categoryNameCanonical\":\"Cat\",\"metricType\":\"AIS\",\"expectedEditionCount\":1}]");
        int bundledAllow = ReflectionTestUtils.invokeMethod(
                service, "checkBundledSplit", bundledChecks, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, bundledAllow);

        WosMetricFact metric = new WosMetricFact();
        metric.setJournalId("j1");
        metric.setYear(2024);
        metric.setMetricType(MetricType.AIS);
        metric.setValue(2.5);

        JsonNode timelineChecks = mapper.readTree("[{\"journalId\":\"j1\",\"metricType\":\"AIS\",\"edition\":\"SCIE\",\"expected\":{\"2024\":2.5}}]");
        int timelineAllow = ReflectionTestUtils.invokeMethod(
                service, "checkMetricTimelines", timelineChecks, List.of(metric), Set.of(), mismatches
        );
        assertEquals(0, timelineAllow);

        JsonNode categoryChecks = mapper.readTree("[{\"journalId\":\"j1\",\"categoryNameCanonical\":\"Cat\",\"metricType\":\"AIS\",\"edition\":\"SCIE\",\"expected\":{\"2024\":{\"quarter\":\"Q1\",\"rank\":1}}}]");
        int categoryAllow = ReflectionTestUtils.invokeMethod(
                service, "checkCategoryTimelines", categoryChecks, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, categoryAllow);

        WosScoringView scoring = new WosScoringView();
        scoring.setJournalId("j1");
        scoring.setCategoryNameCanonical("Cat");
        scoring.setEditionNormalized(EditionNormalized.SCIE);
        scoring.setMetricType(MetricType.AIS);
        scoring.setYear(2024);
        scoring.setQuarter("Q1");
        JsonNode scoringChecks = mapper.readTree("[{\"categoryNameCanonical\":\"Cat\",\"edition\":\"SCIE\",\"metricType\":\"AIS\",\"quarter\":\"Q1\",\"year\":2024,\"expectedTopCount\":1}]");
        int scoringAllow = ReflectionTestUtils.invokeMethod(
                service, "checkScoringViewTopCounts", scoringChecks, List.of(scoring), Set.of(), mismatches
        );
        assertEquals(0, scoringAllow);

        JsonNode ifMissingChecks = mapper.readTree("[{\"journalId\":\"j1\",\"year\":2024,\"edition\":\"SCIE\",\"expectedMissing\":true}]");
        int ifMissingAllow = ReflectionTestUtils.invokeMethod(
                service, "checkIfMissing", ifMissingChecks, List.of(metric), List.of(category), Set.of("ifMissing[0]"), mismatches
        );
        assertEquals(0, ifMissingAllow);

        WosImportEvent e1 = new WosImportEvent();
        e1.setSourceType(WosSourceType.GOV_AIS_RIS);
        e1.setSourceFile("f");
        e1.setSourceVersion("v");
        e1.setSourceRowItem("1");
        WosImportEvent e2 = new WosImportEvent();
        e2.setSourceType(WosSourceType.GOV_AIS_RIS);
        e2.setSourceFile("f");
        e2.setSourceVersion("v");
        e2.setSourceRowItem("1");
        when(importEventRepository.findAll()).thenReturn(List.of(e1, e2));

        WosMetricFact metricDup = new WosMetricFact();
        metricDup.setJournalId("j1");
        metricDup.setYear(2024);
        metricDup.setMetricType(MetricType.AIS);
        WosCategoryFact categoryDup = new WosCategoryFact();
        categoryDup.setJournalId("j1");
        categoryDup.setYear(2024);
        categoryDup.setCategoryNameCanonical("Cat");
        categoryDup.setEditionNormalized(EditionNormalized.SCIE);
        categoryDup.setMetricType(MetricType.AIS);
        JsonNode replayChecks = mapper.readTree("{\"expectedDuplicateImportEventKeys\":1,\"expectedDuplicateMetricFactKeys\":1,\"expectedDuplicateCategoryFactKeys\":1}");
        int replayAllow = ReflectionTestUtils.invokeMethod(
                service, "checkReplayDeterminism", replayChecks, List.of(metric, metricDup), List.of(category, categoryDup), Set.of(), mismatches
        );
        assertEquals(0, replayAllow);

        int mismatchIntAllow = ReflectionTestUtils.invokeMethod(
                service, "mismatchInt", "k", 1, 2, Set.of("k"), mismatches
        );
        assertEquals(1, mismatchIntAllow);

        List<String> capped = ReflectionTestUtils.invokeMethod(
                service, "capAndSort", Arrays.asList("z", " ", null, "a")
        );
        assertEquals(List.of("a", "z"), capped);

        String normEdition = ReflectionTestUtils.invokeMethod(service, "normalizeEditionName", EditionNormalized.SSCI);
        assertEquals("SSCI", normEdition);
        String normEditionUnknown = ReflectionTestUtils.invokeMethod(service, "normalizeEditionName", (EditionNormalized) null);
        assertEquals("UNKNOWN", normEditionUnknown);

        JsonNode obj = mapper.readTree("{\"t\":\"  x  \",\"i\":7,\"b\":true}");
        assertEquals("x", ReflectionTestUtils.invokeMethod(service, "text", obj, "t"));
        assertEquals(Integer.valueOf(7), ReflectionTestUtils.invokeMethod(service, "intOrNull", obj, "i"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "boolOrNull", obj, "b"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "parseYear", "bad"));
        assertEquals(Integer.valueOf(2024), ReflectionTestUtils.invokeMethod(service, "parseYear", "2024"));
        assertEquals(MetricType.AIS, ReflectionTestUtils.invokeMethod(service, "metricTypeOrNull", "ais"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "metricTypeOrNull", "nope"));
        assertEquals(EditionNormalized.SCIE, ReflectionTestUtils.invokeMethod(service, "editionOrNull", "scie"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "editionOrNull", "unknown!"));

        assertTrue(mismatches.stream().noneMatch(s -> s.startsWith("counts.importEvents")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eligibilitySuccessAndRunFullParityExecutesRowMapperPath() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );

        Path baseline = Files.createTempFile("wos-parity-", ".json");
        String json = """
                {
                  "allowlistedMismatches": ["counts.importEvents"],
                  "counts": {"importEvents": 1, "metricFacts": 1, "categoryFacts": 1, "rankingView": 1, "scoringView": 1},
                  "editionCounts": {"SCIE": 1},
                  "scienceToScieExpectedCount": 1,
                  "bundledSplitChecks": [{"sourceEventId":"ev1","year":2023,"categoryNameCanonical":"Cat","metricType":"AIS","expectedEditionCount":1}],
                  "timelineChecks": [{"journalId":"j1","metricType":"AIS","edition":"SCIE","expected":{"2023":1.5}}],
                  "categoryChecks": [{"journalId":"j1","categoryNameCanonical":"Cat","metricType":"AIS","edition":"SCIE","expected":{"2023":{"quarter":"Q1","rank":1}}}],
                  "scoringChecks": [{"categoryNameCanonical":"Cat","edition":"SCIE","metricType":"AIS","quarter":"Q1","year":2023,"expectedTopCount":1}],
                  "ifMissingChecks": [{"journalId":"j1","year":2023,"edition":"SCIE","expectedMissing":true}],
                  "replayChecks": {"expectedDuplicateImportEventKeys":0,"expectedDuplicateMetricFactKeys":0,"expectedDuplicateCategoryFactKeys":0}
                }
                """;
        Files.writeString(baseline, json, StandardCharsets.UTF_8);
        ReflectionTestUtils.setField(service, "baselineLocation", baseline.toUri().toString());

        when(importEventRepository.count()).thenReturn(1L);
        when(metricFactRepository.count()).thenReturn(1L);
        when(categoryFactRepository.count()).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("j1");
        metricFact.setYear(2023);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(1.5);
        when(metricFactRepository.findAll()).thenReturn(List.of(metricFact));

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setJournalId("j1");
        categoryFact.setYear(2023);
        categoryFact.setCategoryNameCanonical("Cat");
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setEditionRaw("SCIENCE");
        categoryFact.setQuarter("Q1");
        categoryFact.setRank(1);
        categoryFact.setSourceEventId("ev1");
        when(categoryFactRepository.findAll()).thenReturn(List.of(categoryFact));
        when(importEventRepository.findAll()).thenReturn(List.of());

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("s1");
        when(rs.getString("journal_id")).thenReturn("j1");
        when(rs.getObject("year", Integer.class)).thenReturn(2023);
        when(rs.getString("category_name_canonical")).thenReturn("Cat");
        when(rs.getString("edition_normalized")).thenReturn("SCIE");
        when(rs.getString("metric_type")).thenReturn("AIS");
        when(rs.getObject("value", Double.class)).thenReturn(1.5);
        when(rs.getString("quarter")).thenReturn("Q1");
        when(rs.getObject("quartile_rank", Integer.class)).thenReturn(1);
        when(rs.getObject("rank", Integer.class)).thenReturn(1);
        when(rs.getString("build_version")).thenReturn("v");
        when(rs.getTimestamp("build_at")).thenReturn(null);
        when(rs.getTimestamp("updated_at")).thenReturn(null);

        ResultSet rsDecoy = mock(ResultSet.class);
        when(rsDecoy.getString("id")).thenReturn("s2");
        when(rsDecoy.getString("journal_id")).thenReturn("j2");
        when(rsDecoy.getObject("year", Integer.class)).thenReturn(2022);
        when(rsDecoy.getString("category_name_canonical")).thenReturn("Other");
        when(rsDecoy.getString("edition_normalized")).thenReturn("ESCI");
        when(rsDecoy.getString("metric_type")).thenReturn("IF");
        when(rsDecoy.getObject("value", Double.class)).thenReturn(3.0);
        when(rsDecoy.getString("quarter")).thenReturn("Q2");
        when(rsDecoy.getObject("quartile_rank", Integer.class)).thenReturn(2);
        when(rsDecoy.getObject("rank", Integer.class)).thenReturn(2);
        when(rsDecoy.getString("build_version")).thenReturn("v");
        when(rsDecoy.getTimestamp("build_at")).thenReturn(null);
        when(rsDecoy.getTimestamp("updated_at")).thenReturn(null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<WosScoringView> mapperFn = inv.getArgument(1);
                    return List.of(mapperFn.mapRow(rs, 0), mapperFn.mapRow(rsDecoy, 1));
                });

        var eligibility = service.runEligibilityCheck();
        assertTrue(eligibility.baselineAvailable());
        assertTrue(eligibility.passed());

        var parity = service.runFullParity();
        assertTrue(parity.baselineAvailable());
        assertTrue(parity.passed());
        assertEquals(0, parity.mismatchCount());
        assertTrue(parity.allowlistedMismatchCount() >= 0);
        assertTrue(parity.executedChecks().contains("scores"));
    }

    @Test
    void helperChecksCoverMalformedAndMismatchBranches() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        List<String> mismatches = new ArrayList<>();

        WosCategoryFact c = new WosCategoryFact();
        c.setSourceEventId("evx");
        c.setYear(2020);
        c.setCategoryNameCanonical("CatX");
        c.setMetricType(MetricType.AIS);
        c.setEditionNormalized(EditionNormalized.ESCI);
        c.setEditionRaw("SOCIAL SCIENCE");
        c.setJournalId("jx");
        c.setQuarter("Q4");
        c.setRank(9);

        WosMetricFact m = new WosMetricFact();
        m.setJournalId("jx");
        m.setYear(2020);
        m.setMetricType(MetricType.IF);
        m.setValue(null);

        JsonNode badEditionCounts = mapper.readTree("{\"SCIE\":\"x\"}");
        int x1 = ReflectionTestUtils.invokeMethod(service, "checkEditionCounts", badEditionCounts, List.of(c), Set.of(), mismatches);
        assertEquals(0, x1);

        int x2 = ReflectionTestUtils.invokeMethod(service, "checkScienceToScieCount", mapper.readTree("2"), List.of(c), Set.of(), mismatches);
        assertEquals(0, x2);
        assertTrue(mismatches.stream().anyMatch(s -> s.startsWith("editionNormalization.scienceToScie")));

        JsonNode bundledMismatch = mapper.readTree("[{\"sourceEventId\":\"evx\",\"year\":2020,\"categoryNameCanonical\":\"CatX\",\"metricType\":\"AIS\",\"expectedEditionCount\":2}]");
        int x3 = ReflectionTestUtils.invokeMethod(service, "checkBundledSplit", bundledMismatch, List.of(c), Set.of(), mismatches);
        assertEquals(0, x3);

        JsonNode metricMalformed = mapper.readTree("[{\"journalId\":\"jx\",\"metricType\":\"IF\",\"edition\":\"SCIE\",\"expected\":{\"bad\":\"x\",\"2020\":1.0}}]");
        int x4 = ReflectionTestUtils.invokeMethod(service, "checkMetricTimelines", metricMalformed, List.of(m), Set.of(), mismatches);
        assertEquals(0, x4);

        JsonNode categoryMalformed = mapper.readTree("[{\"journalId\":\"jx\",\"categoryNameCanonical\":\"CatX\",\"metricType\":\"AIS\",\"edition\":\"ESCI\",\"expected\":{\"2020\":{\"quarter\":\"Q1\",\"rank\":1}}}]");
        int x5 = ReflectionTestUtils.invokeMethod(service, "checkCategoryTimelines", categoryMalformed, List.of(c), Set.of(), mismatches);
        assertEquals(0, x5);

        WosScoringView scoring = new WosScoringView();
        scoring.setJournalId("jx");
        scoring.setCategoryNameCanonical("CatX");
        scoring.setEditionNormalized(EditionNormalized.ESCI);
        scoring.setMetricType(MetricType.AIS);
        scoring.setYear(2020);
        scoring.setQuarter("Q4");
        JsonNode scoringMismatch = mapper.readTree("[{\"categoryNameCanonical\":\"CatX\",\"edition\":\"ESCI\",\"metricType\":\"AIS\",\"quarter\":\"Q4\",\"year\":2020,\"expectedTopCount\":2}]");
        int x6 = ReflectionTestUtils.invokeMethod(service, "checkScoringViewTopCounts", scoringMismatch, List.of(scoring), Set.of(), mismatches);
        assertEquals(0, x6);

        JsonNode ifMissingFalse = mapper.readTree("[{\"journalId\":\"jx\",\"year\":2020,\"edition\":\"ESCI\",\"expectedMissing\":false}]");
        int x7 = ReflectionTestUtils.invokeMethod(service, "checkIfMissing", ifMissingFalse, List.of(m), List.of(c), Set.of(), mismatches);
        assertEquals(0, x7);

        JsonNode obj = mapper.readTree("{\"i\":\"notInt\",\"b\":\"notBool\",\"t\":123}");
        assertNull(ReflectionTestUtils.invokeMethod(service, "intOrNull", obj, "i"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "boolOrNull", obj, "b"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "text", obj, "t"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "parseYear", "  "));
    }

    @Test
    void loadBaselineParsesAllowlistAndSkipsBlankEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );

        Path baseline = Files.createTempFile("wos-parity-load-", ".json");
        Files.writeString(
                baseline,
                "{\"allowlistedMismatches\":[\"a\",\"  \",\"b\",null],\"counts\":{\"importEvents\":1}}",
                StandardCharsets.UTF_8
        );
        ReflectionTestUtils.setField(service, "baselineLocation", baseline.toUri().toString());

        Object loaded = ReflectionTestUtils.invokeMethod(service, "loadBaseline");
        @SuppressWarnings("unchecked")
        Set<String> allowlisted = (Set<String>) ReflectionTestUtils.invokeMethod(loaded, "allowlistedMismatches");
        assertEquals(Set.of("a", "b"), allowlisted);
        Object root = ReflectionTestUtils.invokeMethod(loaded, "root");
        assertTrue(root instanceof JsonNode);
    }

    @Test
    void helperAllowlistVsNonAllowlistPairsAndMalformedPlusValidEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        List<String> mismatches = new ArrayList<>();

        WosCategoryFact category = new WosCategoryFact();
        category.setSourceEventId("evA");
        category.setJournalId("jA");
        category.setYear(2025);
        category.setCategoryNameCanonical("CatA");
        category.setMetricType(MetricType.AIS);
        category.setEditionNormalized(EditionNormalized.SCIE);
        category.setQuarter("Q4");
        category.setRank(9);

        WosMetricFact metric = new WosMetricFact();
        metric.setJournalId("jA");
        metric.setYear(2025);
        metric.setMetricType(MetricType.AIS);
        metric.setValue(1.0);

        WosScoringView scoring = new WosScoringView();
        scoring.setJournalId("jA");
        scoring.setCategoryNameCanonical("CatA");
        scoring.setEditionNormalized(EditionNormalized.SCIE);
        scoring.setMetricType(MetricType.AIS);
        scoring.setYear(2025);
        scoring.setQuarter("Q4");

        JsonNode bundled = mapper.readTree("""
                [
                  {"sourceEventId":"evA","year":2025,"categoryNameCanonical":"CatA","metricType":"AIS","expectedEditionCount":2},
                  {"sourceEventId":"evA","year":2025,"categoryNameCanonical":"CatA","metricType":"AIS","expectedEditionCount":"bad"},
                  {"sourceEventId":"evA","year":2025,"categoryNameCanonical":"CatA","metricType":"AIS","expectedEditionCount":1}
                ]
                """);
        int bundledAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkBundledSplit", bundled, List.of(category), Set.of("bundledSplit[0]"), mismatches
        );
        assertEquals(1, bundledAllowlisted);
        assertTrue(mismatches.stream().noneMatch(s -> s.startsWith("bundledSplit[0]")));
        mismatches.clear();
        int bundledNotAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkBundledSplit", bundled, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, bundledNotAllowlisted);
        assertTrue(mismatches.stream().anyMatch(s -> s.startsWith("bundledSplit[0]")));

        JsonNode metricTimeline = mapper.readTree("""
                [
                  {"journalId":"jA","metricType":"AIS","edition":"SCIE","expected":{"2025":2.0,"bad":"x"}},
                  {"journalId":"jA","metricType":"AIS","expected":{"2025":1.0}},
                  {"journalId":"jA","metricType":"AIS","edition":"SCIE","expected":{"2025":1.0}}
                ]
                """);
        mismatches.clear();
        int metricAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkMetricTimelines", metricTimeline, List.of(metric), Set.of("timeline[0].2025"), mismatches
        );
        assertEquals(1, metricAllowlisted);
        assertTrue(mismatches.stream().noneMatch(s -> s.startsWith("timeline[0].2025")));
        mismatches.clear();
        int metricNotAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkMetricTimelines", metricTimeline, List.of(metric), Set.of(), mismatches
        );
        assertEquals(0, metricNotAllowlisted);
        assertTrue(mismatches.stream().anyMatch(s -> s.startsWith("timeline[0].2025")));

        JsonNode categoryTimeline = mapper.readTree("""
                [
                  {"journalId":"jA","categoryNameCanonical":"CatA","metricType":"AIS","edition":"SCIE","expected":{"2025":{"quarter":"Q1","rank":1}}},
                  {"journalId":"jA","categoryNameCanonical":"CatA","metricType":"AIS","edition":"SCIE","expected":{"bad":{"quarter":"Q1","rank":1}}},
                  {"journalId":"jA","categoryNameCanonical":"CatA","metricType":"AIS","edition":"SCIE","expected":{"2025":{"quarter":"Q4","rank":9}}}
                ]
                """);
        mismatches.clear();
        int categoryAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkCategoryTimelines", categoryTimeline, List.of(category), Set.of("category[0].2025"), mismatches
        );
        assertEquals(1, categoryAllowlisted);
        assertTrue(mismatches.stream().noneMatch(s -> s.startsWith("category[0].2025")));
        mismatches.clear();
        int categoryNotAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkCategoryTimelines", categoryTimeline, List.of(category), Set.of(), mismatches
        );
        assertEquals(0, categoryNotAllowlisted);
        assertTrue(mismatches.stream().anyMatch(s -> s.startsWith("category[0].2025")));

        JsonNode scoringChecks = mapper.readTree("""
                [
                  {"categoryNameCanonical":"CatA","edition":"SCIE","metricType":"AIS","quarter":"Q4","year":2025,"expectedTopCount":2},
                  {"categoryNameCanonical":"CatA","edition":"SCIE","metricType":"AIS","quarter":"Q4","expectedTopCount":1},
                  {"categoryNameCanonical":"CatA","edition":"SCIE","metricType":"AIS","quarter":"Q4","year":2025,"expectedTopCount":1}
                ]
                """);
        mismatches.clear();
        int scoringAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkScoringViewTopCounts", scoringChecks, List.of(scoring), Set.of("scoring[0]"), mismatches
        );
        assertEquals(1, scoringAllowlisted);
        assertTrue(mismatches.stream().noneMatch(s -> s.startsWith("scoring[0]")));
        mismatches.clear();
        int scoringNotAllowlisted = ReflectionTestUtils.invokeMethod(
                service, "checkScoringViewTopCounts", scoringChecks, List.of(scoring), Set.of(), mismatches
        );
        assertEquals(0, scoringNotAllowlisted);
        assertTrue(mismatches.stream().anyMatch(s -> s.startsWith("scoring[0]")));
    }

    @Test
    void fullParityMismatchPayloadContainsExpectedKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        Path baseline = Files.createTempFile("wos-parity-mismatch-", ".json");
        String json = """
                {
                  "counts": {"importEvents": 1, "metricFacts": 1, "categoryFacts": 1, "rankingView": 1, "scoringView": 1},
                  "editionCounts": {"SCIE": 1},
                  "scienceToScieExpectedCount": 1,
                  "bundledSplitChecks": [{"sourceEventId":"ev1","year":2023,"categoryNameCanonical":"Cat","metricType":"AIS","expectedEditionCount":1}],
                  "timelineChecks": [{"journalId":"j1","metricType":"AIS","edition":"SCIE","expected":{"2023":1.5}}],
                  "categoryChecks": [{"journalId":"j1","categoryNameCanonical":"Cat","metricType":"AIS","edition":"SCIE","expected":{"2023":{"quarter":"Q1","rank":1}}}],
                  "scoringChecks": [{"categoryNameCanonical":"Cat","edition":"SCIE","metricType":"AIS","quarter":"Q1","year":2023,"expectedTopCount":2}],
                  "ifMissingChecks": [{"journalId":"j1","year":2023,"edition":"SCIE","expectedMissing":false}],
                  "replayChecks": {"expectedDuplicateImportEventKeys":0,"expectedDuplicateMetricFactKeys":0,"expectedDuplicateCategoryFactKeys":0}
                }
                """;
        Files.writeString(baseline, json, StandardCharsets.UTF_8);
        ReflectionTestUtils.setField(service, "baselineLocation", baseline.toUri().toString());

        when(importEventRepository.count()).thenReturn(1L);
        when(metricFactRepository.count()).thenReturn(1L);
        when(categoryFactRepository.count()).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(importEventRepository.findAll()).thenReturn(List.of());

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("j1");
        metricFact.setYear(2023);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setValue(1.5);
        when(metricFactRepository.findAll()).thenReturn(List.of(metricFact));

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setJournalId("j1");
        categoryFact.setYear(2023);
        categoryFact.setCategoryNameCanonical("Cat");
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setEditionRaw("SCIENCE");
        categoryFact.setQuarter("Q1");
        categoryFact.setRank(1);
        categoryFact.setSourceEventId("ev1");
        when(categoryFactRepository.findAll()).thenReturn(List.of(categoryFact));

        WosScoringView scoring = new WosScoringView();
        scoring.setJournalId("j1");
        scoring.setCategoryNameCanonical("Cat");
        scoring.setEditionNormalized(EditionNormalized.SCIE);
        scoring.setMetricType(MetricType.AIS);
        scoring.setYear(2023);
        scoring.setQuarter("Q1");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(scoring));

        var result = service.runFullParity();
        assertFalse(result.passed());
        assertTrue(result.mismatches().stream().anyMatch(s -> s.startsWith("scoring[0]")));
        assertTrue(result.mismatches().stream().anyMatch(s -> s.startsWith("ifMissing[0]")));
    }

    @Test
    void helperEdgeGuardsAndAllowlistIncrementsAreExplicitlyCovered() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                mapper,
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                jdbcTemplate
        );
        List<String> mismatches = new ArrayList<>();

        WosCategoryFact c = new WosCategoryFact();
        c.setJournalId("jE");
        c.setYear(2024);
        c.setMetricType(MetricType.IF);
        c.setEditionNormalized(EditionNormalized.ESCI);

        JsonNode editionCounts = mapper.readTree("{\"ESCI\":2}");
        int editionAllow = ReflectionTestUtils.invokeMethod(
                service, "checkEditionCounts", editionCounts, List.of(c), Set.of("editionCounts.ESCI"), mismatches
        );
        assertEquals(1, editionAllow);
        assertTrue(mismatches.isEmpty());

        WosMetricFact ifMetric = new WosMetricFact();
        ifMetric.setJournalId("jE");
        ifMetric.setYear(2024);
        ifMetric.setMetricType(MetricType.IF);
        ifMetric.setValue(null);
        JsonNode ifMissing = mapper.readTree("[{\"journalId\":\"jE\",\"year\":2024,\"edition\":\"ESCI\",\"expectedMissing\":false}]");
        int ifAllow = ReflectionTestUtils.invokeMethod(
                service, "checkIfMissing", ifMissing, List.of(ifMetric), List.of(c), Set.of("ifMissing[0]"), mismatches
        );
        assertEquals(1, ifAllow);
        assertTrue(mismatches.isEmpty());

        JsonNode blankText = mapper.readTree("{\"t\":\"   \"}");
        assertNull(ReflectionTestUtils.invokeMethod(service, "text", blankText, "t"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "parseYear", (String) null));
        assertNull(ReflectionTestUtils.invokeMethod(service, "metricTypeOrNull", "   "));
    }
}
