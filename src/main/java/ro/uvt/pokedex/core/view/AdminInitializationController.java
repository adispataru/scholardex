package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.GeneralInitializationService;
import ro.uvt.pokedex.core.service.application.DualReadGateService;
import ro.uvt.pokedex.core.service.application.H22OperationalStatusService;
import ro.uvt.pokedex.core.service.application.PostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.PostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.UserDefinedMaintenanceOrchestrationService;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;

import java.util.List;

@Controller
@RequestMapping("/admin/initialization")
@RequiredArgsConstructor
public class AdminInitializationController {

    private final GeneralInitializationService generalInitializationService;
    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final ScopusBigBangMigrationService scopusBigBangMigrationService;
    private final UserDefinedMaintenanceOrchestrationService userDefinedMaintenanceOrchestrationService;
    private final ObjectProvider<PostgresReportingProjectionService> postgresReportingProjectionServiceProvider;
    private final ObjectProvider<PostgresMaterializedViewRefreshService> postgresMaterializedViewRefreshServiceProvider;
    private final ObjectProvider<DualReadGateService> dualReadGateServiceProvider;
    private final ObjectProvider<H22OperationalStatusService> h22OperationalStatusServiceProvider;

    @GetMapping
    public String showInitializationPage(Model model) {
        DualReadGateService service = dualReadGateServiceProvider.getIfAvailable();
        DualReadGateService.DualReadGateRunSummary latestRun = null;
        if (service != null) {
            DualReadGateService.DualReadGateStatusSnapshot status = service.latestStatus();
            if (status != null) {
                latestRun = status.latestRun();
            }
        }
        List<DualReadGateService.DualReadScenarioResult> failedScenarios = latestRun == null
                ? List.of()
                : latestRun.scenarios()
                .stream()
                .filter(scenario -> !"SUCCESS".equals(scenario.status()))
                .toList();
        model.addAttribute("dualReadGateLatestRun", latestRun);
        model.addAttribute("dualReadGateFailedScenarios", failedScenarios);
        return "admin/initialization";
    }

