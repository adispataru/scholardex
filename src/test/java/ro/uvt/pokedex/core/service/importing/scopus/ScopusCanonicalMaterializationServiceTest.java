package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.observability.CanonicalObservabilityMetrics;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusCanonicalMaterializationServiceTest {

    @Mock
    private ScopusFactBuilderService factBuilderService;
    @Mock
    private UserDefinedFactBuilderService userDefinedFactBuilderService;
    @Mock
    private ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    @Mock
    private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Mock
    private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    @Mock
    private UserDefinedCanonicalizationService userDefinedCanonicalizationService;
    @Mock
    private ScholardexCitationCanonicalizationService citationCanonicalizationService;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeReconciliationService edgeReconciliationService;
    @Mock
    private ScholardexProjectionBuilderService projectionBuilderService;

    @Test
    void rebuildFactsAndViewsWithBatchIdUsesBatchScopedCanonicalizationAndProjection() {
        ScopusCanonicalMaterializationService service = service();
        when(projectionBuilderService.rebuildViewsForBatch("batch-1")).thenReturn(new ImportProcessingResult(0));

        service.rebuildFactsAndViews("scheduler-publication-task-1", "batch-1");

        ArgumentCaptor<CanonicalBuildOptions> optionsCaptor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
        verify(affiliationCanonicalizationService).rebuildCanonicalAffiliationFactsFromScopusFacts(optionsCaptor.capture());
        CanonicalBuildOptions options = optionsCaptor.getValue();
        assertEquals("batch-1", options.sourceBatchIdFilter());
        assertEquals(false, options.useCheckpoint());

        verify(authorCanonicalizationService).rebuildCanonicalAuthorFactsFromScopusFacts(any());
        verify(publicationCanonicalizationService).rebuildCanonicalPublicationFactsFromScopusFacts(any());
        verify(citationCanonicalizationService).rebuildCanonicalCitationFactsFromScopusFacts(any());
        verify(projectionBuilderService).rebuildViewsForBatch("batch-1");
        verify(projectionBuilderService, never()).rebuildViews();
        // H66B Phase 1: a Scopus-only incremental batch (no user-defined facts) skips the global user-defined canon.
        verify(userDefinedCanonicalizationService, never()).rebuildCanonicalFacts();
    }

    @Test
    void incrementalBatchThatProducedUserDefinedFactsStillRunsUserDefinedCanon() {
        ImportProcessingResult empty = new ImportProcessingResult(0);
        ImportProcessingResult userDefinedFacts = new ImportProcessingResult(0);
        userDefinedFacts.markProcessed(); // the batch contained user-defined records
        when(factBuilderService.buildFactsFromImportEvents("ud-batch")).thenReturn(empty);
        when(userDefinedFactBuilderService.buildFactsFromImportEvents("ud-batch")).thenReturn(userDefinedFacts);
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(empty);
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(empty);
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(empty);
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(empty);
        when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(empty);
        when(projectionBuilderService.rebuildViewsForBatch("ud-batch")).thenReturn(empty);

        bareService().rebuildFactsAndViews("user-defined-upload", "ud-batch");

        verify(userDefinedCanonicalizationService).rebuildCanonicalFacts();
    }

    @Test
    void rebuildFactsAndViewsWithoutBatchIdKeepsFullRescanBehavior() {
        ScopusCanonicalMaterializationService service = service();
        when(projectionBuilderService.rebuildViews()).thenReturn(new ImportProcessingResult(0));

        service.rebuildFactsAndViews("bootstrap");

        ArgumentCaptor<CanonicalBuildOptions> optionsCaptor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
        verify(affiliationCanonicalizationService).rebuildCanonicalAffiliationFactsFromScopusFacts(optionsCaptor.capture());
        assertNull(optionsCaptor.getValue().sourceBatchIdFilter());
        verify(projectionBuilderService).rebuildViews();
        verify(projectionBuilderService, never()).rebuildViewsForBatch(any());
        // full maintenance always runs the global user-defined canon
        verify(userDefinedCanonicalizationService).rebuildCanonicalFacts();
    }

    @Test
    void rebuildFactsAndViewsWithExplicitBatchScopedOptionsDisablesCheckpointResume() {
        ScopusCanonicalMaterializationService service = service();
        when(projectionBuilderService.rebuildViewsForBatch("batch-2")).thenReturn(new ImportProcessingResult(0));

        service.rebuildFactsAndViews(
                "scheduler-citation-task-1",
                "scheduler-batch",
                new CanonicalBuildOptions(500, null, true, "batch-2", null, false, false)
        );

        ArgumentCaptor<CanonicalBuildOptions> optionsCaptor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
        verify(affiliationCanonicalizationService).rebuildCanonicalAffiliationFactsFromScopusFacts(optionsCaptor.capture());
        CanonicalBuildOptions options = optionsCaptor.getValue();
        assertEquals("batch-2", options.sourceBatchIdFilter());
        assertEquals(false, options.useCheckpoint());
        verify(projectionBuilderService).rebuildViewsForBatch("batch-2");
        verify(projectionBuilderService, never()).rebuildViews();
        // incremental batch with no user-defined facts skips the global user-defined canon
        verify(userDefinedCanonicalizationService, never()).rebuildCanonicalFacts();
    }

    @Test
    void rebuildFactsAndViewsWithReconciliationEnabledUsesFullScopeRepairs() {
        ScopusCanonicalMaterializationService service = service();
        when(sourceLinkService.reconcileLinks()).thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(2L, 3L, 4L));
        when(edgeReconciliationService.reconcileEdges()).thenReturn(result(5, 6, 7));
        when(projectionBuilderService.rebuildViews()).thenReturn(result(8, 1, 0));

        service.rebuildFactsAndViews(
                "bootstrap",
                null,
                new CanonicalBuildOptions(250, null, true, null, "v1", true, true)
        );

        verify(factBuilderService).buildFactsFromImportEvents(null);
        verify(userDefinedFactBuilderService).buildFactsFromImportEvents(null);
        verify(sourceLinkService).reconcileLinks();
        verify(edgeReconciliationService).reconcileEdges();
        verify(edgeReconciliationService, never()).reconcileEdges(any());
        verify(projectionBuilderService).rebuildViews();
        verify(projectionBuilderService, never()).rebuildViewsForBatch(any());
    }

    @Test
    void buildIncrementalOptionsReturnsSameOptionsWhenBatchIdBlankAndNoSourceFilter() {
        ScopusCanonicalMaterializationService service = bareService();
        CanonicalBuildOptions options = new CanonicalBuildOptions(500, 7, true, null, "v2", true, true);

        CanonicalBuildOptions effective = ReflectionTestUtils.invokeMethod(service, "buildIncrementalOptions", "   ", options);

        assertSame(options, effective);
    }

    @Test
    void buildIncrementalOptionsAndIncrementalDetectionRespectBatchAndSourceFilter() {
        ScopusCanonicalMaterializationService service = bareService();
        CanonicalBuildOptions filtered = new CanonicalBuildOptions(500, 7, true, "batch-filter", "v2", true, true);
        CanonicalBuildOptions unfiltered = new CanonicalBuildOptions(500, 7, true, null, "v2", true, true);

        CanonicalBuildOptions preferredFilter =
                ReflectionTestUtils.invokeMethod(service, "buildIncrementalOptions", "scheduler-batch", filtered);
        CanonicalBuildOptions derivedBatch =
                ReflectionTestUtils.invokeMethod(service, "buildIncrementalOptions", "scheduler-batch", unfiltered);

        assertEquals("batch-filter", preferredFilter.sourceBatchIdFilter());
        assertEquals(false, preferredFilter.useCheckpoint());
        assertEquals("scheduler-batch", derivedBatch.sourceBatchIdFilter());
        assertEquals(false, derivedBatch.useCheckpoint());

        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "isIncrementalBatchRun", "   ", unfiltered));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "isIncrementalBatchRun", "scheduler-batch", unfiltered));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "isIncrementalBatchRun", null, filtered));
        assertEquals(false, ReflectionTestUtils.invokeMethod(service, "isIncrementalBatchRun", null, null));
        assertEquals(true, ReflectionTestUtils.invokeMethod(service, "isIncrementalBatchRun", "scheduler-batch", null));
    }

    @Test
    void buildIncrementalOptionsWithNullOptionsUsesDefaultsAndBatchId() {
        ScopusCanonicalMaterializationService service = bareService();

        CanonicalBuildOptions effective =
                ReflectionTestUtils.invokeMethod(service, "buildIncrementalOptions", "batch-9", null);

        assertEquals("batch-9", effective.sourceBatchIdFilter());
        assertEquals(false, effective.useCheckpoint());
    }

    @Test
    void reconcileEdgesUsesBatchScopedVariantWhenSourceBatchFilterPresent() {
        ScopusCanonicalMaterializationService service = bareService();
        when(edgeReconciliationService.reconcileEdges("batch-3")).thenReturn(result(9, 8, 7));

        ImportProcessingResult scoped = ReflectionTestUtils.invokeMethod(
                service,
                "reconcileEdges",
                new CanonicalBuildOptions(null, null, false, "batch-3", null, false, true)
        );

        assertEquals(9, scoped.getUpdatedCount());
        assertEquals(8, scoped.getSkippedCount());
        assertEquals(7, scoped.getErrorCount());
        verify(edgeReconciliationService).reconcileEdges("batch-3");
        verify(edgeReconciliationService, never()).reconcileEdges();
    }

    @Test
    void rebuildFactsAndViewsWithNullOptionsUsesFullMaintenanceDefaultsAndFailureMetrics() {
        ImportProcessingResult factFailure = result(0, 0, 1);
        when(factBuilderService.buildFactsFromImportEvents(null)).thenReturn(factFailure);
        when(userDefinedFactBuilderService.buildFactsFromImportEvents(null)).thenReturn(new ImportProcessingResult(0));
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(new ImportProcessingResult(0));
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(new ImportProcessingResult(0));
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(new ImportProcessingResult(0));
        when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(new ImportProcessingResult(0));
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(new ImportProcessingResult(0));
        when(projectionBuilderService.rebuildViews()).thenReturn(new ImportProcessingResult(0));
        ScopusCanonicalMaterializationService service = bareService();

        try (MockedStatic<CanonicalObservabilityMetrics> metrics = mockStatic(CanonicalObservabilityMetrics.class)) {
            service.rebuildFactsAndViews("bootstrap", null, null);

            ArgumentCaptor<CanonicalBuildOptions> optionsCaptor = ArgumentCaptor.forClass(CanonicalBuildOptions.class);
            verify(affiliationCanonicalizationService).rebuildCanonicalAffiliationFactsFromScopusFacts(optionsCaptor.capture());
            assertNull(optionsCaptor.getValue().sourceBatchIdFilter());
            verify(factBuilderService).buildFactsFromImportEvents(null);
            verify(userDefinedFactBuilderService).buildFactsFromImportEvents(null);
            verify(sourceLinkService, never()).reconcileLinks();
            verify(edgeReconciliationService, never()).reconcileEdges();
            verify(projectionBuilderService).rebuildViews();
            metrics.verify(() -> CanonicalObservabilityMetrics.recordCanonicalBuildRun(eq("all"), eq("CANONICAL_MIXED"), eq("failure"), anyLong()));
            metrics.verify(() -> CanonicalObservabilityMetrics.recordCanonicalBuildRun(eq("all"), eq("USER_DEFINED"), eq("failure"), anyLong()));
        }
    }

    private ImportProcessingResult result(int updated, int skipped, int errors) {
        ImportProcessingResult result = new ImportProcessingResult(0);
        for (int i = 0; i < updated; i++) {
            result.markUpdated();
        }
        for (int i = 0; i < skipped; i++) {
            result.markSkipped("skip-" + i);
        }
        for (int i = 0; i < errors; i++) {
            result.markError("error-" + i);
        }
        return result;
    }

    private ScopusCanonicalMaterializationService service() {
        ImportProcessingResult empty = new ImportProcessingResult(0);
        when(factBuilderService.buildFactsFromImportEvents(any())).thenReturn(empty);
        when(userDefinedFactBuilderService.buildFactsFromImportEvents(any())).thenReturn(empty);
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(empty);
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(empty);
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(empty);
        // H66B Phase 1: user-defined canon is now skipped on an incremental batch that produced no user-defined
        // facts, so this stub is only exercised on the full-maintenance paths — make it lenient.
        lenient().when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(empty);
        when(citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(any())).thenReturn(empty);
        return new ScopusCanonicalMaterializationService(
                factBuilderService,
                userDefinedFactBuilderService,
                affiliationCanonicalizationService,
                authorCanonicalizationService,
                publicationCanonicalizationService,
                userDefinedCanonicalizationService,
                citationCanonicalizationService,
                sourceLinkService,
                edgeReconciliationService,
                projectionBuilderService
        );
    }

    private ScopusCanonicalMaterializationService bareService() {
        return new ScopusCanonicalMaterializationService(
                factBuilderService,
                userDefinedFactBuilderService,
                affiliationCanonicalizationService,
                authorCanonicalizationService,
                publicationCanonicalizationService,
                userDefinedCanonicalizationService,
                citationCanonicalizationService,
                sourceLinkService,
                edgeReconciliationService,
                projectionBuilderService
        );
    }
}
