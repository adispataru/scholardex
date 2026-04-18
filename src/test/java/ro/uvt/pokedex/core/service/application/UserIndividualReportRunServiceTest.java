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
import ro.uvt.pokedex.core.service.application.model.ReportScopedIndividualReportComputation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private UserReportFacade userReportFacade;

    private UserIndividualReportRunService service;

    @BeforeEach
    void setUp() {
        service = new UserIndividualReportRunService(runRepository, reportRepository, userService, indicatorResultService, userReportFacade);
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

        when(userReportFacade.computeReportScopedIndividualReport("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(new ReportScopedIndividualReportComputation(
                        Map.of(indicator, 5.0),
                        Map.of("ind-1", 5.0),
                        Map.of(0, 5.0),
                        Map.of("ind-1", new IndicatorApplyResultDto(
                                null,
                                "ind-1",
                                "user/indicators-apply-publications",
                                Map.of("total", "5.00"),
                                new IndicatorApplyResultDto.Summary(5.0, null, List.of(), List.of()),
                                IndicatorApplyResultDto.Source.COMPUTED,
                                null,
                                null,
                                0
                        ))
                )));

        UserIndicatorResult snapshot = new UserIndicatorResult();
        snapshot.setId("snap-1");
        when(indicatorResultService.createSnapshotFromComputed(eq("u@uvt.ro"), eq("ind-1"), eq("rep-1"), any(), eq(0)))
                .thenReturn(snapshot);

        when(runRepository.save(any(UserIndividualReportRun.class))).thenAnswer(invocation -> {
            UserIndividualReportRun run = invocation.getArgument(0);
            run.setId("run-2");
            return run;
        });

        Optional<IndividualReportRunDto> dto = service.getOrCreateLatestRun("u@uvt.ro", "rep-1");

        assertTrue(dto.isPresent());
        assertEquals("run-2", dto.get().runId());
        assertEquals(5.0, dto.get().criteriaScores().get(0));
        verify(indicatorResultService, never()).getOrCreateLatest(any(), any());
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

        when(indicatorResultService.getLatestRefreshVersion("u@uvt.ro", "ind-1")).thenReturn(1);
        when(indicatorResultService.getLatestRefreshVersion("u@uvt.ro", "ind-2")).thenReturn(1);

        when(userReportFacade.computeReportScopedIndividualReport("u@uvt.ro", "rep-1"))
                .thenReturn(Optional.of(new ReportScopedIndividualReportComputation(
                        Map.of(indicator1, 10.0, indicator2, 1.0),
                        Map.of("ind-1", 10.0, "ind-2", 1.0),
                        Map.of(0, 11.0),
                        Map.of(
                                "ind-1", new IndicatorApplyResultDto(
                                        null,
                                        "ind-1",
                                        "user/indicators-apply-publications",
                                        Map.of("total", "10.00"),
                                        new IndicatorApplyResultDto.Summary(10.0, null, List.of(), List.of()),
                                        IndicatorApplyResultDto.Source.COMPUTED,
                                        null,
                                        null,
                                        0
                                ),
                                "ind-2", new IndicatorApplyResultDto(
                                        null,
                                        "ind-2",
                                        "user/indicators-apply-publications",
                                        Map.of("total", "1.00"),
                                        new IndicatorApplyResultDto.Summary(1.0, null, List.of(), List.of()),
                                        IndicatorApplyResultDto.Source.COMPUTED,
                                        null,
                                        null,
                                        0
                                )
                        )
                )));

        UserIndicatorResult snapshot1 = new UserIndicatorResult();
        snapshot1.setId("snap-1");
        UserIndicatorResult snapshot2 = new UserIndicatorResult();
        snapshot2.setId("snap-2");
        when(indicatorResultService.createSnapshotFromComputed(eq("u@uvt.ro"), eq("ind-1"), eq("rep-1"), any(), eq(1))).thenReturn(snapshot1);
        when(indicatorResultService.createSnapshotFromComputed(eq("u@uvt.ro"), eq("ind-2"), eq("rep-1"), any(), eq(1))).thenReturn(snapshot2);

        when(runRepository.save(any(UserIndividualReportRun.class))).thenAnswer(invocation -> {
            UserIndividualReportRun run = invocation.getArgument(0);
            run.setId("run-3");
            return run;
        });

        Optional<IndividualReportRunDto> dto = service.refreshRunWithAllIndicators("u@uvt.ro", "rep-1");

        assertTrue(dto.isPresent());
        assertEquals("run-3", dto.get().runId());
        assertEquals(11.0, dto.get().criteriaScores().get(0));
        assertEquals(10.0, dto.get().indicatorScoresByIndicatorId().get("ind-1"));
        assertEquals(1.0, dto.get().indicatorScoresByIndicatorId().get("ind-2"));
        verify(indicatorResultService).getLatestRefreshVersion("u@uvt.ro", "ind-1");
        verify(indicatorResultService).getLatestRefreshVersion("u@uvt.ro", "ind-2");
        verify(indicatorResultService, never()).getOrCreateLatest(any(), any());
    }

    @Test
    void refreshRunWithAllIndicatorsReturnsEmptyWhenReportMissing() {
        when(reportRepository.findById("rep-missing")).thenReturn(Optional.empty());

        Optional<IndividualReportRunDto> dto = service.refreshRunWithAllIndicators("u@uvt.ro", "rep-missing");

        assertTrue(dto.isEmpty());
        verify(indicatorResultService, never()).refreshLatest(any(), any());
        verify(runRepository, never()).save(any(UserIndividualReportRun.class));
    }

    @Test
    void invalidateLatestRunsDeletesTransientRunsForUser() {
        when(runRepository.deleteByUserEmail("u@uvt.ro")).thenReturn(2L);

        long deleted = service.invalidateLatestRuns("u@uvt.ro");

        assertEquals(2L, deleted);
        verify(runRepository).deleteByUserEmail("u@uvt.ro");
        verify(userReportFacade, never()).computeReportScopedIndividualReport(any(), any());
    }
}