    @PostMapping("/general/runAll")
    public String runGeneralInitializationAll(RedirectAttributes redirectAttributes) {
        var summary = generalInitializationService.runAll();
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "General initialization complete. success=" + summary.successCount()
                        + ", failed=" + summary.failureCount() + "."
        );
        return "redirect:/admin/initialization";
    }

    @PostMapping("/general/adminUser")
    public String runGeneralAdminUser(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runAdminUserBootstrap(), redirectAttributes);
    }

    @PostMapping("/general/domain")
    public String runGeneralDomainBootstrap(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runSpecialDomainBootstrap(), redirectAttributes);
    }

    @PostMapping("/general/artisticEvents")
    public String runGeneralArtisticEvents(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runArtisticEventsImport(), redirectAttributes);
    }

    @PostMapping("/general/urap")
    public String runGeneralUrap(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runUrapImport(), redirectAttributes);
    }

    @PostMapping("/general/cncsis")
    public String runGeneralCncsis(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runCncsisImport(), redirectAttributes);
    }

    @PostMapping("/general/coreConference")
    public String runGeneralCoreConference(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runCoreConferenceImport(), redirectAttributes);
    }

    @PostMapping("/general/sense")
    public String runGeneralSense(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runSenseImport(), redirectAttributes);
    }

    @GetMapping("/wos/enrichment")
    public String showWosEnrichmentPage(Model model) {
        model.addAttribute("summary", rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary());
        return "admin/wos-enrichment";
    }

    @PostMapping("/wos/rebuildProjections")
    public String rebuildWosProjections(RedirectAttributes redirectAttributes) {
        var result = rankingMaintenanceFacade.rebuildWosProjections();
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "WoS projections rebuilt. processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount()
        );
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/ingest")
    public String ingestWos(
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            RedirectAttributes redirectAttributes
    ) {
        var step = rankingMaintenanceFacade.ingestWosEvents(sourceVersion);
        redirectAttributes.addFlashAttribute("successMessage", "WoS ingest complete. " + formatWosStep("ingest", step));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/ensureIndexes")
    public String ensureWosIndexes(RedirectAttributes redirectAttributes) {
        var result = rankingMaintenanceFacade.ensureWosIndexes();
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "WoS indexes ensured. created=" + result.created().size()
                        + ", present=" + result.present().size()
                        + ", invalid=" + result.invalid().size()
                        + ", errors=" + result.errors().size()
        );
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/buildFacts")
    public String buildWosFacts(
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            @RequestParam(name = "startBatchOverride", required = false) Integer startBatchOverride,
            RedirectAttributes redirectAttributes
    ) {
        var step = rankingMaintenanceFacade.buildWosFactsFromEvents(startBatchOverride, sourceVersion, true);
        redirectAttributes.addFlashAttribute("successMessage", "WoS fact build complete. " + formatWosStep("facts", step));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/enrichCategoryRankings")
    public String enrichWosCategoryRankings(RedirectAttributes redirectAttributes) {
        var summary = rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary();
        redirectAttributes.addFlashAttribute("successMessage", "WoS category ranking enrichment complete. " + formatWosEnrichmentSummary(summary));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/enrichment/run")
    @ResponseBody
    public WosEnrichmentRunSummaryDto runWosCategoryEnrichmentApi() {
        return rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary();
    }

    @PostMapping("/wos/enrichment/runPage")
    public String runWosCategoryEnrichmentPageFlow(RedirectAttributes redirectAttributes) {
        var summary = rankingMaintenanceFacade.runWosCategoryRankingEnrichmentWithSummary();
        redirectAttributes.addFlashAttribute("successMessage", "WoS category ranking enrichment complete. " + formatWosEnrichmentSummary(summary));
        return "redirect:/admin/initialization/wos/enrichment";
    }

    @GetMapping("/wos/enrichment/summary")
    @ResponseBody
    public WosEnrichmentRunSummaryDto getLastWosCategoryEnrichmentSummaryApi() {
        return rankingMaintenanceFacade.latestWosCategoryRankingEnrichmentSummary();
    }

    @PostMapping("/wos/resetFactCheckpoint")
    public String resetWosFactCheckpoint(RedirectAttributes redirectAttributes) {
        rankingMaintenanceFacade.resetWosFactBuildCheckpoint();
        redirectAttributes.addFlashAttribute("successMessage", "WoS fact-build checkpoint reset.");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/wos/resetCanonicalState")
    public String resetWosCanonicalState(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!"RESET".equals(confirmation == null ? null : confirmation.trim())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "WoS canonical reset aborted. Type RESET in the confirmation field to proceed."
            );
            return "redirect:/admin/initialization";
        }
        var result = rankingMaintenanceFacade.resetWosCanonicalState();
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "WoS canonical state cleared. events=" + result.importEvents()
                        + ", journalIdentities=" + result.journalIdentities()
                        + ", metricFacts=" + result.metricFacts()
                        + ", categoryFacts=" + result.categoryFacts()
                        + ", identityConflicts=" + result.identityConflicts()
                        + ", factConflicts=" + result.factConflicts()
                        + ", rankingViews=" + result.rankingViewRows()
                        + ", scoringViews=" + result.scoringViewRows()
                        + "."
        );
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/ingest")
    public String runScopusIngest(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runIngestStep();
        redirectAttributes.addFlashAttribute("successMessage", "Scopus ingest complete. "
                + formatScopusStep("ingest", result.ingest()) + " "
                + formatScopusVerification(result.verification()));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/buildFacts")
    public String runScopusBuildFacts(
            @RequestParam(name = "startBatchOverride", required = false) Integer startBatchOverride,
            @RequestParam(name = "useCheckpoint", defaultValue = "true") boolean useCheckpoint,
            @RequestParam(name = "chunkSizeOverride", required = false) Integer chunkSizeOverride,
            RedirectAttributes redirectAttributes
    ) {
        var result = scopusBigBangMigrationService.runBuildFactsStep(startBatchOverride, useCheckpoint, chunkSizeOverride);
        redirectAttributes.addFlashAttribute("successMessage", "Scopus fact build complete. "
                + formatScopusStep("facts", result.buildFacts()) + " "
                + formatScopusVerification(result.verification()));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/buildCanonical")
    public String runScopusCanonicalBuild(
            @RequestParam(name = "entity", required = false) String entity,
            @RequestParam(name = "startBatchOverride", required = false) Integer startBatchOverride,
            @RequestParam(name = "useCheckpoint", defaultValue = "true") boolean useCheckpoint,
            @RequestParam(name = "reconcileSourceLinks", defaultValue = "false") boolean reconcileSourceLinks,
            @RequestParam(name = "reconcileEdges", defaultValue = "false") boolean reconcileEdges,
            @RequestParam(name = "fullRescan", defaultValue = "false") boolean fullRescan,
            @RequestParam(name = "drainQueues", defaultValue = "true") boolean drainQueues,
            @RequestParam(name = "chunkSizeOverride", required = false) Integer chunkSizeOverride,
            RedirectAttributes redirectAttributes
    ) {
        var result = scopusBigBangMigrationService.runCanonicalBuildStep(
                entity,
                startBatchOverride,
                useCheckpoint,
                chunkSizeOverride,
                reconcileSourceLinks,
                reconcileEdges,
                fullRescan,
                drainQueues
        );
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus canonical build complete (entity=" + (entity == null || entity.isBlank() ? "all" : entity) + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount()
                        + ", startBatch=" + result.getStartBatch()
                        + ", endBatch=" + result.getEndBatch()
                        + ", batchesProcessed=" + result.getBatchesProcessed()
                        + ", totalBatches=" + result.getTotalBatches()
                        + ", resumedFromCheckpoint=" + result.getResumedFromCheckpoint()
                        + ", checkpointLastCompletedBatch=" + result.getCheckpointLastCompletedBatch()
                        + ", fullRescan=" + fullRescan
                        + ", drainQueues=" + drainQueues
                        + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/rebuildTouchQueuesFromEvents")
    public String rebuildScopusTouchQueuesFromEvents(RedirectAttributes redirectAttributes) {
        var backlog = scopusBigBangMigrationService.rebuildTouchQueuesFromEvents();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus touch queues rebuilt. publications=" + backlog.publications()
                        + ", authors=" + backlog.authors()
                        + ", affiliations=" + backlog.affiliations()
                        + ", forums=" + backlog.forums()
                        + ", citations=" + backlog.citations() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/drainTouchQueues")
    public String drainScopusTouchQueues(RedirectAttributes redirectAttributes) {
        var backlog = scopusBigBangMigrationService.drainAllTouchQueues();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus touch queues drain completed. publications=" + backlog.publications()
                        + ", authors=" + backlog.authors()
                        + ", affiliations=" + backlog.affiliations()
                        + ", forums=" + backlog.forums()
                        + ", citations=" + backlog.citations() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/showTouchQueueBacklog")
    public String showScopusTouchQueueBacklog(RedirectAttributes redirectAttributes) {
        var backlog = scopusBigBangMigrationService.showTouchQueueBacklog();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus touch queue backlog. publications=" + backlog.publications()
                        + ", authors=" + backlog.authors()
                        + ", affiliations=" + backlog.affiliations()
                        + ", forums=" + backlog.forums()
                        + ", citations=" + backlog.citations() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/reconcileEdges")
    public String runScopusEdgeReconcile(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runEdgeReconcileStep();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus edge reconcile complete. processed=" + result.getProcessedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/reconcileSourceLinks")
    public String runScopusSourceLinkReconcile(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runSourceLinkReconcileStep();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus source-link reconcile complete. updated=" + result.updated()
                        + ", skipped=" + result.skipped()
                        + ", errors=" + result.errors() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/resetCanonicalCheckpoints")
    public String resetScopusCanonicalCheckpoints(RedirectAttributes redirectAttributes) {
        scopusBigBangMigrationService.resetCanonicalBuildCheckpoints();
        redirectAttributes.addFlashAttribute("successMessage", "Scopus canonical build checkpoints reset.");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/buildProjections")
    public String runScopusBuildProjections(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runBuildProjectionsStep();
        redirectAttributes.addFlashAttribute("successMessage", "Scopus projection build complete. "
                + formatScopusStep("projections", result.buildProjections()) + " "
                + formatScopusVerification(result.verification()));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/ensureIndexes")
    public String runScopusEnsureIndexes(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runEnsureIndexesStep();
        redirectAttributes.addFlashAttribute("successMessage", "Scopus indexes ensured. "
                + "indexes[created=" + result.ensureIndexes().created()
                + ", present=" + result.ensureIndexes().present()
                + ", invalid=" + result.ensureIndexes().invalid()
                + ", errors=" + result.ensureIndexes().errors() + "]. "
                + formatScopusVerification(result.verification()));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/backfillCanonicalCitations")
    public String runScopusCitationBackfill(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runCitationIdentityBackfill();
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus canonical citation backfill complete. processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/user-defined/buildFacts")
    public String runUserDefinedBuildFacts(RedirectAttributes redirectAttributes) {
        var result = userDefinedMaintenanceOrchestrationService.runBuildFactsStep(null);
        redirectAttributes.addFlashAttribute("successMessage",
                "USER_DEFINED fact build complete. processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/user-defined/canonicalize")
    public String runUserDefinedCanonicalize(
            @RequestParam(name = "reconcileSourceLinks", defaultValue = "false") boolean reconcileSourceLinks,
            @RequestParam(name = "reconcileEdges", defaultValue = "false") boolean reconcileEdges,
            @RequestParam(name = "rebuildProjections", defaultValue = "true") boolean rebuildProjections,
            RedirectAttributes redirectAttributes
    ) {
        var summary = userDefinedMaintenanceOrchestrationService.runCanonicalizeStep(
                reconcileSourceLinks,
                reconcileEdges,
                rebuildProjections
        );
        redirectAttributes.addFlashAttribute("successMessage", formatUserDefinedMaintenance("canonicalize", summary));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/user-defined/runAll")
    public String runUserDefinedRunAll(
            @RequestParam(name = "reconcileSourceLinks", defaultValue = "false") boolean reconcileSourceLinks,
            @RequestParam(name = "reconcileEdges", defaultValue = "false") boolean reconcileEdges,
            @RequestParam(name = "rebuildProjections", defaultValue = "true") boolean rebuildProjections,
            RedirectAttributes redirectAttributes
    ) {
        var summary = userDefinedMaintenanceOrchestrationService.runAll(
                null,
                reconcileSourceLinks,
                reconcileEdges,
                rebuildProjections
        );
        redirectAttributes.addFlashAttribute("successMessage", formatUserDefinedMaintenance("runAll", summary));
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/projection/runFull")
    public String runPostgresProjectionFullRebuild(RedirectAttributes redirectAttributes) {
        PostgresReportingProjectionService service = postgresReportingProjectionServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres projection service is disabled. Enable core.h22.projection.enabled in postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        var run = service.runFullRebuild();
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres projection full rebuild " + run.status().toLowerCase()
                        + ". runId=" + run.runId()
                        + ", slices=" + run.slices().size()
                        + ", error=" + (run.errorSample() == null ? "none" : run.errorSample()) + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/projection/runIncremental")
    public String runPostgresProjectionIncremental(RedirectAttributes redirectAttributes) {
        PostgresReportingProjectionService service = postgresReportingProjectionServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres projection service is disabled. Enable core.h22.projection.enabled in postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        var run = service.runIncrementalSync();
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres projection incremental sync " + run.status().toLowerCase()
                        + ". runId=" + run.runId()
                        + ", slices=" + run.slices().size()
                        + ", error=" + (run.errorSample() == null ? "none" : run.errorSample()) + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/projection/showStatus")
    public String showPostgresProjectionStatus(RedirectAttributes redirectAttributes) {
        PostgresReportingProjectionService service = postgresReportingProjectionServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres projection service is disabled. Enable core.h22.projection.enabled in postgres profile."
            );
            return "redirect:/admin/initialization";
        }
        var snapshot = service.latestRunStatus();
        var latestRun = snapshot.latestRun();
        redirectAttributes.addFlashAttribute("successMessage",
                latestRun == null
                        ? "Postgres projection status: no run recorded yet."
                        : "Postgres projection status: runId=" + latestRun.runId()
                        + ", mode=" + latestRun.mode()
                        + ", status=" + latestRun.status()
                        + ", slices=" + latestRun.slices().size()
                        + ", checkpoints=" + snapshot.checkpoints().size() + ".");
        return "redirect:/admin/initialization";
    }

    @GetMapping("/postgres/projection/status")
    @ResponseBody
    public PostgresReportingProjectionService.ProjectionStatusSnapshot postgresProjectionStatusApi() {
        PostgresReportingProjectionService service = postgresReportingProjectionServiceProvider.getIfAvailable();
        if (service == null) {
            return new PostgresReportingProjectionService.ProjectionStatusSnapshot(null, java.util.Map.of());
        }
        return service.latestRunStatus();
    }

    @PostMapping("/postgres/projection/resetState")
    public String resetPostgresProjectionState(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!"RESET".equals(confirmation == null ? null : confirmation.trim())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres projection reset aborted. Type RESET in the confirmation field to proceed."
            );
            return "redirect:/admin/initialization";
        }
        PostgresReportingProjectionService service = postgresReportingProjectionServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres projection service is disabled. Enable core.h22.projection.enabled in postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        service.resetProjectionState();
        redirectAttributes.addFlashAttribute("successMessage", "Postgres projection checkpoints reset.");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/materialized/refreshAll")
    public String refreshPostgresMaterializedViewsAll(RedirectAttributes redirectAttributes) {
        PostgresMaterializedViewRefreshService service = postgresMaterializedViewRefreshServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres materialized-view refresh service is disabled. Enable postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        var run = service.refreshAllManual();
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres materialized-view refresh " + run.status().toLowerCase()
                        + ". runId=" + run.runId()
                        + ", views=" + run.views().size()
                        + ", error=" + (run.errorSample() == null ? "none" : run.errorSample()) + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/materialized/refreshSlice")
    public String refreshPostgresMaterializedViewsSlice(
            @RequestParam(name = "slice", required = false) String slice,
            RedirectAttributes redirectAttributes
    ) {
        PostgresMaterializedViewRefreshService service = postgresMaterializedViewRefreshServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres materialized-view refresh service is disabled. Enable postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        String normalized = slice == null ? "" : slice.trim().toLowerCase();
        if (!"wos".equals(normalized) && !"scopus".equals(normalized)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Invalid materialized-view slice. Allowed values: wos, scopus."
            );
            return "redirect:/admin/initialization";
        }

        var run = service.refreshManualForSlices(java.util.Set.of(normalized));
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres materialized-view slice refresh " + run.status().toLowerCase()
                        + ". runId=" + run.runId()
                        + ", slice=" + normalized
                        + ", views=" + run.views().size()
                        + ", error=" + (run.errorSample() == null ? "none" : run.errorSample()) + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/materialized/showStatus")
    public String showPostgresMaterializedViewRefreshStatus(RedirectAttributes redirectAttributes) {
        PostgresMaterializedViewRefreshService service = postgresMaterializedViewRefreshServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres materialized-view refresh service is disabled. Enable postgres profile."
            );
            return "redirect:/admin/initialization";
        }
        var latestRun = service.latestStatus().latestRun();
        redirectAttributes.addFlashAttribute("successMessage",
                latestRun == null
                        ? "Postgres materialized-view refresh status: no run recorded yet."
                        : "Postgres materialized-view refresh status: runId=" + latestRun.runId()
                        + ", trigger=" + latestRun.triggerMode()
                        + ", status=" + latestRun.status()
                        + ", views=" + latestRun.views().size() + ".");
        return "redirect:/admin/initialization";
    }

    @GetMapping("/postgres/materialized/status")
    @ResponseBody
    public PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot postgresMaterializedStatusApi() {
        PostgresMaterializedViewRefreshService service = postgresMaterializedViewRefreshServiceProvider.getIfAvailable();
        if (service == null) {
            return new PostgresMaterializedViewRefreshService.MaterializedViewRefreshStatusSnapshot(null);
        }
        return service.latestStatus();
    }

    @PostMapping("/postgres/dualReadGate/run")
    public String runPostgresDualReadGate(RedirectAttributes redirectAttributes) {
        DualReadGateService service = dualReadGateServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres dual-read gate service is disabled. Enable postgres profile."
            );
            return "redirect:/admin/initialization";
        }

        var run = service.runFullGate();
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres dual-read gate " + run.status().toLowerCase()
                        + ". runId=" + run.runId()
                        + ", scenarios=" + run.scenarios().size()
                        + ", failed="
                        + run.scenarios().stream().filter(scenario -> !"SUCCESS".equals(scenario.status())).count()
                        + ", error=" + (run.errorSample() == null ? "none" : run.errorSample()) + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/postgres/dualReadGate/showStatus")
    public String showPostgresDualReadGateStatus(RedirectAttributes redirectAttributes) {
        DualReadGateService service = dualReadGateServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres dual-read gate service is disabled. Enable postgres profile."
            );
            return "redirect:/admin/initialization";
        }
        var latestRun = service.latestStatus().latestRun();
        redirectAttributes.addFlashAttribute("successMessage",
                latestRun == null
                        ? "Postgres dual-read gate status: no run recorded yet."
                        : formatDualReadGateStatus(latestRun));
        return "redirect:/admin/initialization";
    }

    @GetMapping("/postgres/dualReadGate/status")
    @ResponseBody
    public DualReadGateService.DualReadGateStatusSnapshot postgresDualReadGateStatusApi() {
        DualReadGateService service = dualReadGateServiceProvider.getIfAvailable();
        if (service == null) {
            return new DualReadGateService.DualReadGateStatusSnapshot(null);
        }
        return service.latestStatus();
    }

    @PostMapping("/postgres/operational/showStatus")
    public String showPostgresOperationalStatus(RedirectAttributes redirectAttributes) {
        H22OperationalStatusService service = h22OperationalStatusServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "H22 operational status service is disabled."
            );
            return "redirect:/admin/initialization";
        }
        var snapshot = service.latestStatus();
        redirectAttributes.addFlashAttribute("successMessage",
                "H22 operational status: state=" + snapshot.overallState()
                        + ", readStore=" + snapshot.readStore()
                        + ", projection=" + snapshot.projection().status()
                        + ", materialized=" + snapshot.materializedViewRefresh().status()
                        + ", dualReadGate=" + snapshot.dualReadGate().status()
                        + ".");
        return "redirect:/admin/initialization";
    }

    @GetMapping("/postgres/operational/status")
    @ResponseBody
    public H22OperationalStatusService.H22OperationalStatusSnapshot postgresOperationalStatusApi() {
        H22OperationalStatusService service = h22OperationalStatusServiceProvider.getIfAvailable();
        if (service == null) {
            return H22OperationalStatusService.H22OperationalStatusSnapshot.unavailable();
        }
        return service.latestStatus();
    }

    @PostMapping("/scopus/resetCanonicalState")
    public String resetScopusCanonicalState(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            RedirectAttributes redirectAttributes
    ) {
        if (!"RESET".equals(confirmation == null ? null : confirmation.trim())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Scopus canonical reset aborted. Type RESET in the confirmation field to proceed."
            );
            return "redirect:/admin/initialization";
        }
        var result = scopusBigBangMigrationService.resetCanonicalState();
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Scopus canonical state cleared. importEvents=" + result.importEvents()
                        + ", publicationFacts=" + result.publicationFacts()
                        + ", citationFacts=" + result.citationFacts()
                        + ", forumFacts=" + result.forumFacts()
                        + ", authorFacts=" + result.authorFacts()
                        + ", affiliationFacts=" + result.affiliationFacts()
                        + ", forumViews=" + result.forumViews()
                        + ", authorViews=" + result.authorViews()
                        + ", affiliationViews=" + result.affiliationViews()
                        + ", canonicalPublicationFacts=" + result.canonicalPublicationFacts()
                        + ", canonicalCitationFacts=" + result.canonicalCitationFacts()
                        + ", canonicalAuthorFacts=" + result.canonicalAuthorFacts()
                        + ", canonicalAffiliationFacts=" + result.canonicalAffiliationFacts()
                        + ", canonicalForumFacts=" + result.canonicalForumFacts()
                        + ", publicationViews=" + result.publicationViews()
                        + ", canonicalAuthorViews=" + result.canonicalAuthorViews()
                        + ", canonicalAffiliationViews=" + result.canonicalAffiliationViews()
                        + ", canonicalForumViews=" + result.canonicalForumViews()
                        + ", sourceLinks=" + result.sourceLinks()
                        + ", identityConflicts=" + result.identityConflicts()
                        + ", authorshipFacts=" + result.authorshipFacts()
                        + ", authorAffiliationFacts=" + result.authorAffiliationFacts()
                        + ", publicationAuthorAffiliationFacts=" + result.publicationAuthorAffiliationFacts()
                        + ", canonicalBuildCheckpoints=" + result.canonicalBuildCheckpoints()
                        + ", publicationTouches=" + result.publicationTouches()
                        + ", authorTouches=" + result.authorTouches()
                        + ", affiliationTouches=" + result.affiliationTouches()
                        + ", forumTouches=" + result.forumTouches()
                        + ", citationTouches=" + result.citationTouches()
                        + "."
        );
        return "redirect:/admin/initialization";
    }

    private String formatWosStep(String label, ro.uvt.pokedex.core.service.application.WosBigBangMigrationService.MigrationStepResult step) {
        String checkpointInfo = "";
        if (step.startBatch() != null || step.endBatch() != null || step.batchesProcessed() != null) {
            checkpointInfo = ", startBatch=" + step.startBatch()
                    + ", endBatch=" + step.endBatch()
                    + ", batchesProcessed=" + step.batchesProcessed()
                    + ", resumedFromCheckpoint=" + step.resumedFromCheckpoint()
                    + ", checkpointLastCompletedBatch=" + step.checkpointLastCompletedBatch();
        }
        return label + "[processed=" + step.processed()
                + ", imported=" + step.imported()
                + ", updated=" + step.updated()
                + ", skipped=" + step.skipped()
                + ", errors=" + step.errors()
                + checkpointInfo + "].";
    }

    private String formatScopusStep(String label, ScopusBigBangMigrationService.MigrationStepResult step) {
        if (step == null) {
            return label + "[not-run].";
        }
        String checkpointInfo = "";
        if (step.startBatch() != null || step.endBatch() != null || step.batchesProcessed() != null) {
            checkpointInfo = ", startBatch=" + step.startBatch()
                    + ", endBatch=" + step.endBatch()
                    + ", batchesProcessed=" + step.batchesProcessed()
                    + ", totalBatches=" + step.totalBatches()
                    + ", resumedFromCheckpoint=" + step.resumedFromCheckpoint()
                    + ", checkpointLastCompletedBatch=" + step.checkpointLastCompletedBatch();
        }
        return label + "[processed=" + step.processed()
                + ", imported=" + step.imported()
                + ", updated=" + step.updated()
                + ", skipped=" + step.skipped()
                + ", errors=" + step.errors()
                + checkpointInfo + "].";
    }

    private String formatScopusVerification(ScopusBigBangMigrationService.VerificationSummary verification) {
        return "verify[events=" + verification.importEvents()
                + ", publicationFacts=" + verification.publicationFacts()
                + ", canonicalPublicationFacts=" + verification.canonicalPublicationFacts()
                + ", citationFacts=" + verification.citationFacts()
                + ", canonicalCitationFacts=" + verification.canonicalCitationFacts()
                + ", forumFacts=" + verification.forumFacts()
                + ", authorFacts=" + verification.authorFacts()
                + ", affiliationFacts=" + verification.affiliationFacts()
                + ", forumViews=" + verification.forumViews()
                + ", authorViews=" + verification.authorViews()
                + ", affiliationViews=" + verification.affiliationViews()
                + ", publicationViews=" + verification.publicationViews() + "].";
    }

    private String formatUserDefinedMaintenance(
            String label,
            UserDefinedMaintenanceOrchestrationService.UserDefinedMaintenanceRunSummary summary
    ) {
        return "USER_DEFINED " + label + " complete. "
                + "buildFacts[processed=" + summary.buildFacts().getProcessedCount()
                + ", imported=" + summary.buildFacts().getImportedCount()
                + ", updated=" + summary.buildFacts().getUpdatedCount()
                + ", skipped=" + summary.buildFacts().getSkippedCount()
                + ", errors=" + summary.buildFacts().getErrorCount()
                + "], canonicalize[processed=" + summary.canonicalize().getProcessedCount()
                + ", imported=" + summary.canonicalize().getImportedCount()
                + ", updated=" + summary.canonicalize().getUpdatedCount()
                + ", skipped=" + summary.canonicalize().getSkippedCount()
                + ", errors=" + summary.canonicalize().getErrorCount()
                + "], sourceLinkReconcile[updated=" + summary.sourceLinkReconcile().updated()
                + ", skipped=" + summary.sourceLinkReconcile().skipped()
                + ", errors=" + summary.sourceLinkReconcile().errors()
                + "], edgeReconcile[updated=" + summary.edgeReconcile().getUpdatedCount()
                + ", skipped=" + summary.edgeReconcile().getSkippedCount()
                + ", errors=" + summary.edgeReconcile().getErrorCount()
                + "], projections[processed=" + summary.projections().getProcessedCount()
                + ", errors=" + summary.projections().getErrorCount()
                + "].";
    }

    private String formatWosEnrichmentSummary(WosEnrichmentRunSummaryDto summary) {
        return "enrichment[processed=" + summary.processed()
                + ", computed=" + summary.computed()
                + ", preserved=" + summary.preserved()
                + ", failed=" + summary.failed()
                + ", skipped=" + summary.skipped()
                + ", durationMs=" + summary.durationMs()
                + "].";
    }

    private String formatDualReadGateStatus(DualReadGateService.DualReadGateRunSummary latestRun) {
        var failedScenarios = latestRun.scenarios()
                .stream()
                .filter(scenario -> !"SUCCESS".equals(scenario.status()))
                .toList();

        if (failedScenarios.isEmpty()) {
            return "Postgres dual-read gate status: runId=" + latestRun.runId()
                    + ", status=" + latestRun.status()
                    + ", scenarios=" + latestRun.scenarios().size()
                    + ", failed=0.";
        }

        String failedScenarioIds = String.join(", ", failedScenarios.stream()
                .map(DualReadGateService.DualReadScenarioResult::scenarioId)
                .limit(3)
                .toList());
        String failedScenarioSuffix = failedScenarios.size() > 3 ? "..." : "";
        String error = firstNonBlank(
                latestRun.errorSample(),
                failedScenarios.stream()
                        .map(DualReadGateService.DualReadScenarioResult::mismatchSample)
                        .filter(sample -> sample != null && !sample.isBlank())
                        .findFirst()
                        .orElse(null),
                "none"
        );

        return "Postgres dual-read gate status: runId=" + latestRun.runId()
                + ", status=" + latestRun.status()
                + ", scenarios=" + latestRun.scenarios().size()
                + ", failed=" + failedScenarios.size()
                + ", failedScenarios=[" + failedScenarioIds + failedScenarioSuffix + "]"
                + ", error=" + error + ".";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String redirectAfterGeneralStep(
            GeneralInitializationService.GeneralInitializationStepResult step,
            RedirectAttributes redirectAttributes
    ) {
        String message = "General step '" + step.step() + "' "
                + (step.success() ? "completed" : "failed")
                + ". durationMs=" + step.durationMs()
                + ", details=" + step.message();
        if (step.success()) {
            redirectAttributes.addFlashAttribute("successMessage", message);
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", message);
        }
        return "redirect:/admin/initialization";
    }
}
