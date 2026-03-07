package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;

@Controller
@RequestMapping("/admin/initialization")
@RequiredArgsConstructor
public class AdminInitializationController {

    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final ScopusBigBangMigrationService scopusBigBangMigrationService;

    @GetMapping
    public String showInitializationPage() {
        return "admin/initialization";
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

    @PostMapping("/wos/runBigBangMigration")
    public String runWosBigBangMigration(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            @RequestParam(name = "startBatchOverride", required = false) Integer startBatchOverride,
            RedirectAttributes redirectAttributes
    ) {
        try {
            var result = rankingMaintenanceFacade.runWosBigBangMigration(dryRun, sourceVersion, startBatchOverride, true);
            String mode = dryRun ? "dry-run" : "full-run";
            redirectAttributes.addFlashAttribute("successMessage", "WoS big-bang " + mode + " complete. "
                    + formatWosStep("ingest", result.ingest()) + " "
                    + formatWosStep("facts", result.buildFacts()) + " "
                    + formatWosStep("projections", result.buildProjections()) + " "
                    + "verify[events=" + result.verification().importEvents()
                    + ", metricFacts=" + result.verification().metricFacts()
                    + ", legacyCategoryFacts=" + result.verification().categoryFacts()
                    + ", rankingRows=" + result.verification().rankingViewRows()
                    + ", scoringRows=" + result.verification().scoringViewRows()
                    + ", parserErrors=" + result.verification().parserErrors()
                    + ", parityPassed=" + result.verification().parityPassed()
                    + ", parityMismatches=" + result.verification().parityMismatchCount()
                    + "].");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "WoS big-bang migration failed: " + e.getMessage());
        }
        return "redirect:/admin/initialization";
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
                        + ", legacyCategoryFacts=" + result.categoryFacts()
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
    public String runScopusBuildFacts(RedirectAttributes redirectAttributes) {
        var result = scopusBigBangMigrationService.runBuildFactsStep();
        redirectAttributes.addFlashAttribute("successMessage", "Scopus fact build complete. "
                + formatScopusStep("facts", result.buildFacts()) + " "
                + formatScopusVerification(result.verification()));
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

    @PostMapping("/scopus/runBigBang")
    public String runScopusBigBang(RedirectAttributes redirectAttributes) {
        try {
            var result = scopusBigBangMigrationService.runFull();
            redirectAttributes.addFlashAttribute("successMessage", "Scopus big-bang full-run complete. "
                    + formatScopusStep("ingest", result.ingest()) + " "
                    + formatScopusStep("facts", result.buildFacts()) + " "
                    + formatScopusStep("projections", result.buildProjections()) + " "
                    + "indexes[created=" + result.ensureIndexes().created()
                    + ", present=" + result.ensureIndexes().present()
                    + ", invalid=" + result.ensureIndexes().invalid()
                    + ", errors=" + result.ensureIndexes().errors() + "]. "
                    + formatScopusVerification(result.verification()));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Scopus big-bang failed: " + e.getMessage());
        }
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
        return label + "[processed=" + step.processed()
                + ", imported=" + step.imported()
                + ", updated=" + step.updated()
                + ", skipped=" + step.skipped()
                + ", errors=" + step.errors() + "].";
    }

    private String formatScopusVerification(ScopusBigBangMigrationService.VerificationSummary verification) {
        return "verify[events=" + verification.importEvents()
                + ", publicationFacts=" + verification.publicationFacts()
                + ", citationFacts=" + verification.citationFacts()
                + ", forumFacts=" + verification.forumFacts()
                + ", authorFacts=" + verification.authorFacts()
                + ", affiliationFacts=" + verification.affiliationFacts()
                + ", forumViews=" + verification.forumViews()
                + ", authorViews=" + verification.authorViews()
                + ", affiliationViews=" + verification.affiliationViews()
                + ", publicationViews=" + verification.publicationViews() + "].";
    }
}
