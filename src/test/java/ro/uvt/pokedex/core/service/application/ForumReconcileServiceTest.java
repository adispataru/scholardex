package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForumReconcileServiceTest {

    @Mock private ScholardexForumBuilder forumBuilder;
    @Mock private AuthorReconcileService authorReconcileService;
    @Mock private ScholardexProjectionBuilderService projectionBuilderService;

    private ImportProcessingResult res(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult r = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) r.markProcessed();
        for (int i = 0; i < imported; i++) r.markImported();
        for (int i = 0; i < updated; i++) r.markUpdated();
        for (int i = 0; i < skipped; i++) r.markSkipped("s");
        for (int i = 0; i < errors; i++) r.markError("e");
        return r;
    }

    private ScholardexForumBuilder.ScopusForumBuildResult forumBuild() {
        return new ScholardexForumBuilder.ScopusForumBuildResult(
                res(0, 0, 0, 0, 0), res(0, 0, 0, 0, 0), res(0, 0, 0, 0, 0),
                res(0, 0, 0, 0, 0), res(0, 0, 0, 0, 0), res(0, 0, 2, 0, 0), res(0, 0, 3, 0, 0));
    }

    @Test
    void reconcileRunsForumBuildThenProjectionRefreshInOrder() {
        when(forumBuilder.buildScopusForums(any(), any())).thenReturn(forumBuild());
        when(authorReconcileService.reconcileByOrcid(any(), any())).thenReturn(res(0, 0, 0, 0, 0));
        when(authorReconcileService.reconcileByName(any(), any())).thenReturn(res(0, 0, 0, 0, 0));
        when(authorReconcileService.reconcileByNameAndAffiliation(any(), any())).thenReturn(res(0, 0, 0, 0, 0));
        when(projectionBuilderService.rebuildViews()).thenReturn(res(100, 0, 0, 0, 0));

        ForumReconcileService.ForumReconcileResult out =
                new ForumReconcileService(forumBuilder, authorReconcileService, projectionBuilderService).reconcile("admin-manual");

        // Tier-1 reconcile: global forum build, then author reconcile, then the projection refresh so the merges show.
        InOrder order = inOrder(forumBuilder, authorReconcileService, projectionBuilderService);
        order.verify(forumBuilder).buildScopusForums(any(), any());
        order.verify(authorReconcileService).reconcileByOrcid(any(), any());
        order.verify(projectionBuilderService).rebuildViews();
        assertNotNull(out.forumBuild());
        assertEquals(100, out.projection().getProcessedCount());
        assertEquals(2, out.forumBuild().membershipDedup().getUpdatedCount());
    }
}
