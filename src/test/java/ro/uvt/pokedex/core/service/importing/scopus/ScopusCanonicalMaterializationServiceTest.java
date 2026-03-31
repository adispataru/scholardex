package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeReconciliationService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    private ScopusProjectionBuilderService projectionBuilderService;

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
    }

    private ScopusCanonicalMaterializationService service() {
        ImportProcessingResult empty = new ImportProcessingResult(0);
        when(factBuilderService.buildFactsFromImportEvents(any())).thenReturn(empty);
        when(userDefinedFactBuilderService.buildFactsFromImportEvents(any())).thenReturn(empty);
        when(affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(any())).thenReturn(empty);
        when(authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(any())).thenReturn(empty);
        when(publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(any())).thenReturn(empty);
        when(userDefinedCanonicalizationService.rebuildCanonicalFacts()).thenReturn(empty);
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
}
