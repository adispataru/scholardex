package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIndividualReportRunServiceTest {

    @Mock
    private UserIndividualReportRunRepository runRepository;
    @Mock
    private IndividualReportRepository reportRepository;
    @Mock
    private UserService userService;
    @Mock
    private UserIndicatorResultService indicatorResultService;

    private UserIndividualReportRunService service;

    @BeforeEach
    void setUp() {
        service = new UserIndividualReportRunService(runRepository, reportRepository, userService, indicatorResultService);
    }

    @Test
    void getOrCreateLatestRunReturnsPersistedRunWhenPresent() {
        UserIndividualReportRun persisted = new UserIndividualReportRun();
        persisted.setId("run-1");
        persisted.setReportDefinitionId("rep-1");
        persisted.setCreatedAt(Instant.now());

        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(persisted));

        Optional<IndividualReportRunDto> dto = service.getOrCreateLatestRun("u@uvt.ro", "rep-1");

        assertTrue(dto.isPresent());
        assertEquals("run-1", dto.get().runId());
        assertEquals(IndividualReportRunDto.Source.PERSISTED, dto.get().source());
    }

    @Test
    void getOrCreateLatestRunBuildsRunWhenMissing() {
        when(runRepository.findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.empty());

        Indicator indicator = new Indicator();
        indicator.setId("ind-1");

        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setIndicatorIndices(List.of(0));

        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of(criterion));

        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        User user = new User();
        user.setResearcherId("r-1");
        when(userService.getUserByEmail("u@uvt.ro")).thenReturn(Optional.of(user));

        when(indicatorResultService.getOrCreateLatest("u@uvt.ro", "ind-1"))
                .thenReturn(new IndicatorApplyResultDto(
                        "res-1",
                        "ind-1",
                        "user/indicators-apply-publications",
                        Map.of(),
                        new IndicatorApplyResultDto.Summary(5.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        Instant.now(),
                        Instant.now(),
                        0
                ));

        UserIndicatorResult snapshot = new UserIndicatorResult();
        snapshot.setId("snap-1");
        when(indicatorResultService.createSnapshotFromLatest("u@uvt.ro", "ind-1", "rep-1")).thenReturn(snapshot);

        when(runRepository.save(any(UserIndividualReportRun.class))).thenAnswer(invocation -> {
            UserIndividualReportRun run = invocation.getArgument(0);
            run.setId("run-2");
            return run;
        });

        Optional<IndividualReportRunDto> dto = service.getOrCreateLatestRun("u@uvt.ro", "rep-1");

        assertTrue(dto.isPresent());
        assertEquals("run-2", dto.get().runId());
        assertEquals(5.0, dto.get().criteriaScores().get(0));
    }

    @Test
    void refreshRunWithAllIndicatorsRecomputesReportIndicatorsAndPersistsRun() {
        Indicator indicator1 = new Indicator();
        indicator1.setId("ind-1");
        Indicator indicator2 = new Indicator();
        indicator2.setId("ind-2");

        AbstractReport.Criterion criterion = new AbstractReport.Criterion();
        criterion.setIndicatorIndices(List.of(0, 1));

        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setIndicators(List.of(indicator1, indicator2));
        report.setCriteria(List.of(criterion));

        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        User user = new User();
        user.setResearcherId("r-1");
        when(userService.getUserByEmail("u@uvt.ro")).thenReturn(Optional.of(user));

        when(indicatorResultService.refreshLatest("u@uvt.ro", "ind-1"))
                .thenReturn(new IndicatorApplyResultDto(
                        "latest-1",
                        "ind-1",
                        "user/indicators-apply-publications",
                        Map.of(),
                        new IndicatorApplyResultDto.Summary(7.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.COMPUTED,
                        Instant.now(),
                        Instant.now(),
                        1
                ));
        when(indicatorResultService.refreshLatest("u@uvt.ro", "ind-2"))
                .thenReturn(new IndicatorApplyResultDto(
                        "latest-2",
                        "ind-2",
                        "user/indicators-apply-publications",
                        Map.of(),
                        new IndicatorApplyResultDto.Summary(3.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.COMPUTED,
                        Instant.now(),
                        Instant.now(),
                        1
                ));

        when(indicatorResultService.getOrCreateLatest("u@uvt.ro", "ind-1"))
                .thenReturn(new IndicatorApplyResultDto(
                        "latest-1",
                        "ind-1",
                        "user/indicators-apply-publications",
                        Map.of(),
                        new IndicatorApplyResultDto.Summary(7.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        Instant.now(),
                        Instant.now(),
                        1
                ));
        when(indicatorResultService.getOrCreateLatest("u@uvt.ro", "ind-2"))
                .thenReturn(new IndicatorApplyResultDto(
                        "latest-2",
                        "ind-2",
                        "user/indicators-apply-publications",
                        Map.of(),
                        new IndicatorApplyResultDto.Summary(3.0, null, List.of(), List.of()),
                        IndicatorApplyResultDto.Source.PERSISTED,
                        Instant.now(),
                        Instant.now(),
                        1
                ));

        UserIndicatorResult snapshot1 = new UserIndicatorResult();
        snapshot1.setId("snap-1");
        UserIndicatorResult snapshot2 = new UserIndicatorResult();
        snapshot2.setId("snap-2");
        when(indicatorResultService.createSnapshotFromLatest("u@uvt.ro", "ind-1", "rep-1")).thenReturn(snapshot1);
        when(indicatorResultService.createSnapshotFromLatest("u@uvt.ro", "ind-2", "rep-1")).thenReturn(snapshot2);

        when(runRepository.save(any(UserIndividualReportRun.class))).thenAnswer(invocation -> {
            UserIndividualReportRun run = invocation.getArgument(0);
            run.setId("run-3");
            return run;
        });

        Optional<IndividualReportRunDto> dto = service.refreshRunWithAllIndicators("u@uvt.ro", "rep-1");

        assertTrue(dto.isPresent());
        assertEquals("run-3", dto.get().runId());
        assertEquals(10.0, dto.get().criteriaScores().get(0));
        verify(indicatorResultService).refreshLatest("u@uvt.ro", "ind-1");
        verify(indicatorResultService).refreshLatest("u@uvt.ro", "ind-2");
    }

    @Test
    void refreshRunWithAllIndicatorsReturnsEmptyWhenReportMissing() {
        when(reportRepository.findById("rep-missing")).thenReturn(Optional.empty());

        Optional<IndividualReportRunDto> dto = service.refreshRunWithAllIndicators("u@uvt.ro", "rep-missing");

        assertTrue(dto.isEmpty());
        verify(indicatorResultService, never()).refreshLatest(any(), any());
        verify(runRepository, never()).save(any(UserIndividualReportRun.class));
    }
}
