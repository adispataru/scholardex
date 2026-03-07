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
import ro.uvt.pokedex.core.repository.reporting.WosFactConflictRepository;
import ro.uvt.pokedex.core.repository.reporting.WosIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationLinkConflictRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictOperationsFacadeTest {

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
                wosIdentityConflictRepository,
                wosFactConflictRepository,
                publicationLinkConflictRepository
        );
    }

    @Test
    void findWosIdentityConflictsUsesNormalizedFiltersAndSort() {
        when(wosIdentityConflictRepository
                .findAllBySourceVersionContainingIgnoreCaseAndSourceFileContainingIgnoreCaseAndConflictTypeContainingIgnoreCase(
                        any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new WosIdentityConflict())));

        facade.findWosIdentityConflicts(-1, 999, " v2020 ", null, " AMBIGUOUS ");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(wosIdentityConflictRepository)
                .findAllBySourceVersionContainingIgnoreCaseAndSourceFileContainingIgnoreCaseAndConflictTypeContainingIgnoreCase(
                        eq("v2020"), eq(""), eq("AMBIGUOUS"), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(200, pageable.getPageSize());
        assertTrue(pageable.getSort().getOrderFor("conflictDetectedAt").isDescending());
    }

    @Test
    void findWosFactConflictsWithoutSourceVersionUsesSimpleMethod() {
        when(wosFactConflictRepository.findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new WosFactConflict())));

        facade.findWosFactConflicts(1, 10, "   ", "METRIC", "reason");

        verify(wosFactConflictRepository)
                .findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(eq("METRIC"), eq("reason"), any());
    }

    @Test
    void findWosFactConflictsWithSourceVersionUsesWinnerLoserMethod() {
        when(wosFactConflictRepository
                .findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndWinnerSourceVersionContainingIgnoreCaseOrFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndLoserSourceVersionContainingIgnoreCase(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new WosFactConflict())));

        facade.findWosFactConflicts(0, 20, "v2020", "CATEGORY", "latest-lineage");

        verify(wosFactConflictRepository)
                .findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndWinnerSourceVersionContainingIgnoreCaseOrFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndLoserSourceVersionContainingIgnoreCase(
                        eq("CATEGORY"), eq("latest-lineage"), eq("v2020"),
                        eq("CATEGORY"), eq("latest-lineage"), eq("v2020"),
                        any());
    }

    @Test
    void findScopusLinkConflictsUsesNormalizedFiltersAndSort() {
        when(publicationLinkConflictRepository
                .findAllByEnrichmentSourceContainingIgnoreCaseAndKeyTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
                        any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new PublicationLinkConflict())));

        facade.findScopusLinkConflicts(null, null, " WOSEXTRACTOR ", " wosId ", " key-conflict ");

        verify(publicationLinkConflictRepository)
                .findAllByEnrichmentSourceContainingIgnoreCaseAndKeyTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
                        eq("WOSEXTRACTOR"), eq("wosId"), eq("key-conflict"), any());
    }

    @Test
    void clearOperationsDeleteAllAndReturnCounts() {
        when(wosIdentityConflictRepository.count()).thenReturn(3L);
        when(wosFactConflictRepository.count()).thenReturn(4L);
        when(publicationLinkConflictRepository.count()).thenReturn(5L);

        assertEquals(3L, facade.clearWosIdentityConflicts());
        assertEquals(4L, facade.clearWosFactConflicts());
        assertEquals(5L, facade.clearScopusLinkConflicts());

        verify(wosIdentityConflictRepository).deleteAll();
        verify(wosFactConflictRepository).deleteAll();
        verify(publicationLinkConflictRepository).deleteAll();
    }
}
