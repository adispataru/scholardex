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
import ro.uvt.pokedex.core.service.application.PostgresOperationalStatusService;
import ro.uvt.pokedex.core.service.application.PostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.PostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.ScopusBigBangMigrationService;
import ro.uvt.pokedex.core.service.application.UserDefinedMaintenanceOrchestrationService;
import ro.uvt.pokedex.core.service.application.model.WosEnrichmentRunSummaryDto;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;

@Controller
@RequestMapping("/admin/initialization")
@RequiredArgsConstructor
public class AdminInitializationController {

    private final GeneralInitializationService generalInitializationService;
    private final RankingMaintenanceFacade rankingMaintenanceFacade;
    private final ScopusBigBangMigrationService scopusBigBangMigrationService;
    private final ro.uvt.pokedex.core.service.importing.ScopusDataService scopusDataService;
    private final ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService wosImportEventIngestionService;
    private final ro.uvt.pokedex.core.service.application.PipelineRebuildService pipelineRebuildService;
    private final UserDefinedMaintenanceOrchestrationService userDefinedMaintenanceOrchestrationService;
    private final ObjectProvider<PostgresReportingProjectionService> postgresReportingProjectionServiceProvider;
    private final ObjectProvider<PostgresMaterializedViewRefreshService> postgresMaterializedViewRefreshServiceProvider;
    private final ObjectProvider<PostgresOperationalStatusService> postgresOperationalStatusServiceProvider;
    private final ro.uvt.pokedex.core.service.application.ForumReconcileAuditService forumReconcileAuditService;
    private final ro.uvt.pokedex.core.service.importing.DoajDataService doajDataService;
    private final ro.uvt.pokedex.core.service.importing.ErihDataService erihDataService;
    private final ro.uvt.pokedex.core.service.application.ErihOnboardingService erihOnboardingService;
    private final ro.uvt.pokedex.core.service.application.DoajOnboardingService doajOnboardingService;
    private final ro.uvt.pokedex.core.service.application.ScholardexForumDeduplicationService scholardexForumDeduplicationService;
    private final ro.uvt.pokedex.core.service.application.ForumReconcileService forumReconcileService;
    private final ro.uvt.pokedex.core.service.application.AuthorReconcileService authorReconcileService;
    private final ro.uvt.pokedex.core.service.openalex.OpenAlexBulkImportService openAlexBulkImportService;
    private final ro.uvt.pokedex.core.service.importing.scopus.OpenAlexCanonicalizationService openAlexCanonicalizationService;
    private final ro.uvt.pokedex.core.service.importing.wos.WosCpciOnboardingService wosCpciOnboardingService;

    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.works-file:}")
    private String openAlexWorksFile;
    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.citers-file:}")
    private String openAlexCitersFile;
    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.institutions-dir:}")
    private String openAlexInstitutionsDir;

    @GetMapping
    public String showInitializationPage(Model model) {
        return "admin/initialization";
    }

    /**
     * H76 S1: dry-run the WoS CPCI onboarding match (no writes) — reports per-key match rate + the distinct forums
     * that would be tagged WoS-indexed. Reads the configured {@code wos.cpci.file}.
     */
    @PostMapping("/wos/cpci/dryRun")
    @ResponseBody
    public ro.uvt.pokedex.core.service.importing.wos.WosCpciMatchReport wosCpciDryRun() {
        return wosCpciOnboardingService.dryRun();
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

    @PostMapping("/general/dblpLnChapterEnrichment")
    public String runGeneralDblpLnChapterEnrichment(RedirectAttributes redirectAttributes) {
        return redirectAfterGeneralStep(generalInitializationService.runDblpLnChapterEnrichment(), redirectAttributes);
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

    /**
     * H66 C2.2 — read-only verification of the canonical forum registry after the one-time full rebuild
     * (no orphaned publication→forum links; WoS-linked forums resolve metrics by FK). Mutates nothing.
     */
    @GetMapping("/forum/reconcileAudit")
    @ResponseBody
    public ro.uvt.pokedex.core.service.application.model.ForumReconcileAuditReport forumReconcileAudit() {
        return forumReconcileAuditService.audit();
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
                        + ", canonicalForums=" + result.canonicalForums()
                        + ", forumSourceLinks=" + result.forumSourceLinks()
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

    /**
     * H66 A6 — load the Scopus Source List (ext_list xlsx) as the serial forum backbone (FORUM events keyed
     * by Sourcerecord ID). Run before buildFacts so the registry is seeded from Scopus coverage.
     */
    @PostMapping("/scopus/importSourceList")
    public String runScopusImportSourceList(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "batchId", required = false) String batchId,
            RedirectAttributes redirectAttributes
    ) {
        String effectiveBatchId = (batchId == null || batchId.isBlank())
                ? "sourcelist-" + java.time.Instant.now().toEpochMilli()
                : batchId;
        var result = scopusDataService.importSourceListXlsxFromPath(path, effectiveBatchId);
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus Source List import complete (batchId=" + effectiveBatchId + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/importBookList")
    public String runScopusImportBookList(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "asOf", required = false) String asOf,
            @RequestParam(name = "batchId", required = false) String batchId,
            RedirectAttributes redirectAttributes
    ) {
        String effectiveBatchId = (batchId == null || batchId.isBlank())
                ? "booklist-" + java.time.Instant.now().toEpochMilli()
                : batchId;
        var result = scopusDataService.importBookListXlsxFromPath(path, effectiveBatchId, asOf);
        redirectAttributes.addFlashAttribute("successMessage",
                "Scopus Books List import complete (batchId=" + effectiveBatchId + "). processed="
                        + result.getProcessedCount() + ", imported=" + result.getImportedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/importCiteScore")
    public String runScopusImportCiteScore(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "batchId", required = false) String batchId,
            RedirectAttributes redirectAttributes
    ) {
        String effectiveBatchId = (batchId == null || batchId.isBlank())
                ? "citescore-" + java.time.Instant.now().toEpochMilli()
                : batchId;
        var result = scopusDataService.importCiteScoreCsvFromPath(path, effectiveBatchId);
        redirectAttributes.addFlashAttribute("successMessage",
                "CiteScore import complete (batchId=" + effectiveBatchId + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66 A4 — load the DOAJ open-access snapshot CSV into doaj.journal_facts (match-only reference data;
     * projected to membership_view database='DOAJ' by ISSN). {@code asOf} stamps the snapshot (e.g. dump year).
     */
    @PostMapping("/forum/importDoaj")
    public String runDoajImport(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "batchId", required = false) String batchId,
            @RequestParam(name = "asOf", required = false) String asOf,
            RedirectAttributes redirectAttributes
    ) {
        String effectiveBatchId = (batchId == null || batchId.isBlank())
                ? "doaj-" + java.time.Instant.now().toEpochMilli()
                : batchId;
        var result = doajDataService.importDoajCsvFromPath(path, effectiveBatchId, asOf);
        redirectAttributes.addFlashAttribute("successMessage",
                "DOAJ import complete (batchId=" + effectiveBatchId + ", asOf=" + asOf + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66 A5 — load the ERIH PLUS snapshot JSONL into erih.journal_facts (reference data).
     */
    @PostMapping("/forum/importErih")
    public String runErihImport(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "batchId", required = false) String batchId,
            @RequestParam(name = "asOf", required = false) String asOf,
            RedirectAttributes redirectAttributes
    ) {
        String effectiveBatchId = (batchId == null || batchId.isBlank())
                ? "erih-" + java.time.Instant.now().toEpochMilli()
                : batchId;
        var result = erihDataService.importErihJsonlFromPath(path, effectiveBatchId, asOf);
        redirectAttributes.addFlashAttribute("successMessage",
                "ERIH import complete (batchId=" + effectiveBatchId + ", asOf=" + asOf + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66 A5 — populate canonical forums with erihIds by ISSN match (match-only). Run after a forum rebuild
     * and before {@code /forum/dedup} so the C1-part-2 shared-erihId merge composes.
     */
    @PostMapping("/forum/onboardErih")
    public String runErihOnboarding(RedirectAttributes redirectAttributes) {
        var result = erihOnboardingService.onboardErih();
        redirectAttributes.addFlashAttribute("successMessage",
                "ERIH onboarding complete. processed=" + result.getProcessedCount()
                        + ", forumsUpdated=" + result.getUpdatedCount()
                        + ", unmatched=" + result.getSkippedCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66B M4-B — onboard DOAJ as a create-or-match identity source (tag matches by ISSN / mint DOAJ-only
     * venues). Standalone re-run; in a full rebuild it runs inside the forum build after ERIH.
     */
    @PostMapping("/forum/onboardDoaj")
    public String runDoajOnboarding(RedirectAttributes redirectAttributes) {
        var result = doajOnboardingService.onboardDoaj();
        redirectAttributes.addFlashAttribute("successMessage",
                "DOAJ onboarding complete. processed=" + result.getProcessedCount()
                        + ", forumsTagged=" + result.getUpdatedCount()
                        + ", forumsCreated=" + result.getImportedCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66 C1 part 2 — standalone forum dedup (clusters by shared ISSN + erihId, safe-merge or quarantine).
     * Lets a dedup pass run after ERIH onboarding without re-running the whole Scopus build.
     */
    @PostMapping("/forum/dedup")
    public String runForumDedup(RedirectAttributes redirectAttributes) {
        var result = scholardexForumDeduplicationService.deduplicateForums(
                "forum-dedup-" + java.time.Instant.now().toEpochMilli(), "manual");
        redirectAttributes.addFlashAttribute("successMessage",
                "Forum dedup complete. clusters=" + result.getProcessedCount()
                        + ", merged(updated)=" + result.getUpdatedCount()
                        + ", quarantined(skipped)=" + result.getSkippedCount()
                        + ", forumsRemoved=" + result.getImportedCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H66B Phase 3 — manual trigger for the Tier-1 forum reconcile (the cold-path partner of the incremental
     * resolve): full forum build (dedup → canon → ERIH/DOAJ onboarding → WoS onboarding → membership dedup →
     * M10 relink) + projection refresh. Collapses transient duplicates that incremental uploads minted. The
     * nightly scheduler runs the same {@code ForumReconcileService.reconcile}; this is the on-demand path.
     */
    @PostMapping("/forum/reconcile")
    public String runForumReconcile(RedirectAttributes redirectAttributes) {
        var result = forumReconcileService.reconcile("admin-manual");
        redirectAttributes.addFlashAttribute("successMessage",
                "Forum reconcile complete. membershipDedupMerged=" + result.forumBuild().membershipDedup().getUpdatedCount()
                        + ", wosRelinked=" + (result.forumBuild().wosRelink().getUpdatedCount()
                                + result.forumBuild().wosRelink().getImportedCount())
                        + ", projectionRows=" + result.projection().getProcessedCount()
                        + ", projectionErrors=" + result.projection().getErrorCount());
        return "redirect:/admin/initialization";
    }

    /**
     * H73 slice 1 — manual trigger for the file-driven OpenAlex bulk import (works + citers → source facts;
     * referenced institutions → ROR affiliation backbone), reading the configured {@code core.openalex.bulk.*}
     * paths. Lets the bulk import + backbone be (re)built on demand without a full ~36-min rebuild.
     */
    @PostMapping("/openalex/bulkImport")
    public String runOpenAlexBulkImport(RedirectAttributes redirectAttributes) {
        java.nio.file.Path works = pathOrNull(openAlexWorksFile);
        java.nio.file.Path citers = pathOrNull(openAlexCitersFile);
        java.nio.file.Path institutions = pathOrNull(openAlexInstitutionsDir);
        try {
            var r = openAlexBulkImportService.importAll(works, citers, institutions,
                    "openalex-bulk-manual", "admin-manual");
            redirectAttributes.addFlashAttribute("successMessage",
                    "OpenAlex bulk import complete. works=" + r.worksImported()
                            + ", citers=" + r.citersImported()
                            + ", referencedInstitutions=" + r.referencedInstitutions()
                            + ", backboneAffiliations=" + r.backboneInstitutions() + ".");
        } catch (java.io.IOException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "OpenAlex bulk import failed: " + e.getMessage());
        }
        return "redirect:/admin/initialization";
    }

    private static java.nio.file.Path pathOrNull(String path) {
        return path == null || path.isBlank() ? null : java.nio.file.Path.of(path);
    }

    /**
     * H73 slice 3 — manual trigger for OpenAlex canonicalization (source facts → canonical pubs/authors +
     * authorship and pub→author→affiliation edges). Lets the OpenAlex canonical layer (incl. the new affiliation
     * edges) be (re)built on demand without a full ~36-min rebuild. Run after {@code /openalex/bulkImport}.
     */
    @PostMapping("/openalex/canonicalize")
    public String runOpenAlexCanonicalize(RedirectAttributes redirectAttributes) {
        var result = openAlexCanonicalizationService.rebuildCanonicalFacts();
        redirectAttributes.addFlashAttribute("successMessage",
                "OpenAlex canonicalization complete. processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/author/reconcile")
    public String runAuthorReconcile(RedirectAttributes redirectAttributes) {
        var result = authorReconcileService.reconcileByOrcid("admin-manual", "admin-manual");
        redirectAttributes.addFlashAttribute("successMessage",
                "Author reconcile (ORCID pass) complete. clusters=" + result.getProcessedCount()
                        + ", authorsMerged=" + result.getImportedCount()
                        + ", quarantined=" + result.getSkippedCount() + ".");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/author/reconcile/fuzzy")
    public String runAuthorFuzzyReconcile(RedirectAttributes redirectAttributes) {
        var result = authorReconcileService.reconcileByName("admin-manual", "admin-manual");
        redirectAttributes.addFlashAttribute("successMessage",
                "Author reconcile (fuzzy name pass) complete. mergeGroups=" + result.getProcessedCount()
                        + ", authorsMerged=" + result.getImportedCount()
                        + ", reportedCandidates=" + result.getSkippedCount()
                        + " (dry-run unless core.author-reconcile.fuzzy-apply=true).");
        return "redirect:/admin/initialization";
    }

    @PostMapping("/author/reconcile/affiliation")
    public String runAuthorOverSplitReconcile(RedirectAttributes redirectAttributes) {
        var result = authorReconcileService.reconcileByNameAndAffiliation("admin-manual", "admin-manual");
        redirectAttributes.addFlashAttribute("successMessage",
                "Author reconcile (name+affiliation over-split pass) complete. mergeGroups=" + result.getProcessedCount()
                        + ", authorsMerged=" + result.getImportedCount()
                        + ", reportedCandidates=" + result.getSkippedCount()
                        + " (dry-run unless core.author-reconcile.affiliation-apply=true).");
        return "redirect:/admin/initialization";
    }


    @PostMapping("/wos/importMjl")
    public String runWosImportMjl(
            @RequestParam(name = "dir", defaultValue = "data/wos/mjl") String dir,
            @RequestParam(name = "sourceVersion", required = false) String sourceVersion,
            RedirectAttributes redirectAttributes
    ) {
        var result = wosImportEventIngestionService.ingestMjlDirectory(dir, sourceVersion);
        redirectAttributes.addFlashAttribute("successMessage",
                "WoS MJL ingest complete (dir=" + dir + ", sourceVersion=" + (sourceVersion == null ? "2025" : sourceVersion)
                        + "). processed=" + result.getProcessedCount()
                        + ", imported=" + result.getImportedCount()
                        + ", updated=" + result.getUpdatedCount()
                        + ", skipped=" + result.getSkippedCount()
                        + ", errors=" + result.getErrorCount());
        return "redirect:/admin/initialization";
    }

    @PostMapping("/scopus/buildFacts")
    public String runScopusBuildFacts(
            @RequestParam(name = "startBatchOverride", required = false) Integer startBatchOverride,
            @RequestParam(name = "useCheckpoint", defaultValue = "true") boolean useCheckpoint,
            @RequestParam(name = "chunkSizeOverride", required = false) Integer chunkSizeOverride,
            @RequestParam(name = "skipUnchanged", defaultValue = "false") boolean skipUnchanged,
            RedirectAttributes redirectAttributes
    ) {
        var result = scopusBigBangMigrationService.runBuildFactsStep(startBatchOverride, useCheckpoint, chunkSizeOverride, skipUnchanged);
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
            @RequestParam(name = "chunkSizeOverride", required = false) Integer chunkSizeOverride,
            RedirectAttributes redirectAttributes
    ) {
        var result = scopusBigBangMigrationService.runCanonicalBuildStep(
                entity,
                startBatchOverride,
                useCheckpoint,
                chunkSizeOverride,
                reconcileSourceLinks,
                reconcileEdges
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
                        + ".");
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

    @PostMapping("/postgres/operational/showStatus")
    public String showPostgresOperationalStatus(RedirectAttributes redirectAttributes) {
        PostgresOperationalStatusService service = postgresOperationalStatusServiceProvider.getIfAvailable();
        if (service == null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Postgres operational status service is disabled."
            );
            return "redirect:/admin/initialization";
        }
        var snapshot = service.latestStatus();
        redirectAttributes.addFlashAttribute("successMessage",
                "Postgres operational status: state=" + snapshot.overallState()
                        + ", readStore=" + snapshot.readStore()
                        + ", projection=" + snapshot.projection().status()
                        + ", materialized=" + snapshot.materializedViewRefresh().status()
                        + ".");
        return "redirect:/admin/initialization";
    }

    @GetMapping("/postgres/operational/status")
    @ResponseBody
    public PostgresOperationalStatusService.PostgresOperationalStatusSnapshot postgresOperationalStatusApi() {
        PostgresOperationalStatusService service = postgresOperationalStatusServiceProvider.getIfAvailable();
        if (service == null) {
            return PostgresOperationalStatusService.PostgresOperationalStatusSnapshot.unavailable();
        }
        return service.latestStatus();
    }

    @PostMapping("/rebuildAllDerived")
    public String rebuildAllDerived(
            @RequestParam(name = "confirmation", required = false) String confirmation,
            @RequestParam(name = "reingest", required = false, defaultValue = "false") boolean reingest,
            RedirectAttributes redirectAttributes
    ) {
        if (!"RESET".equals(confirmation == null ? null : confirmation.trim())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Full derived-data rebuild aborted. Type RESET in the confirmation field to proceed."
            );
            return "redirect:/admin/initialization";
        }
        // Single guarded full-rebuild entry point (H58/#2): true full wipe (all owned managed collections,
        // regardless of source attribution) then re-derive WoS + Scopus from source files. Preferred over
        // the per-source reset chain, whose canonical wipes are source-scoped.
        // H75 skip-smart: when the source/stage-2 facts are already present, the rebuild re-derives the canonical
        // layer only (~5 min) instead of re-ingesting (~33 min). Pass reingest=true to force a full re-ingest
        // (use this whenever a source file has actually changed).
        var result = pipelineRebuildService.rebuildAllDerivedFromSource(reingest);
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Full derived-data rebuild complete (WoS + Scopus re-derived from source after a full wipe). "
                        + result);
        return "redirect:/admin/initialization";
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
                        + "."
        );
        return "redirect:/admin/initialization";
    }

    private String formatWosStep(String label, MigrationStepResult step) {
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

    private String formatScopusStep(String label, MigrationStepResult step) {
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
