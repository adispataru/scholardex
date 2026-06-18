package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexForumBuilderTest {

    @Mock private ScholardexForumDeduplicationService deduplicationService;
    @Mock private WosScholardexOnboardingService wosScholardexOnboardingService;
    @Mock private ErihOnboardingService erihOnboardingService;
    @Mock private DoajOnboardingService doajOnboardingService;

    private ImportProcessingResult result(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult r = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) r.markProcessed();
        for (int i = 0; i < imported; i++) r.markImported();
        for (int i = 0; i < updated; i++) r.markUpdated();
        for (int i = 0; i < skipped; i++) r.markSkipped("s");
        for (int i = 0; i < errors; i++) r.markError("e");
        return r;
    }

    private ScholardexForumBuilder builder() {
        return new ScholardexForumBuilder(
                deduplicationService, wosScholardexOnboardingService, erihOnboardingService, doajOnboardingService);
    }

    @Test
    void buildsForumsFirstInOrderAndRunsMembershipDedupWhenAnIdentitySourceTagged() {
        when(deduplicationService.deduplicateForums(eq("batch"), any())).thenReturn(result(0, 0, 1, 0, 0));
        when(wosScholardexOnboardingService.runScopusForumCanonicalization("batch", "run")).thenReturn(result(5, 5, 0, 0, 0));
        when(erihOnboardingService.onboardErih()).thenReturn(result(10, 0, 3, 0, 0)); // tagged forums -> dedup again
        when(doajOnboardingService.onboardDoaj()).thenReturn(result(8, 1, 0, 0, 0));
        when(wosScholardexOnboardingService.runWosForumOnboarding("batch", "run")).thenReturn(result(30, 20, 5, 0, 0));
        when(wosScholardexOnboardingService.relinkAmbiguousWosForums("batch", "run-relink")).thenReturn(result(2, 0, 2, 0, 0));

        ScholardexForumBuilder.ScopusForumBuildResult out = builder().buildScopusForums("batch", "run");

        // identity-first order: dedup -> Scopus canon -> ERIH -> DOAJ -> WoS (last) -> membership dedup -> relink
        InOrder order = inOrder(deduplicationService, wosScholardexOnboardingService, erihOnboardingService, doajOnboardingService);
        order.verify(deduplicationService).deduplicateForums("batch", "run");
        order.verify(wosScholardexOnboardingService).runScopusForumCanonicalization("batch", "run");
        order.verify(erihOnboardingService).onboardErih();
        order.verify(doajOnboardingService).onboardDoaj();
        order.verify(wosScholardexOnboardingService).runWosForumOnboarding("batch", "run");
        order.verify(deduplicationService).deduplicateForums("batch", "run-membership");
        order.verify(wosScholardexOnboardingService).relinkAmbiguousWosForums("batch", "run-relink");

        assertEquals(2, out.wosRelink().getUpdatedCount());
        assertEquals(3, out.erihOnboarding().getUpdatedCount());
        assertEquals(1, out.doajOnboarding().getImportedCount());
        assertEquals(20, out.wosOnboarding().getImportedCount());
        assertEquals(5, out.canonicalization().getProcessedCount());
    }

    @Test
    void resolveDelegatesToBatchScopedCanonAndSkipsGlobalReconcile() {
        // H66B Phase 2: Tier-2 resolve binds only the batch's venues (find-or-mint) and defers the global
        // reconcile — it must NOT run dedup / ERIH / DOAJ / WoS onboarding.
        when(wosScholardexOnboardingService.runScopusForumCanonicalizationForBatch("up-batch", "incremental"))
                .thenReturn(result(5, 2, 3, 1, 0)); // processed5, minted(imported)2, matched(updated)3, deferred(skipped)1

        ScholardexForumBuilder.ForumResolveResult out = builder().resolve("up-batch", "incremental");

        assertEquals(5, out.processed());
        assertEquals(2, out.minted());
        assertEquals(3, out.matched());
        assertEquals(1, out.deferredConflicts());
        verify(wosScholardexOnboardingService).runScopusForumCanonicalizationForBatch("up-batch", "incremental");
        verify(deduplicationService, never()).deduplicateForums(any(), any());
        verify(erihOnboardingService, never()).onboardErih();
        verify(doajOnboardingService, never()).onboardDoaj();
        verify(wosScholardexOnboardingService, never()).runWosForumOnboarding(any(), any());
    }

    @Test
    void skipsMembershipDedupWhenNeitherSourceTagged() {
        when(deduplicationService.deduplicateForums("batch", "run")).thenReturn(result(0, 0, 0, 0, 0));
        when(wosScholardexOnboardingService.runScopusForumCanonicalization("batch", "run")).thenReturn(result(0, 0, 0, 0, 0));
        when(erihOnboardingService.onboardErih()).thenReturn(result(0, 0, 0, 0, 0)); // no tags
        when(doajOnboardingService.onboardDoaj()).thenReturn(result(2, 1, 0, 0, 0)); // only a create, no tag
        when(wosScholardexOnboardingService.runWosForumOnboarding("batch", "run")).thenReturn(result(0, 0, 0, 0, 0)); // no change

        builder().buildScopusForums("batch", "run");

        // only the first dedup runs; creates (imported) alone don't trigger the membership dedup
        verify(deduplicationService).deduplicateForums("batch", "run");
        verify(deduplicationService, never()).deduplicateForums("batch", "run-membership");
        // no membership dedup -> no forums removed -> nothing to re-link
        verify(wosScholardexOnboardingService, never()).relinkAmbiguousWosForums(any(), any());
    }
}
