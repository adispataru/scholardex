package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import com.mongodb.client.result.DeleteResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineRebuildServiceTest {

    @Mock
    private ScopusBigBangMigrationService scopusRebuild;
    @Mock
    private WosBigBangMigrationService wosRebuild;
    @Mock
    private OwnedCollectionRegistry ownedCollectionRegistry;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ro.uvt.pokedex.core.service.importing.DoajDataService doajDataService;
    @Mock
    private ro.uvt.pokedex.core.service.importing.ErihDataService erihDataService;
    @Mock
    private ForumReconcileService forumReconcileService;
    @Mock
    private ro.uvt.pokedex.core.service.openalex.OpenAlexBulkImportService openAlexBulkImportService;

    /** A non-null reconcile result so the post-rebuild reconcile step can read its projection counts. */
    private static ForumReconcileService.ForumReconcileResult noopReconcile() {
        return new ForumReconcileService.ForumReconcileResult(
                null, new ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult(0));
    }

    @Test
    void rebuildAssertsOwnershipThenResetsWipesAndRebuilds() {
        when(mongoTemplate.remove(any(Query.class), anyString())).thenReturn(DeleteResult.acknowledged(0));
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry, mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);

        PipelineRebuildService.PipelineRebuildResult result = service.rebuildAllDerivedFromSource();

        InOrder order = inOrder(ownedCollectionRegistry, scopusRebuild, wosRebuild, mongoTemplate);
        order.verify(ownedCollectionRegistry).assertAllWipeable(any());
        order.verify(scopusRebuild).resetCanonicalState();
        order.verify(wosRebuild).resetCanonicalState();
        order.verify(mongoTemplate, org.mockito.Mockito.atLeastOnce()).remove(any(Query.class), anyString());  // full-wipe safety net
        order.verify(wosRebuild).run(false, null);
        order.verify(scopusRebuild).runFull();
        assertThat(result).isNotNull();
    }

    @Test
    void rebuildIngestsConfiguredReferenceFeedsBetweenWosAndScopusBuilds() throws Exception {
        when(mongoTemplate.remove(any(Query.class), anyString())).thenReturn(DeleteResult.acknowledged(0));
        java.nio.file.Path doaj = java.nio.file.Files.createTempFile("doaj", ".csv");
        java.nio.file.Path erih = java.nio.file.Files.createTempFile("erih", ".jsonl");
        try {
            PipelineRebuildService service =
                    new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry, mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "doajFile", doaj.toString());
            org.springframework.test.util.ReflectionTestUtils.setField(service, "erihFile", erih.toString());
            org.springframework.test.util.ReflectionTestUtils.setField(service, "referenceAsOf", "2026");
            when(doajDataService.importDoajCsvFromPath(any(), any(), any()))
                    .thenReturn(new ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult(0));
            when(erihDataService.importErihJsonlFromPath(any(), any(), any()))
                    .thenReturn(new ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult(0));

            service.rebuildAllDerivedFromSource();

            // reference snapshots refresh after the WoS build and before the Scopus forum build reads them
            InOrder order = inOrder(wosRebuild, doajDataService, erihDataService, scopusRebuild);
            order.verify(wosRebuild).run(false, null);
            order.verify(doajDataService).importDoajCsvFromPath(any(), any(), any());
            order.verify(erihDataService).importErihJsonlFromPath(any(), any(), any());
            order.verify(scopusRebuild).runFull();
        } finally {
            java.nio.file.Files.deleteIfExists(doaj);
            java.nio.file.Files.deleteIfExists(erih);
        }
    }

    @Test
    void rebuildSkipsReferenceFeedsWhenPathsUnset() {
        when(mongoTemplate.remove(any(Query.class), anyString())).thenReturn(DeleteResult.acknowledged(0));
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry, mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);

        service.rebuildAllDerivedFromSource();

        verifyNoInteractions(doajDataService, erihDataService);
    }

    @Test
    void rebuildAbortsBeforeWipingWhenGuardRejects() {
        doThrow(new IllegalArgumentException("foreign collection"))
                .when(ownedCollectionRegistry).assertAllWipeable(any());
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, ownedCollectionRegistry, mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);

        assertThatThrownBy(service::rebuildAllDerivedFromSource)
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wosRebuild, scopusRebuild);
    }

    @Test
    void startupValidationPassesWhenAllManagedCollectionsAreOwned() {
        OwnedCollectionRegistry allOwned =
                new OwnedCollectionRegistry(PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS);
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, allOwned, mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);

        assertThatCode(service::validateManagedCollectionsAreOwned).doesNotThrowAnyException();
    }

    @Test
    void startupValidationFailsIfAManagedCollectionIsNotOwned() {
        Set<String> missingOne = new HashSet<>(PipelineRebuildService.MANAGED_DERIVED_COLLECTIONS);
        missingOne.remove("scholardex.source_links");
        PipelineRebuildService service =
                new PipelineRebuildService(scopusRebuild, wosRebuild, new OwnedCollectionRegistry(missingOne), mongoTemplate, doajDataService, erihDataService, forumReconcileService, openAlexBulkImportService);

        assertThatThrownBy(service::validateManagedCollectionsAreOwned)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scholardex.source_links");
    }
}
