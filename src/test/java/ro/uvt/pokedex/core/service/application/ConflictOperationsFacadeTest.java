package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationLinkConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private ConflictOperationsFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ConflictOperationsFacade(
                scholardexIdentityConflictRepository,
                wosIdentityConflictRepository,
                wosFactConflictRepository,
                publicationLinkConflictRepository
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
    void updateConflictStatusOnlyMutatesOpenConflicts() {
        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setId("c1");
        conflict.setStatus("OPEN");

        when(scholardexIdentityConflictRepository.findByIdAndStatus("c1", "OPEN"))
                .thenReturn(Optional.of(conflict));

        long updated = facade.updateConflictStatus("c1", "RESOLVED", "admin@uvt.ro");

        assertEquals(1L, updated);
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

    private ScholardexIdentityConflict conflict(String id, String status) {
        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setId(id);
        conflict.setStatus(status);
        conflict.setDetectedAt(Instant.now());
        return conflict;
    }
}
