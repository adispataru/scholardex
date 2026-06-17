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

    private ImportProcessingResult result(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult r = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) r.markProcessed();
        for (int i = 0; i < imported; i++) r.markImported();
        for (int i = 0; i < updated; i++) r.markUpdated();
        for (int i = 0; i < skipped; i++) r.markSkipped("s");
        for (int i = 0; i < errors; i++) r.markError("e");
        return r;
    }

    @Test
    void buildsForumsFirstInOrderAndRunsErihDedupWhenErihWroteIds() {
        when(deduplicationService.deduplicateForums(eq("batch"), any())).thenReturn(result(0, 0, 1, 0, 0));
        when(wosScholardexOnboardingService.runScopusForumCanonicalization("batch", "run")).thenReturn(result(5, 5, 0, 0, 0));
        when(erihOnboardingService.onboardErih()).thenReturn(result(10, 0, 3, 0, 0)); // wrote erihIds -> dedup again

        ScholardexForumBuilder builder = new ScholardexForumBuilder(
                deduplicationService, wosScholardexOnboardingService, erihOnboardingService);
        ScholardexForumBuilder.ScopusForumBuildResult out = builder.buildScopusForums("batch", "run");

        // forums-first order: dedup -> canonicalization -> erih onboard -> erih dedup
        InOrder order = inOrder(deduplicationService, wosScholardexOnboardingService, erihOnboardingService);
        order.verify(deduplicationService).deduplicateForums("batch", "run");
        order.verify(wosScholardexOnboardingService).runScopusForumCanonicalization("batch", "run");
        order.verify(erihOnboardingService).onboardErih();
        order.verify(deduplicationService).deduplicateForums("batch", "run-erih");

        assertEquals(3, out.erihOnboarding().getUpdatedCount());
        assertEquals(5, out.canonicalization().getProcessedCount());
    }

    @Test
    void skipsErihDedupWhenOnboardingWroteNoIds() {
        when(deduplicationService.deduplicateForums("batch", "run")).thenReturn(result(0, 0, 0, 0, 0));
        when(wosScholardexOnboardingService.runScopusForumCanonicalization("batch", "run")).thenReturn(result(0, 0, 0, 0, 0));
        when(erihOnboardingService.onboardErih()).thenReturn(result(0, 0, 0, 0, 0)); // no erihIds written

        ScholardexForumBuilder builder = new ScholardexForumBuilder(
                deduplicationService, wosScholardexOnboardingService, erihOnboardingService);
        builder.buildScopusForums("batch", "run");

        // only the first dedup runs; no erih-dedup pass
        verify(deduplicationService).deduplicateForums("batch", "run");
        verify(deduplicationService, never()).deduplicateForums("batch", "run-erih");
    }
}
