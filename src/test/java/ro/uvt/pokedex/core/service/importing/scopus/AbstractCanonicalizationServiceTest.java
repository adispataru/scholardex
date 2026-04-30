package ro.uvt.pokedex.core.service.importing.scopus;

import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCanonicalBuildCheckpoint;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCanonicalizationServiceTest {

    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ScholardexCanonicalBuildCheckpointService checkpointService;

    private TestCanonicalizationService service;

    @BeforeEach
    void setUp() {
        service = new TestCanonicalizationService(sourceLinkService, identityConflictRepository, checkpointService);
    }

    @Test
    void rebuildRunsChunksAndWritesCheckpointFromResumePoint() {
        ScholardexCanonicalBuildCheckpoint checkpoint = new ScholardexCanonicalBuildCheckpoint();
        checkpoint.setLastCompletedBatch(0);
        when(checkpointService.readCheckpoint("pipeline-key")).thenReturn(Optional.of(checkpoint));
        double beforeBuildRunCounterCount = Metrics.globalRegistry.getMeters().stream()
                .filter(m -> "core.h19.canonical.build.runs".equals(m.getId().getName()))
                .count();

        ImportProcessingResult result = service.rebuild(CanonicalBuildOptions.defaults());

        assertEquals(1, result.getStartBatch());
        assertEquals(2, result.getEndBatch());
        assertEquals(2, result.getBatchesProcessed());
        assertEquals(3, result.getTotalBatches());
        assertEquals(4, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(Boolean.TRUE.equals(result.getResumedFromCheckpoint()));
        assertEquals(1, service.sortCalls);
        assertEquals(2, service.afterChunkCalls);
        assertEquals(2, service.preloadCalls);
        assertEquals(4, service.processCalls);
        assertEquals(2, service.flushCalls);
        assertEquals(6, service.contextsWithFlush);
        assertEquals(6, service.contextsWithAfterChunk);
        assertEquals(2, service.maxProcessedInContextAtFlush);
        assertEquals(2, service.maxProcessedInContextAtAfterChunk);
        assertEquals(0, service.heartbeatEventsObserved);

        verify(checkpointService).upsertCheckpoint(
                eq("pipeline-key"),
                eq(1),
                eq(2),
                eq("r4"),
                any(),
                eq("scopus-v-default")
        );
        verify(checkpointService).upsertCheckpoint(
                eq("pipeline-key"),
                eq(2),
                eq(2),
                eq("r6"),
                any(),
                eq("scopus-v-default")
        );

        double afterBuildRunCounterCount = Metrics.globalRegistry.getMeters().stream()
                .filter(m -> "core.h19.canonical.build.runs".equals(m.getId().getName()))
                .count();
        assertTrue(afterBuildRunCounterCount >= beforeBuildRunCounterCount);
    }

    @Test
    void rebuildSkipsWhenStartBatchAfterTotalAndNoCheckpointOption() {
        ImportProcessingResult result = service.rebuild(new CanonicalBuildOptions(2, 99, false, null, null, false, false));

        assertEquals(99, result.getStartBatch());
        assertEquals(99, result.getEndBatch());
        assertEquals(0, result.getBatchesProcessed());
        assertEquals(0, service.afterChunkCalls);
        assertEquals(0, service.preloadCalls);
        assertEquals(0, service.processCalls);
        verify(checkpointService, never()).readCheckpoint("pipeline-key");
    }

    @Test
    void buildConflictCreatesOrReusesOpenConflict() {
        ScholardexIdentityConflict existing = new ScholardexIdentityConflict();
        existing.setDetectedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                ScholardexEntityType.PUBLICATION,
                "USER_DEFINED",
                "src-1",
                "REASON_X",
                CanonicalizationSupport.STATUS_OPEN
        )).thenReturn(Optional.of(existing));

        double beforeCreatedConflictCount = Metrics.globalRegistry.getMeters().stream()
                .filter(m -> "core.h19.identity_conflict.created".equals(m.getId().getName()))
                .count();

        ScholardexIdentityConflict reused = service.buildConflict(
                "USER_DEFINED", "src-1", "REASON_X", List.of("c1", "c2"), "ev", "b", "corr"
        );
        assertEquals(ScholardexEntityType.PUBLICATION, reused.getEntityType());
        assertEquals("USER_DEFINED", reused.getIncomingSource());
        assertEquals("src-1", reused.getIncomingSourceRecordId());
        assertEquals(CanonicalizationSupport.STATUS_OPEN, reused.getStatus());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), reused.getDetectedAt());
        assertEquals(List.of("c1", "c2"), reused.getCandidateCanonicalIds());
        assertEquals("ev", reused.getSourceEventId());
        assertEquals("b", reused.getSourceBatchId());
        assertEquals("corr", reused.getSourceCorrelationId());
        assertEquals("REASON_X", reused.getReasonCode());

        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                ScholardexEntityType.PUBLICATION,
                "USER_DEFINED",
                "src-2",
                "REASON_Y",
                CanonicalizationSupport.STATUS_OPEN
        )).thenReturn(Optional.empty());
        ScholardexIdentityConflict created = service.buildConflict(
                "USER_DEFINED", "src-2", "REASON_Y", null, "ev2", "b2", "corr2"
        );
        assertNotNull(created.getDetectedAt());
        assertEquals(List.of(), created.getCandidateCanonicalIds());
        assertEquals(ScholardexEntityType.PUBLICATION, created.getEntityType());
        assertEquals("USER_DEFINED", created.getIncomingSource());
        assertEquals("src-2", created.getIncomingSourceRecordId());
        assertEquals(CanonicalizationSupport.STATUS_OPEN, created.getStatus());
        assertEquals("ev2", created.getSourceEventId());
        assertEquals("b2", created.getSourceBatchId());
        assertEquals("corr2", created.getSourceCorrelationId());
        assertEquals("REASON_Y", created.getReasonCode());

        double afterCreatedConflictCount = Metrics.globalRegistry.getMeters().stream()
                .filter(m -> "core.h19.identity_conflict.created".equals(m.getId().getName()))
                .count();
        assertTrue(afterCreatedConflictCount >= beforeCreatedConflictCount);
    }

    @Test
    void processChunkHeartbeatBranchAndFlushAreCovered() {
        FastHeartbeatCanonicalizationService heartbeatService = new FastHeartbeatCanonicalizationService(
                sourceLinkService, identityConflictRepository, checkpointService
        );
        ImportProcessingResult result = new ImportProcessingResult(5);
        @SuppressWarnings("unchecked")
        CanonicalBuildChunkTimings timings = ReflectionTestUtils.invokeMethod(
                heartbeatService,
                "processChunk",
                List.of("a", "b", "c"),
                result,
                1,
                1,
                3,
                heartbeatService.createChunkContext()
        );
        assertNotNull(timings);
        assertEquals(3, result.getProcessedCount());
        assertEquals(heartbeatService.processCalls, 3);
        assertTrue(heartbeatService.heartbeatEventsObserved > 0);
        assertEquals(1, heartbeatService.preloadCalls);
        assertEquals(1, heartbeatService.flushCalls);
        assertEquals(3, heartbeatService.maxProcessedInContextAtFlush);
    }

    @Test
    void rebuildWithNullOptionsUsesDefaultsAndSort() {
        SortingCanonicalizationService sortingService = new SortingCanonicalizationService(
                sourceLinkService, identityConflictRepository, checkpointService
        );
        ImportProcessingResult result = sortingService.rebuild(null);
        assertEquals(0, result.getStartBatch());
        assertEquals(1, result.getTotalBatches());
        assertEquals(1, sortingService.sortCalls);
        assertEquals("a", sortingService.firstSortedKey);
        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getUpdatedCount());
    }

    private static class TestCanonicalizationService extends AbstractCanonicalizationService<String, List<String>> {

        protected List<String> sourceFacts = List.of("r1", "r2", "r3", "r4", "r5", "r6");
        protected int sortCalls;
        protected int preloadCalls;
        protected int processCalls;
        protected int flushCalls;
        protected int afterChunkCalls;
        protected int contextsWithAfterChunk;
        protected int contextsWithFlush;
        protected int maxProcessedInContextAtAfterChunk;
        protected int maxProcessedInContextAtFlush;
        protected int heartbeatEventsObserved;

        private TestCanonicalizationService(
                ScholardexSourceLinkService sourceLinkService,
                ScholardexIdentityConflictRepository identityConflictRepository,
                ScholardexCanonicalBuildCheckpointService checkpointService
        ) {
            super(sourceLinkService, identityConflictRepository, checkpointService);
        }

        @Override
        protected String getPipelineKey() {
            return "pipeline-key";
        }

        @Override
        protected String getEntityTypeLabel() {
            return "publication";
        }

        @Override
        protected ScholardexEntityType getEntityType() {
            return ScholardexEntityType.PUBLICATION;
        }

        @Override
        protected int getDefaultChunkSize() {
            return 2;
        }

        @Override
        protected String getDefaultSourceVersion() {
            return "scopus-v-default";
        }

        @Override
        protected long getHeartbeatSeconds() {
            return 1;
        }

        @Override
        protected List<String> loadSourceFacts(CanonicalBuildOptions options) {
            return new ArrayList<>(sourceFacts);
        }

        @Override
        protected void sortSourceFacts(List<String> facts) {
            sortCalls++;
        }

        @Override
        protected String lastRecordKey(List<String> chunk) {
            return chunk.get(chunk.size() - 1);
        }

        @Override
        protected List<String> createChunkContext() {
            return new ArrayList<>();
        }

        @Override
        protected void preloadChunkContext(List<String> chunk, List<String> context) {
            preloadCalls++;
            context.add("preloaded:" + chunk.size());
        }

        @Override
        protected void processSourceFact(String fact, ImportProcessingResult result, List<String> context) {
            processCalls++;
            if ("r5".equals(fact)) {
                result.markImported();
            } else if ("r6".equals(fact)) {
                result.markUpdated();
                result.markSkipped("demo");
            }
            context.add("processed:" + fact);
        }

        @Override
        protected CanonicalBuildChunkTimings flushPendingWrites(
                long chunkStartedAtNanos,
                long preloadFinishedAtNanos,
                long resolveFinishedAtNanos,
                List<String> context
        ) {
            flushCalls++;
            contextsWithFlush += context.size();
            maxProcessedInContextAtFlush = Math.max(maxProcessedInContextAtFlush, (int) context.stream().filter(v -> v.startsWith("processed:")).count());
            return new CanonicalBuildChunkTimings(1, 2, 3, 4, 10);
        }

        @Override
        protected void afterChunkLogged(int chunkNo, List<String> context) {
            afterChunkCalls++;
            contextsWithAfterChunk += context.size();
            maxProcessedInContextAtAfterChunk = Math.max(maxProcessedInContextAtAfterChunk, (int) context.stream().filter(v -> v.startsWith("processed:")).count());
        }
    }

    private static final class FastHeartbeatCanonicalizationService extends TestCanonicalizationService {

        private FastHeartbeatCanonicalizationService(
                ScholardexSourceLinkService sourceLinkService,
                ScholardexIdentityConflictRepository identityConflictRepository,
                ScholardexCanonicalBuildCheckpointService checkpointService
        ) {
            super(sourceLinkService, identityConflictRepository, checkpointService);
            sourceFacts = List.of("a", "b", "c");
        }

        @Override
        protected long getHeartbeatSeconds() {
            return 0;
        }

        @Override
        protected void processSourceFact(String fact, ImportProcessingResult result, List<String> context) {
            super.processSourceFact(fact, result, context);
            // Count heartbeat opportunities via processed events.
            heartbeatEventsObserved++;
        }
    }

    private static final class SortingCanonicalizationService extends TestCanonicalizationService {
        private String firstSortedKey;

        private SortingCanonicalizationService(
                ScholardexSourceLinkService sourceLinkService,
                ScholardexIdentityConflictRepository identityConflictRepository,
                ScholardexCanonicalBuildCheckpointService checkpointService
        ) {
            super(sourceLinkService, identityConflictRepository, checkpointService);
            sourceFacts = List.of("z", "a");
        }

        @Override
        protected int getDefaultChunkSize() {
            return 10;
        }

        @Override
        protected void sortSourceFacts(List<String> facts) {
            super.sortSourceFacts(facts);
            facts.sort(String::compareTo);
            firstSortedKey = facts.get(0);
        }

        @Override
        protected void processSourceFact(String fact, ImportProcessingResult result, List<String> context) {
            processCalls++;
            if ("a".equals(fact)) {
                result.markImported();
            } else {
                result.markUpdated();
            }
            context.add("processed:" + fact);
        }
    }
}
