package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.UserDefinedCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.UserDefinedFactBuilderService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDefinedMaintenanceOrchestrationServiceTest {

    @Mock
    private UserDefinedFactBuilderService userDefinedFactBuilderService;
    @Mock
    private UserDefinedCanonicalizationService userDefinedCanonicalizationService;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeReconciliationService edgeReconciliationService;
    @Mock
    private ScopusProjectionBuilderService projectionBuilderService;

    @Test
    void runAllExecutesStepsInOrderWithAllOptionsEnabled() {
        ImportProcessingResult buildFacts = result(5, 0);
        ImportProcessingResult canonicalize = result(3, 0);
        ScholardexSourceLinkService.ImportRepairSummary sourceReconcile =
                new ScholardexSourceLinkService.ImportRepairSummary(4L, 1L, 0L);
        ImportProcessingResult edgeReconcile = result(2, 0);
        ImportProcessingResult projections = result(7, 0);

        when(userDefinedFactBuilderService.buildFactsFromImportEvents("batch-42")).thenReturn(buildFacts);
        when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(canonicalize);
        when(sourceLinkService.reconcileLinks()).thenReturn(sourceReconcile);
        when(edgeReconciliationService.reconcileEdges()).thenReturn(edgeReconcile);
        when(projectionBuilderService.rebuildViews()).thenReturn(projections);

        UserDefinedMaintenanceOrchestrationService service = new UserDefinedMaintenanceOrchestrationService(
                userDefinedFactBuilderService,
                userDefinedCanonicalizationService,
                sourceLinkService,
                edgeReconciliationService,
                projectionBuilderService
        );

        var summary = service.runAll("batch-42", true, true, true);

        assertEquals(5, summary.buildFacts().getProcessedCount());
        assertEquals(3, summary.canonicalize().getProcessedCount());
        assertEquals(4L, summary.sourceLinkReconcile().updated());
        assertEquals(2, summary.edgeReconcile().getProcessedCount());
        assertEquals(7, summary.projections().getProcessedCount());

        InOrder inOrder = inOrder(
                userDefinedFactBuilderService,
                userDefinedCanonicalizationService,
                sourceLinkService,
                edgeReconciliationService,
                projectionBuilderService
        );
        inOrder.verify(userDefinedFactBuilderService).buildFactsFromImportEvents("batch-42");
        inOrder.verify(userDefinedCanonicalizationService).rebuildCanonicalFacts();
        inOrder.verify(sourceLinkService).reconcileLinks();
        inOrder.verify(edgeReconciliationService).reconcileEdges();
        inOrder.verify(projectionBuilderService).rebuildViews();
    }

    @Test
    void runCanonicalizeSkipsOptionalMaintenanceWhenDisabled() {
        ImportProcessingResult canonicalize = result(3, 0);
        when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(canonicalize);

        UserDefinedMaintenanceOrchestrationService service = new UserDefinedMaintenanceOrchestrationService(
                userDefinedFactBuilderService,
                userDefinedCanonicalizationService,
                sourceLinkService,
                edgeReconciliationService,
                projectionBuilderService
        );

        var summary = service.runCanonicalizeStep(false, false, false);

        assertEquals(0, summary.buildFacts().getProcessedCount());
        assertEquals(3, summary.canonicalize().getProcessedCount());
        assertEquals(0L, summary.sourceLinkReconcile().updated());
        assertEquals(0, summary.edgeReconcile().getProcessedCount());
        assertEquals(0, summary.projections().getProcessedCount());

        verify(userDefinedFactBuilderService, never()).buildFactsFromImportEvents(org.mockito.ArgumentMatchers.any());
        verify(sourceLinkService, never()).reconcileLinks();
        verify(edgeReconciliationService, never()).reconcileEdges();
        verify(projectionBuilderService, never()).rebuildViews();
    }

    private static ImportProcessingResult result(int processed, int errors) {
        ImportProcessingResult result = new ImportProcessingResult(0);
        for (int i = 0; i < processed; i++) {
            result.markProcessed();
        }
        for (int i = 0; i < errors; i++) {
            result.markError("err-" + i);
        }
        return result;
    }
}
