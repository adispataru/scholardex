package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.ScopusUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadRunResult;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.model.MigrationStepResult;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminIncrementalUpdatesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminIncrementalUpdatesControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncrementalUpdateUploadFacade incrementalUpdateUploadFacade;

    @Test
    void pageRendersTemplateAndLinks() throws Exception {
        mockMvc.perform(get("/admin/incremental-updates"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/incremental-updates"))
                .andExpect(content().string(containsString("/admin/incremental-updates/wos")))
                .andExpect(content().string(containsString("/admin/incremental-updates/scopus")))
                .andExpect(content().string(containsString("one uploaded file at a time")))
                .andExpect(content().string(containsString("broader maintenance, rebuild, reconcile, recovery, and downstream follow-up steps")))
                .andExpect(content().string(containsString("OFFICIAL_JSON")))
                .andExpect(content().string(containsString("GOVERNMENT_EXCEL")))
                .andExpect(content().string(containsString("Upload-scoped category enrichment and upload-scoped projection rebuild are available below after a successful WoS upload.")))
                .andExpect(content().string(containsString("It does not run projection rebuild, source-link reconciliation, edge reconciliation, or ensure-indexes.")))
                .andExpect(content().string(containsString("full-corpus WoS onboarding, recovery, canonical reset, or broader rebuild maintenance")))
                .andExpect(content().string(containsString("downstream Scopus maintenance after the upload")))
                .andExpect(content().string(containsString("/admin/incremental-updates/wos/enrichCategoryRankings")))
                .andExpect(content().string(containsString("/admin/incremental-updates/wos/rebuildProjections")))
                .andExpect(content().string(containsString("Last WoS upload context")))
                .andExpect(content().string(containsString("No successful WoS upload lineage is stored in this session yet")))
                .andExpect(content().string(containsString("/admin/incremental-updates/scopus/buildProjections")))
                .andExpect(content().string(containsString("/admin/incremental-updates/scopus/reconcileEdges")))
                .andExpect(content().string(containsString("/admin/incremental-updates/scopus/reconcileSourceLinks")))
                .andExpect(content().string(containsString("Last Scopus upload context")))
                .andExpect(content().string(containsString("No successful Scopus upload is stored in this session yet")))
                .andExpect(content().string(containsString("/admin/initialization")));
    }

    @Test
    void validWosUploadRedirectsAndPassesRequestToFacade() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "wos.json", "application/json", "{\"items\":[]}".getBytes());
        when(incrementalUpdateUploadFacade.acceptWosUpload(argThat(request ->
                request.sourceType() == WosUploadSourceType.OFFICIAL_JSON
                        && "2026-Q1".equals(request.sourceVersion())
                        && "wos.json".equals(request.file().originalFilename())
                        && request.file().sizeBytes() > 0
        ))).thenReturn(wosRunResult("wos.json", "2026-Q1"));

        mockMvc.perform(multipart("/admin/incremental-updates/wos")
                        .file(file)
                        .param("sourceType", "OFFICIAL_JSON")
                        .param("sourceVersion", "2026-Q1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("WoS incremental upload complete for wos.json [sourceType=OFFICIAL_JSON, sourceVersion=2026-Q1]")))
                .andExpect(flash().attribute("successMessage", containsString("resumedFromCheckpoint=false")))
                .andExpect(flash().attribute("successMessage", containsString("Use the WoS post-upload actions below only to continue this stored upload lineage.")));

        verify(incrementalUpdateUploadFacade).acceptWosUpload(argThat(request ->
                request.sourceType() == WosUploadSourceType.OFFICIAL_JSON
                        && "2026-Q1".equals(request.sourceVersion())
                        && "wos.json".equals(request.file().originalFilename())
        ));
    }

    @Test
    void wosPostUploadActionsRejectMissingSessionContext() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/wos/enrichCategoryRankings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", containsString("No successful WoS upload lineage is stored in this session yet")));

        verifyNoInteractions(incrementalUpdateUploadFacade);
    }

    @Test
    void wosCategoryEnrichmentUsesStoredUploadContext() throws Exception {
        ImportProcessingResult enrichmentResult = new ImportProcessingResult(10);
        enrichmentResult.markProcessed();
        enrichmentResult.markUpdated();
        enrichmentResult.markSkipped("already-present");
        when(incrementalUpdateUploadFacade.enrichWosUploadCategoryRankings(WosUploadSourceType.OFFICIAL_JSON, "wos.json", "2026-Q1"))
                .thenReturn(enrichmentResult);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/wos/enrichCategoryRankings")
                        .sessionAttr("h29.wos.lastUploadSourceType", "OFFICIAL_JSON")
                        .sessionAttr("h29.wos.lastUploadFilename", "wos.json")
                        .sessionAttr("h29.wos.lastUploadSourceVersion", "2026-Q1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("WoS category enrichment complete for last uploaded lineage [wos.json, sourceType=OFFICIAL_JSON, sourceVersion=2026-Q1].")))
                .andExpect(flash().attribute("successMessage", containsString("Enrichment[p=1, i=0, u=1, s=1, e=0]")));

        verify(incrementalUpdateUploadFacade).enrichWosUploadCategoryRankings(WosUploadSourceType.OFFICIAL_JSON, "wos.json", "2026-Q1");
    }

    @Test
    void wosProjectionRebuildUsesStoredUploadContext() throws Exception {
        ImportProcessingResult projectionResult = new ImportProcessingResult(10);
        projectionResult.markProcessed();
        projectionResult.markImported();
        projectionResult.markImported();
        when(incrementalUpdateUploadFacade.rebuildWosUploadProjections(WosUploadSourceType.GOVERNMENT_EXCEL, "AIS_2026.xlsx", "2026-Q1"))
                .thenReturn(projectionResult);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/wos/rebuildProjections")
                        .sessionAttr("h29.wos.lastUploadSourceType", "GOVERNMENT_EXCEL")
                        .sessionAttr("h29.wos.lastUploadFilename", "AIS_2026.xlsx")
                        .sessionAttr("h29.wos.lastUploadSourceVersion", "2026-Q1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("WoS projection rebuild complete for last uploaded lineage [AIS_2026.xlsx, sourceType=GOVERNMENT_EXCEL, sourceVersion=2026-Q1].")))
                .andExpect(flash().attribute("successMessage", containsString("Projections[p=1, i=2, u=0, s=0, e=0]")));

        verify(incrementalUpdateUploadFacade).rebuildWosUploadProjections(WosUploadSourceType.GOVERNMENT_EXCEL, "AIS_2026.xlsx", "2026-Q1");
    }

    @Test
    void validScopusUploadRedirectsAndPassesPayloadToFacade() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "scopus.json", "application/json", "{\"eid\":[]}".getBytes());
        when(incrementalUpdateUploadFacade.acceptScopusUpload(argThat(payload ->
                "scopus.json".equals(payload.originalFilename()) && payload.sizeBytes() > 0
        ))).thenReturn(scopusRunResult("scopus.json"));

        mockMvc.perform(multipart("/admin/incremental-updates/scopus").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("Scopus incremental upload complete for scopus.json.")))
                .andExpect(flash().attribute("successMessage", containsString("Facts[p=1, i=1, u=1, s=1, e=0]")));

        verify(incrementalUpdateUploadFacade).acceptScopusUpload(argThat(payload ->
                "scopus.json".equals(payload.originalFilename()) && payload.sizeBytes() > 0
        ));
    }

    @Test
    void scopusPostUploadActionsRejectMissingSessionContext() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/scopus/buildProjections"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", containsString("No successful Scopus upload is stored in this session yet")));

        verifyNoInteractions(incrementalUpdateUploadFacade);
    }

    @Test
    void scopusProjectionFollowUpUsesStoredUploadContext() throws Exception {
        ImportProcessingResult projectionResult = new ImportProcessingResult(10);
        projectionResult.markProcessed();
        projectionResult.markImported();
        projectionResult.markUpdated();
        projectionResult.markSkipped("already-present");
        when(incrementalUpdateUploadFacade.rebuildScopusUploadProjections("upload-batch-7"))
                .thenReturn(MigrationStepResult.executed("build-projections", projectionResult));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/scopus/buildProjections")
                        .sessionAttr("h29.scopus.lastUploadBatchId", "upload-batch-7")
                        .sessionAttr("h29.scopus.lastUploadFilename", "scopus.json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("Scopus projection rebuild complete for last uploaded batch [scopus.json].")))
                .andExpect(flash().attribute("successMessage", containsString("Projections[p=1, i=1, u=1, s=1, e=0]")));

        verify(incrementalUpdateUploadFacade).rebuildScopusUploadProjections("upload-batch-7");
    }

    @Test
    void scopusEdgeReconcileFollowUpUsesStoredUploadContext() throws Exception {
        ImportProcessingResult edgeResult = new ImportProcessingResult(10);
        edgeResult.markProcessed();
        edgeResult.markUpdated();
        when(incrementalUpdateUploadFacade.reconcileScopusUploadEdges("upload-batch-7")).thenReturn(edgeResult);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/scopus/reconcileEdges")
                        .sessionAttr("h29.scopus.lastUploadBatchId", "upload-batch-7")
                        .sessionAttr("h29.scopus.lastUploadFilename", "scopus.json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("Scopus edge reconcile complete for last uploaded batch [scopus.json].")))
                .andExpect(flash().attribute("successMessage", containsString("Edges[p=1, u=1, s=0, e=0]")));

        verify(incrementalUpdateUploadFacade).reconcileScopusUploadEdges("upload-batch-7");
    }

    @Test
    void scopusSourceLinkRepairUsesStoredUploadContext() throws Exception {
        when(incrementalUpdateUploadFacade.repairScopusSourceLinks())
                .thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(2, 3, 0));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/incremental-updates/scopus/reconcileSourceLinks")
                        .sessionAttr("h29.scopus.lastUploadBatchId", "upload-batch-7")
                        .sessionAttr("h29.scopus.lastUploadFilename", "scopus.json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("successMessage", containsString("Scopus source-link ledger repair complete after last uploaded batch [scopus.json].")))
                .andExpect(flash().attribute("successMessage", containsString("Repair[updated=2, skipped=3, errors=0]")));

        verify(incrementalUpdateUploadFacade).repairScopusSourceLinks();
    }

    @Test
    void scopusFacadeValidationErrorIsShownClearly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "scopus.json", "application/json", "{bad".getBytes());
        when(incrementalUpdateUploadFacade.acceptScopusUpload(argThat(payload ->
                "scopus.json".equals(payload.originalFilename())
        ))).thenThrow(new IllegalArgumentException("Failed to parse uploaded Scopus JSON file."));

        mockMvc.perform(multipart("/admin/incremental-updates/scopus").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "Failed to parse uploaded Scopus JSON file."));
    }

    @Test
    void emptyUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "", "application/json", new byte[0]);

        mockMvc.perform(multipart("/admin/incremental-updates/scopus").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "Please select a file to upload."));
    }

    @Test
    void wosRejectsMismatchedSelectorAndFileExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "AIS_2024.xlsx", "application/vnd.ms-excel", "xlsx".getBytes());

        mockMvc.perform(multipart("/admin/incremental-updates/wos")
                        .file(file)
                        .param("sourceType", "OFFICIAL_JSON"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "WoS official JSON uploads require a .json file."));
    }

    @Test
    void scopusRejectsNonJsonUploads() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "scopus.xlsx", "application/vnd.ms-excel", "xlsx".getBytes());

        mockMvc.perform(multipart("/admin/incremental-updates/scopus").file(file))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "Scopus incremental uploads require a .json file."));
    }

    @Test
    void invalidWosSourceTypeIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "wos.json", "application/json", "{}".getBytes());

        mockMvc.perform(multipart("/admin/incremental-updates/wos")
                        .file(file)
                        .param("sourceType", "INVALID"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "Invalid WoS source type."));
    }

    @Test
    void wosFacadeValidationErrorIsShownClearly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "WOS_2024.xlsx", "application/vnd.ms-excel", "xlsx".getBytes());
        when(incrementalUpdateUploadFacade.acceptWosUpload(argThat(request ->
                request.sourceType() == WosUploadSourceType.GOVERNMENT_EXCEL
                        && "WOS_2024.xlsx".equals(request.file().originalFilename())
        ))).thenThrow(new IllegalArgumentException("WoS government Excel filename must start with AIS_ or RIS_."));

        mockMvc.perform(multipart("/admin/incremental-updates/wos")
                        .file(file)
                        .param("sourceType", "GOVERNMENT_EXCEL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/incremental-updates"))
                .andExpect(flash().attribute("errorMessage", "WoS government Excel filename must start with AIS_ or RIS_."));
    }

    private WosUploadRunResult wosRunResult(String fileName, String sourceVersion) {
        ImportProcessingResult ingest = new ImportProcessingResult(10);
        ingest.markProcessed();
        ingest.markImported();
        ImportProcessingResult facts = new ImportProcessingResult(10);
        facts.markProcessed();
        facts.markUpdated();
        return new WosUploadRunResult(
                WosUploadSourceType.OFFICIAL_JSON,
                fileName,
                sourceVersion,
                ingest,
                new WosFactBuilderService.FactBuildRunResult(facts, 0, 0, 1, false, -1),
                "Upload-scoped fact building, upload-scoped category enrichment, and upload-scoped projection rebuild remain tied to the uploaded lineage. WoS onboarding is still excluded from this incremental path."
        );
    }

    private ScopusUploadRunResult scopusRunResult(String fileName) {
        ImportProcessingResult publications = new ImportProcessingResult(10);
        publications.markProcessed();
        publications.markImported();
        ImportProcessingResult citations = new ImportProcessingResult(10);
        citations.markProcessed();
        citations.markImported();
        citations.markSkipped("duplicate");
        ImportProcessingResult facts = new ImportProcessingResult(10);
        facts.markProcessed();
        facts.markImported();
        facts.markUpdated();
        facts.markSkipped("skipped");
        return new ScopusUploadRunResult(
                fileName,
                "upload-batch-1",
                publications,
                citations,
                MigrationStepResult.executed("ingest", combine(publications, citations)),
                MigrationStepResult.executed("build-facts", facts),
                "Projection rebuild, source-link reconciliation, edge reconciliation, and ensure-indexes were intentionally skipped in H29.3."
        );
    }

    private ImportProcessingResult combine(ImportProcessingResult left, ImportProcessingResult right) {
        ImportProcessingResult combined = new ImportProcessingResult(10);
        for (int i = 0; i < left.getProcessedCount() + right.getProcessedCount(); i++) {
            combined.markProcessed();
        }
        for (int i = 0; i < left.getImportedCount() + right.getImportedCount(); i++) {
            combined.markImported();
        }
        for (int i = 0; i < left.getUpdatedCount() + right.getUpdatedCount(); i++) {
            combined.markUpdated();
        }
        for (int i = 0; i < left.getSkippedCount() + right.getSkippedCount(); i++) {
            combined.markSkipped("s" + i);
        }
        for (int i = 0; i < left.getErrorCount() + right.getErrorCount(); i++) {
            combined.markError("e" + i);
        }
        return combined;
    }
}
