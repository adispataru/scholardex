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

    public Optional<IndividualReportRunDto> getOrCreateLatestRun(String userEmail, String reportDefinitionId) {
        Optional<UserIndividualReportRun> existing = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportDefinitionId);
        if (existing.isPresent()) {
            return Optional.of(toDto(existing.get(), IndividualReportRunDto.Source.PERSISTED));
        }
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT);
    }

    public Optional<IndividualReportRunDto> refreshRun(String userEmail, String reportDefinitionId) {
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT);
    }

    public Optional<IndividualReportRunDto> refreshRunWithAllIndicators(String userEmail, String reportDefinitionId) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportDefinitionId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        IndividualReport report = reportOpt.get();
        if (report.getIndicators() != null) {
            for (Indicator indicator : report.getIndicators()) {
                if (indicator == null || indicator.getId() == null) {
                    continue;
                }
                userIndicatorResultService.refreshLatest(userEmail, indicator.getId());
            }
        }

        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT);
    }

    private Optional<IndividualReportRunDto> buildAndSaveRun(String userEmail,
                                                             String reportDefinitionId,
                                                             IndividualReportRunDto.Source source) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportDefinitionId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        IndividualReport report = reportOpt.get();
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setUserEmail(userEmail);
        run.setResearcherId(userService.getUserByEmail(userEmail).map(ro.uvt.pokedex.core.model.user.User::getResearcherId).orElse(null));
        run.setReportDefinitionId(reportDefinitionId);
        run.setCreatedAt(Instant.now());

        List<String> indicatorResultIds = new ArrayList<>();
        Map<String, Double> indicatorScoresByIndicatorId = new HashMap<>();
        List<String> errors = new ArrayList<>();

        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) {
                errors.add("Missing indicator id in report definition.");
                continue;
            }
            IndicatorApplyResultDto latest = userIndicatorResultService.getOrCreateLatest(userEmail, indicator.getId());
            UserIndicatorResult snapshot = userIndicatorResultService.createSnapshotFromLatest(userEmail, indicator.getId(), reportDefinitionId);
            indicatorResultIds.add(snapshot.getId());
            indicatorScoresByIndicatorId.put(indicator.getId(), latest.summary().totalScore() == null ? 0.0 : latest.summary().totalScore());
        }

        Map<Integer, Double> criteriaScores = new HashMap<>();

        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            AbstractReport.Criterion criterion = criteria.get(i);
            double criterionScore = 0.0;
            if (criterion.getIndicatorIndices() != null) {
                for (Integer idx : criterion.getIndicatorIndices()) {
                    if (idx == null || idx < 0 || idx >= report.getIndicators().size()) {
                        errors.add("Invalid criterion indicator index: " + idx);
                        continue;
                    }
                    Indicator indicator = report.getIndicators().get(idx);
                    if (indicator != null && indicator.getId() != null) {
                        criterionScore += indicatorScoresByIndicatorId.getOrDefault(indicator.getId(), 0.0);
                    }
                }
            }
            criteriaScores.put(i, criterionScore);
        }

        run.setIndicatorResultIds(indicatorResultIds);
        run.setIndicatorScoresByIndicatorId(indicatorScoresByIndicatorId);
        run.setCriteriaScores(criteriaScores);
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
