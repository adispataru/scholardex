package ro.uvt.pokedex.core.service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
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
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.reporting.WosScoringViewRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosParityReconciliationServiceTest {

    @Mock private WosImportEventRepository importEventRepository;
    @Mock private WosMetricFactRepository metricFactRepository;
    @Mock private WosCategoryFactRepository categoryFactRepository;
    @Mock private WosRankingViewRepository rankingViewRepository;
    @Mock private WosScoringViewRepository scoringViewRepository;

    @Test
    void fullParityPassesWhenBaselineMatches() {
        WosParityReconciliationService service = new WosParityReconciliationService(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                importEventRepository,
                metricFactRepository,
                categoryFactRepository,
                rankingViewRepository,
                scoringViewRepository
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

        WosScoringView scoringView = new WosScoringView();
        scoringView.setJournalId("j1");
        scoringView.setYear(2023);
        scoringView.setCategoryNameCanonical("Computer Science, Theory & Methods");
        scoringView.setMetricType(MetricType.AIS);
        scoringView.setEditionNormalized(EditionNormalized.SCIE);
        scoringView.setQuarter("Q1");

        when(importEventRepository.count()).thenReturn(1L);
        WosImportEvent importEvent = new WosImportEvent();
        importEvent.setSourceType(WosSourceType.GOV_AIS_RIS);
        importEvent.setSourceFile("AIS_2023.xlsx");
        importEvent.setSourceVersion("v2023");
        importEvent.setSourceRowItem("1");
        when(importEventRepository.findAll()).thenReturn(List.of(importEvent));
        when(metricFactRepository.count()).thenReturn(1L);
        when(categoryFactRepository.count()).thenReturn(1L);
        when(rankingViewRepository.count()).thenReturn(1L);
        when(scoringViewRepository.count()).thenReturn(1L);
        when(metricFactRepository.findAll()).thenReturn(List.of(metricFact));
        when(categoryFactRepository.findAll()).thenReturn(List.of(categoryFact));
        when(scoringViewRepository.findAll()).thenReturn(List.of(scoringView));

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
                rankingViewRepository,
                scoringViewRepository
        );
        ReflectionTestUtils.setField(service, "baselineLocation", "classpath:wos/parity/baseline-test-fail.json");

        when(importEventRepository.count()).thenReturn(0L);
        when(importEventRepository.findAll()).thenReturn(List.of());
        when(metricFactRepository.count()).thenReturn(0L);
        when(categoryFactRepository.count()).thenReturn(0L);
        when(rankingViewRepository.count()).thenReturn(0L);
        when(scoringViewRepository.count()).thenReturn(0L);
        when(metricFactRepository.findAll()).thenReturn(List.of());
        when(categoryFactRepository.findAll()).thenReturn(List.of());
        when(scoringViewRepository.findAll()).thenReturn(List.of());

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
                rankingViewRepository,
                scoringViewRepository
        );
        ReflectionTestUtils.setField(service, "baselineLocation", "classpath:wos/parity/does-not-exist.json");

        var result = service.runEligibilityCheck();

        assertFalse(result.baselineAvailable());
        assertFalse(result.passed());
        assertTrue(result.mismatchCount() > 0);
    }
}
