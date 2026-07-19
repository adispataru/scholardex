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
    private final ReportingLookupMemoization reportingLookupMemoization;

    public Optional<IndividualReportRunDto> getOrCreateLatestRun(String userEmail, String reportDefinitionId) {
        Optional<UserIndividualReportRun> existing = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportDefinitionId);
        if (existing.isPresent()) {
            return Optional.of(toDto(existing.get(), IndividualReportRunDto.Source.PERSISTED));
        }
        return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of(), userEmail);
    }

    /**
     * Read-only lookup of the latest persisted run for a user's report. Unlike
     * {@link #getOrCreateLatestRun}, this never builds or persists a run when none exists — it is
     * the safe primitive for delegated (admin/supervisor) viewing, where looking at a researcher's
     * report must not mutate that researcher's data space. Returns empty when no run exists.
     */
    public Optional<IndividualReportRunDto> findLatestRun(String userEmail, String reportDefinitionId) {
        return userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportDefinitionId)
                .map(run -> toDto(run, IndividualReportRunDto.Source.PERSISTED));
    }

    /**
     * All persisted runs for a user's report, newest first — the run-history rail on the individual
     * report view. Read-only.
     */
    public List<UserIndividualReportRun> listRuns(String userEmail, String reportDefinitionId) {
        return userIndividualReportRunRepository
                .findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(userEmail, reportDefinitionId);
    }

    public Optional<IndividualReportRunDto> refreshRun(String userEmail, String reportDefinitionId) {
        // One memoization scope for the whole refresh: the ranking/forum/input lookups repeat heavily
        // across the report's indicators (and across the recompute passes), so scope-cache them like
        // the group/department runners already do. All reads inside are point-in-time consistent.
        return reportingLookupMemoization.withRefreshScope(() -> {
            refreshLatestForReportIndicators(userEmail, reportDefinitionId);
            return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of(), userEmail);
        });
    }

    public Optional<IndividualReportRunDto> refreshRunWithAllIndicators(String userEmail, String reportDefinitionId) {
        return refreshRunWithAllIndicators(userEmail, reportDefinitionId, userEmail);
    }

    /**
     * Refresh a researcher's report and record who triggered it. {@code actorEmail} equals
     * {@code userEmail} for a self-refresh; for an admin/supervisor delegated refresh it is the
     * acting principal, persisted on the run as {@code triggeredByEmail} (provenance). The
     * recomputation itself is identical — reports are a deterministic projection of canonical data.
     */
    public Optional<IndividualReportRunDto> refreshRunWithAllIndicators(String userEmail,
                                                                        String reportDefinitionId,
                                                                        String actorEmail) {
        // Same single-scope treatment as refreshRun — see the comment there.
        return reportingLookupMemoization.withRefreshScope(() -> {
            refreshLatestForReportIndicators(userEmail, reportDefinitionId);
            return buildAndSaveRun(userEmail, reportDefinitionId, IndividualReportRunDto.Source.BUILT, Map.of(), actorEmail);
        });
    }

    /**
     * Bypass the LATEST indicator-result cache for every indicator on the report. The cache uses
     * an indicator-definition fingerprint, so a scoring-service code change does not invalidate
     * persisted LATEST entries on its own. Without this pre-pass, the per-indicator detail panel
     * and the H50 report exporter would keep serving stale {@code coreRankingEquivalent} /
     * {@code authorScore} values until each indicator was refreshed individually — which means
     * the downloaded export could diverge from what the user sees on the dashboard after clicking
     * Refresh.
     */
    private void refreshLatestForReportIndicators(String userEmail, String reportDefinitionId) {
        individualReportRepository.findById(reportDefinitionId).ifPresent(report -> {
            if (report.getIndicators() == null) return;
            for (Indicator indicator : report.getIndicators()) {
                if (indicator != null && indicator.getId() != null) {
                    userIndicatorResultService.refreshLatest(userEmail, indicator.getId());
                }
            }
        });
    }

    public long invalidateLatestRuns(String userEmail) {
        return userIndividualReportRunRepository.deleteByUserEmail(userEmail);
    }

    private Optional<IndividualReportRunDto> buildAndSaveRun(String userEmail,
                                                             String reportDefinitionId,
                                                             IndividualReportRunDto.Source source,
                                                             Map<String, Integer> latestRefreshVersionsByIndicatorId,
                                                             String actorEmail) {
        // Nested scopes are no-ops, so this also covers the getOrCreateLatestRun (first-visit) path
        // while the refresh entry points keep their wider scope.
        return reportingLookupMemoization.withRefreshScope(() ->
                buildAndSaveRunInternal(userEmail, reportDefinitionId, source, latestRefreshVersionsByIndicatorId, actorEmail));
    }

    private Optional<IndividualReportRunDto> buildAndSaveRunInternal(String userEmail,
                                                                     String reportDefinitionId,
                                                                     IndividualReportRunDto.Source source,
                                                                     Map<String, Integer> latestRefreshVersionsByIndicatorId,
                                                                     String actorEmail) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportDefinitionId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        IndividualReport report = reportOpt.get();
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setUserEmail(userEmail);
        run.setResearcherId(userEmail);
        run.setReportDefinitionId(reportDefinitionId);
        run.setTriggeredByEmail(actorEmail != null ? actorEmail : userEmail);
        Instant createdAt = Instant.now();
        run.setCreatedAt(createdAt);
        // H60: capture the evaluation anchor that relative year specs resolve against (default = creation year), so
        // replay/export of this run is stable across later ranking imports.
        run.setReferenceYear(createdAt.atZone(java.time.ZoneId.systemDefault()).getYear());

        List<String> indicatorResultIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        // H60: score with the run's referenceYear in scope so relative year specs resolve against it.
        Optional<ReportScopedIndividualReportComputation> computationOpt =
                ro.uvt.pokedex.core.service.reporting.ScoringReferenceYearContext.with(
                        run.getReferenceYear(),
                        () -> userReportFacade.computeReportScopedIndividualReport(userEmail, reportDefinitionId));
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
            // Use the report-scoped *detail* (rich rawGraph with outputMode/scores/publications),
            // not computation.reportScopedIndicatorResultsByIndicatorId() which carries only the
            // thin {indicator,total} graph. The exporter's RunIndicatorSnapshotProjector needs the
            // per-item detail to render publication/citation rows; with the thin graph every
            // projected row set is empty and the export shows 0 everywhere. Totals match because
            // both paths score through the same support/service with the same affiliation filter.
            IndicatorApplyResultDto computedIndicatorResult = userReportFacade
                    .buildReportScopedIndicatorDetail(userEmail, reportDefinitionId, indicator.getId())
                    .orElseGet(() -> computation.reportScopedIndicatorResultsByIndicatorId().get(indicator.getId()));
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

    /**
     * H77: persist an admin provisional run for one subject scored from a DECLARED authorship identity — the resolved
     * canonical author ids (from the org roster / declared source ids), NOT the subject's confirmed decisions. Read-only:
     * the subject email is just an identifier (no {@code User} needed); the run is flagged {@code provisional}. Indicator
     * snapshots use the thin computed graph (correct totals; per-item detail is not materialized on this path).
     */
    public Optional<IndividualReportRunDto> buildAndSaveProvisionalRun(String subjectEmail,
                                                                       String reportDefinitionId,
                                                                       java.util.Collection<String> resolvedAuthorIds,
                                                                       String adminEmail) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportDefinitionId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }
        IndividualReport report = reportOpt.get();

        return reportingLookupMemoization.withRefreshScope(
                () -> buildAndSaveProvisionalRunInternal(subjectEmail, reportDefinitionId, resolvedAuthorIds, adminEmail, report));
    }

    private Optional<IndividualReportRunDto> buildAndSaveProvisionalRunInternal(String subjectEmail,
                                                                                String reportDefinitionId,
                                                                                java.util.Collection<String> resolvedAuthorIds,
                                                                                String adminEmail,
                                                                                IndividualReport report) {
        UserIndividualReportRun run = new UserIndividualReportRun();
        run.setUserEmail(subjectEmail);
        run.setResearcherId(subjectEmail);
        run.setReportDefinitionId(reportDefinitionId);
        run.setTriggeredByEmail(adminEmail != null ? adminEmail : subjectEmail);
        run.setProvisional(true);
        Instant createdAt = Instant.now();
        run.setCreatedAt(createdAt);
        int referenceYear = createdAt.atZone(java.time.ZoneId.systemDefault()).getYear();
        run.setReferenceYear(referenceYear);

        Optional<ReportScopedIndividualReportComputation> computationOpt =
                userReportFacade.computeProvisionalReport(resolvedAuthorIds, reportDefinitionId, referenceYear);
        if (computationOpt.isEmpty()) {
            return Optional.empty();
        }
        ReportScopedIndividualReportComputation computation = computationOpt.get();

        List<String> indicatorResultIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Indicator indicator : report.getIndicators()) {
            if (indicator == null || indicator.getId() == null) {
                errors.add("Missing indicator id in report definition.");
                continue;
            }
            IndicatorApplyResultDto computedIndicatorResult =
                    computation.reportScopedIndicatorResultsByIndicatorId().get(indicator.getId());
            if (computedIndicatorResult == null) {
                errors.add("Missing computed indicator result for indicator " + indicator.getId());
                continue;
            }
            UserIndicatorResult snapshot = userIndicatorResultService.createSnapshotFromComputed(
                    subjectEmail,
                    indicator.getId(),
                    reportDefinitionId,
                    computedIndicatorResult,
                    userIndicatorResultService.getLatestRefreshVersion(subjectEmail, indicator.getId())
            );
            indicatorResultIds.add(snapshot.getId());
        }

        run.setIndicatorResultIds(indicatorResultIds);
        run.setIndicatorScoresByIndicatorId(new HashMap<>(computation.indicatorScoresByIndicatorId()));
        run.setCriteriaScores(new HashMap<>(computation.criterionScores()));
        run.setBuildErrors(errors);
        if (!errors.isEmpty()) {
            run.setStatus(indicatorResultIds.isEmpty() ? UserIndividualReportRun.Status.FAILED : UserIndividualReportRun.Status.PARTIAL);
        } else {
            run.setStatus(UserIndividualReportRun.Status.READY);
        }

        UserIndividualReportRun saved = userIndividualReportRunRepository.save(run);
        return Optional.of(toDto(saved, IndividualReportRunDto.Source.ADMIN_PROVISIONAL));
    }

    private IndividualReportRunDto toDto(UserIndividualReportRun run, IndividualReportRunDto.Source source) {
        // H77: a persisted provisional run always reports as ADMIN_PROVISIONAL so the marker survives reload.
        IndividualReportRunDto.Source effectiveSource = run.isProvisional() ? IndividualReportRunDto.Source.ADMIN_PROVISIONAL : source;
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
                effectiveSource,
                run.getTriggeredByEmail()
        );
    }
}
