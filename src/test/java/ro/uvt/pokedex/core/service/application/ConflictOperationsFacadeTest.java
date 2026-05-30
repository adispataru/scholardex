package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.importing.ImportRunMetricRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictOperationsFacadeTest {

    @Mock
    private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock
    private WosIdentityConflictRepository wosIdentityConflictRepository;
    @Mock
    private WosFactConflictRepository wosFactConflictRepository;
    @Mock
    private PublicationLinkConflictRepository publicationLinkConflictRepository;
    @Mock
    private ImportRunMetricRepository importRunMetricRepository;
    @Mock
    private ImportRunMetricService importRunMetricService;
    @Mock
    private ScholardexProjectionDirtyService projectionDirtyService;

    private ConflictOperationsFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ConflictOperationsFacade(
                scholardexIdentityConflictRepository,
                wosIdentityConflictRepository,
                wosFactConflictRepository,
                publicationLinkConflictRepository,
                importRunMetricRepository,
                importRunMetricService,
                projectionDirtyService
        );
    }

    @Test
    void findIdentityConflictsUsesNormalizedFiltersAndSort() {
        when(scholardexIdentityConflictRepository
                .findAllByEntityTypeAndIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new ScholardexIdentityConflict())));

        facade.findIdentityConflicts(-1, 999, " publication ", " SCOPUS ", " SOURCE_ID_COLLISION ", " OPEN ", null, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(scholardexIdentityConflictRepository)
                .findAllByEntityTypeAndIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        eq(ScholardexEntityType.PUBLICATION), eq("SCOPUS"), eq("SOURCE_ID_COLLISION"), eq("OPEN"), any(), any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(200, pageable.getPageSize());
        assertTrue(pageable.getSort().getOrderFor("detectedAt").isDescending());
    }

    @Test
    void findNeedsReviewIdentityConflictsUsesAmbiguousCandidateQuery() {
        when(scholardexIdentityConflictRepository
                .findNeedsReviewByEntityTypeAndFilters(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(ambiguousConflict())));

        var page = facade.findNeedsReviewIdentityConflicts(
                -1,
                999,
                " publication ",
                " SCOPUS ",
                " AMBIGUOUS_MATCH ",
                " OPEN ",
                null,
                null
        );

        assertEquals(1, page.getTotalElements());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(scholardexIdentityConflictRepository).findNeedsReviewByEntityTypeAndFilters(
                eq(ScholardexEntityType.PUBLICATION),
                eq("SCOPUS"),
                eq("AMBIGUOUS_MATCH"),
                eq("OPEN"),
                any(),
                any(),
                pageableCaptor.capture()
        );
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(200, pageableCaptor.getValue().getPageSize());
    }

    @Test
    void findNeedsReviewIdentityConflictsWithoutEntityTypeUsesGenericAmbiguousCandidateQuery() {
        when(scholardexIdentityConflictRepository
                .findNeedsReviewByFilters(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var page = facade.findNeedsReviewIdentityConflicts(
                1,
                25,
                "invalid",
                " src ",
                " reason ",
                " open ",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-31T23:59:59Z")
        );

        assertTrue(page.isEmpty());
        verify(scholardexIdentityConflictRepository).findNeedsReviewByFilters(
                eq("src"),
                eq("reason"),
                eq("open"),
                eq(Instant.parse("2026-05-01T00:00:00Z")),
                eq(Instant.parse("2026-05-31T23:59:59Z")),
                any(Pageable.class)
        );
    }

    @Test
    void updateConflictStatusOnlyMutatesOpenConflicts() {
        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setId("c1");
        conflict.setStatus("OPEN");

        when(scholardexIdentityConflictRepository.findByIdAndStatus("c1", "OPEN"))
                .thenReturn(Optional.of(conflict));

        long updated = facade.updateConflictStatus("c1", "RESOLVED", "admin@uvt.ro");

        assertEquals(1L, updated);
        assertEquals("RESOLVED", conflict.getStatus());
        assertEquals("admin@uvt.ro", conflict.getResolvedBy());
        assertNotNull(conflict.getResolvedAt());
        verify(scholardexIdentityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void clearOperationsDeleteAllAndReturnCounts() {
        when(scholardexIdentityConflictRepository.findAll()).thenReturn(List.of(
                conflict("1", "OPEN"), conflict("2", "RESOLVED"), conflict("3", "OPEN")
        ));
        when(wosIdentityConflictRepository.count()).thenReturn(3L);
        when(wosFactConflictRepository.count()).thenReturn(4L);
        when(publicationLinkConflictRepository.count()).thenReturn(5L);

        assertEquals(2L, facade.clearOpenIdentityConflicts());
        assertEquals(3L, facade.clearWosIdentityConflicts());
        assertEquals(4L, facade.clearWosFactConflicts());
        assertEquals(5L, facade.clearScopusLinkConflicts());

        verify(scholardexIdentityConflictRepository).deleteAll(any(List.class));
        verify(wosIdentityConflictRepository).deleteAll();
        verify(wosFactConflictRepository).deleteAll();
        verify(publicationLinkConflictRepository).deleteAll();
    }

    @Test
    void summarizeUsesStatusCounts() {
        when(scholardexIdentityConflictRepository.countByStatus("OPEN")).thenReturn(7L);
        when(scholardexIdentityConflictRepository.countByStatus("RESOLVED")).thenReturn(2L);
        when(scholardexIdentityConflictRepository.countByStatus("DISMISSED")).thenReturn(1L);

        ConflictOperationsFacade.ConflictSummary summary = facade.summarizeIdentityConflicts();

        assertEquals(7L, summary.open());
        assertEquals(2L, summary.resolved());
        assertEquals(1L, summary.dismissed());
        assertEquals(10L, summary.total());
    }

    @Test
    void summarizeNeedsReviewUsesOnlyAmbiguousCandidateCounts() {
        when(scholardexIdentityConflictRepository.countNeedsReviewByStatus("OPEN")).thenReturn(4L);
        when(scholardexIdentityConflictRepository.countNeedsReviewByStatus("RESOLVED")).thenReturn(3L);
        when(scholardexIdentityConflictRepository.countNeedsReviewByStatus("DISMISSED")).thenReturn(2L);
        when(scholardexIdentityConflictRepository.countNeedsReviewByStatus("INVESTIGATED")).thenReturn(1L);

        ConflictOperationsFacade.ConflictSummary summary = facade.summarizeNeedsReviewIdentityConflicts();

        assertEquals(4L, summary.open());
        assertEquals(3L, summary.resolved());
        assertEquals(2L, summary.dismissed());
        assertEquals(1L, summary.investigated());
        assertEquals(10L, summary.total());
    }

    @Test
    void summarizeAuditOnlyConflictsUsesAggregateAndDeterministicStores() {
        when(importRunMetricRepository.count()).thenReturn(11L);
        when(scholardexIdentityConflictRepository.countAuditOnlyDeterministic()).thenReturn(12L);
        when(wosFactConflictRepository.count()).thenReturn(22L);
        when(publicationLinkConflictRepository.count()).thenReturn(33L);

        ConflictOperationsFacade.AuditOnlySummary summary = facade.summarizeAuditOnlyConflicts();

        assertEquals(11L, summary.importRunMetricAggregates());
        assertEquals(12L, summary.scholardexDeterministicIdentityConflictRows());
        assertEquals(22L, summary.wosFactConflictRows());
        assertEquals(33L, summary.scopusPublicationLinkConflictRows());
        assertEquals(78L, summary.total());
    }

    @Test
    void projectionDirtyOperationsDelegateToProjectionDirtyService() {
        when(projectionDirtyService.summarizeDirtyProjections())
                .thenReturn(new ScholardexProjectionDirtyService.ProjectionDirtySummary(2, 1));
        when(projectionDirtyService.rebuildDirtyProjections())
                .thenReturn(new ScholardexProjectionDirtyService.ProjectionRebuildResult(3, 2, 1, 2, 1, 0, List.of("batch-2 failed")));

        ScholardexProjectionDirtyService.ProjectionDirtySummary summary = facade.summarizeDirtyProjections();
        ScholardexProjectionDirtyService.ProjectionRebuildResult rebuild = facade.rebuildDirtyProjections();

        assertEquals(2L, summary.dirty());
        assertEquals(1L, summary.rebuildFailed());
        assertEquals(3L, summary.totalOutstanding());
        assertEquals(1L, rebuild.failedMarkers());
        assertFalse(rebuild.errors().isEmpty());
    }

    @Test
    void cleanupDeterministicLegacyConflictsDeletesOnlyNonAmbiguousRowsAndRecordsMetrics() {
        ScholardexIdentityConflict deterministicRelink = conflict("det-1", "OPEN");
        deterministicRelink.setEntityType(ScholardexEntityType.PUBLICATION);
        deterministicRelink.setIncomingSource("SCOPUS_JSON_BOOTSTRAP");
        deterministicRelink.setReasonCode("SOURCE_LINK_RELINK_REJECTED");
        deterministicRelink.setCandidateCanonicalIds(List.of("spub_1"));
        ScholardexIdentityConflict zeroCandidate = conflict("det-2", "OPEN");
        zeroCandidate.setEntityType(ScholardexEntityType.AUTHOR);
        zeroCandidate.setIncomingSource("SCOPUS");
        zeroCandidate.setReasonCode("NO_CANONICAL_CANDIDATE");
        zeroCandidate.setCandidateCanonicalIds(List.of());
        ScholardexIdentityConflict ambiguous = ambiguousConflict();
        when(scholardexIdentityConflictRepository.findAll())
                .thenReturn(List.of(deterministicRelink, zeroCandidate, ambiguous));
        when(wosFactConflictRepository.count()).thenReturn(22L);
        when(wosIdentityConflictRepository.count()).thenReturn(1L);
        when(publicationLinkConflictRepository.count()).thenReturn(3L);

        ConflictOperationsFacade.LegacyConflictCleanupSummary summary = facade.cleanupDeterministicLegacyConflicts();

        assertEquals(2L, summary.deletedDeterministicIdentityConflicts());
        assertEquals(1L, summary.retainedNeedsReviewIdentityConflicts());
        assertEquals(2L, summary.metricAggregatesRecorded());
        assertEquals(22L, summary.wosFactConflictRowsPreserved());
        assertEquals(1L, summary.wosIdentityConflictRowsPreserved());
        assertEquals(3L, summary.scopusPublicationLinkConflictRowsPreserved());
        verify(scholardexIdentityConflictRepository).deleteAll(argThat(deleted ->
                deleted instanceof List<?> rows
                        && rows.size() == 2
                        && rows.contains(deterministicRelink)
                        && rows.contains(zeroCandidate)
                        && !rows.contains(ambiguous)
        ));
        verify(importRunMetricService).record(
                "legacy-conflict-cleanup",
                "SCOPUS_JSON_BOOTSTRAP",
                "PUBLICATION",
                "legacy-deterministic-identity-conflict:SOURCE_LINK_RELINK_REJECTED",
                1
        );
        verify(importRunMetricService).record(
                "legacy-conflict-cleanup",
                "SCOPUS",
                "AUTHOR",
                "legacy-deterministic-identity-conflict:NO_CANONICAL_CANDIDATE",
                1
        );
    }

    @Test
    void cleanupDeterministicLegacyConflictsIsIdempotentWhenOnlyAmbiguousRowsRemain() {
        when(scholardexIdentityConflictRepository.findAll()).thenReturn(List.of(ambiguousConflict()));

        ConflictOperationsFacade.LegacyConflictCleanupSummary summary = facade.cleanupDeterministicLegacyConflicts();

        assertEquals(0L, summary.deletedDeterministicIdentityConflicts());
        assertEquals(1L, summary.retainedNeedsReviewIdentityConflicts());
        assertEquals(0L, summary.metricAggregatesRecorded());
        verify(scholardexIdentityConflictRepository, never()).deleteAll(any(List.class));
        verify(importRunMetricService, never()).record(any(), any(), any(), any(), anyLong());
    }

    @Test
    void findIdentityConflictsWithoutEntityTypeUsesGenericQuery() {
        when(scholardexIdentityConflictRepository
                .findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var page = facade.findIdentityConflicts(2, 10, "invalid", " src ", " reason ", " resolved ", Instant.EPOCH, Instant.now());
        assertNotNull(page);
        assertTrue(page.isEmpty());

        verify(scholardexIdentityConflictRepository)
                .findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        eq("src"), eq("reason"), eq("resolved"), any(), any(), any());
    }

    @Test
    void findIdentityConflictsSwapsDateBoundsWhenProvidedInReverseOrder() {
        when(scholardexIdentityConflictRepository
                .findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        facade.findIdentityConflicts(0, 20, "", "", "", "", from, to);

        verify(scholardexIdentityConflictRepository)
                .findAllByIncomingSourceContainingIgnoreCaseAndReasonCodeContainingIgnoreCaseAndStatusContainingIgnoreCaseAndDetectedAtBetween(
                        eq(""), eq(""), eq(""),
                        eq(to), eq(from), any(Pageable.class));
    }

    @Test
    void bulkUpdateConflictStatusAggregatesAndRejectsInvalidRequests() {
        ScholardexIdentityConflict c1 = conflict("c1", "OPEN");
        ScholardexIdentityConflict c2 = conflict("c2", "OPEN");
        when(scholardexIdentityConflictRepository.findByIdAndStatus("c1", "OPEN")).thenReturn(Optional.of(c1));
        when(scholardexIdentityConflictRepository.findByIdAndStatus("c2", "OPEN")).thenReturn(Optional.of(c2));

        assertEquals(2L, facade.bulkUpdateConflictStatus(List.of("c1", "c2"), "investigated", "admin"));
        assertEquals(0L, facade.bulkUpdateConflictStatus(List.of("c1"), "open", "admin"));
        assertEquals(0L, facade.bulkUpdateConflictStatus(List.of(), "resolved", "admin"));
        verify(scholardexIdentityConflictRepository).save(c1);
        verify(scholardexIdentityConflictRepository).save(c2);
        assertEquals("INVESTIGATED", c1.getStatus());
    }

    @Test
    void clearOpenIdentityConflictsReturnsZeroWhenNoneOpen() {
        when(scholardexIdentityConflictRepository.findAll()).thenReturn(List.of(conflict("1", "resolved")));
        assertEquals(0L, facade.clearOpenIdentityConflicts());
    }

    private ScholardexIdentityConflict conflict(String id, String status) {
        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setId(id);
        conflict.setStatus(status);
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }

    private ScholardexIdentityConflict ambiguousConflict() {
        ScholardexIdentityConflict conflict = conflict("ambiguous", "OPEN");
        conflict.setCandidateCanonicalIds(List.of("candidate-1", "candidate-2"));
        return conflict;
    }
}
