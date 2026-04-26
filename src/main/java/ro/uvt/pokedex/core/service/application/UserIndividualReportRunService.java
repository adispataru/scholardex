package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.application.model.ReportScopedIndividualReportComputation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserIndividualReportRunService {

    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final IndividualReportRepository individualReportRepository;
    private final UserService userService;
    private final UserIndicatorResultService userIndicatorResultService;
    private final UserReportFacade userReportFacade;

    public Optional<IndividualReportRunDto> getOrCreateLatestRun(String userEmail, String reportDefinitionId) {
        Optional<UserIndividualReportRun> existing = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportDefinitionId);
        if (existing.isPresent()) {
            return Optional.of(toDto(existing.get(), IndividualReportRunDto.Source.PERSISTED));
        }
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of());
    }

    public Optional<IndividualReportRunDto> refreshRun(String userEmail, String reportDefinitionId) {
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of());
    }

    public Optional<IndividualReportRunDto> refreshRunWithAllIndicators(String userEmail, String reportDefinitionId) {
        // Delegate directly to buildAndSaveRun which recomputes all indicator and criterion scores
        // from source data via computeReportScopedIndividualReport. No pre-pass over LATEST
        // UserIndicatorResult records is needed — those records are not used by the evaluation page.
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of());
    }

    public long invalidateLatestRuns(String userEmail) {
        return userIndividualReportRunRepository.deleteByUserEmail(userEmail);
    }

    private Optional<IndividualReportRunDto> buildAndSaveRun(String userEmail,
                                                             String reportDefinitionId,
                                                             IndividualReportRunDto.Source source,
                                                             Map<String, Integer> latestRefreshVersionsByIndicatorId) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportDefinitionId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        IndividualReport report = reportOpt.get();
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setUserEmail(userEmail);
        run.setResearcherId(userEmail);
        run.setReportDefinitionId(reportDefinitionId);
        run.setCreatedAt(Instant.now());

        List<String> indicatorResultIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Optional<ReportScopedIndividualReportComputation> computationOpt =
                userReportFacade.computeReportScopedIndividualReport(userEmail, reportDefinitionId);
        if (computationOpt.isEmpty()) {
            return Optional.empty();
        }
        ReportScopedIndividualReportComputation computation = computationOpt.get();
        Map<String, Double> indicatorScoresByIndicatorId = new HashMap<>(computation.indicatorScoresByIndicatorId());

        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) {
                errors.add("Missing indicator id in report definition.");
                continue;
            }
            IndicatorApplyResultDto computedIndicatorResult = computation.reportScopedIndicatorResultsByIndicatorId()
                    .get(indicator.getId());
            if (computedIndicatorResult == null) {
                errors.add("Missing computed indicator result for indicator " + indicator.getId());
                continue;
            }
            UserIndicatorResult snapshot = userIndicatorResultService.createSnapshotFromComputed(
                    userEmail,
                    indicator.getId(),
                    reportDefinitionId,
                    computedIndicatorResult,
                    latestRefreshVersionsByIndicatorId.getOrDefault(
                            indicator.getId(),
                            userIndicatorResultService.getLatestRefreshVersion(userEmail, indicator.getId())
                    )
            );
            indicatorResultIds.add(snapshot.getId());
        }

        run.setIndicatorResultIds(indicatorResultIds);
        run.setIndicatorScoresByIndicatorId(indicatorScoresByIndicatorId);
        run.setCriteriaScores(new HashMap<>(computation.criterionScores()));
        run.setBuildErrors(errors);
        if (!errors.isEmpty()) {
            run.setStatus(indicatorResultIds.isEmpty() ? UserIndividualReportRun.Status.FAILED : UserIndividualReportRun.Status.PARTIAL);
        } else {
            run.setStatus(UserIndividualReportRun.Status.READY);
        }

        UserIndividualReportRun saved = userIndividualReportRunRepository.save(run);
        return Optional.of(toDto(saved, source));
    }

    private IndividualReportRunDto toDto(UserIndividualReportRun run, IndividualReportRunDto.Source source) {
        List<IndicatorApplyResultDto> indicatorResults = run.getIndicatorResultIds() == null ? List.of() :
                run.getIndicatorResultIds().stream()
                        .map(userIndicatorResultService::getById)
                        .flatMap(Optional::stream)
                        .toList();

        return new IndividualReportRunDto(
                run.getId(),
                run.getReportDefinitionId(),
                indicatorResults,
                run.getIndicatorScoresByIndicatorId() == null ? Map.of() : run.getIndicatorScoresByIndicatorId(),
                run.getCriteriaScores() == null ? Map.of() : run.getCriteriaScores(),
                run.getCreatedAt(),
                source
        );
    }
}
