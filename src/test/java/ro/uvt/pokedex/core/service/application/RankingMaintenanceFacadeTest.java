package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankingMaintenanceFacadeTest {

    @Mock
    private WosProjectionBuilderService wosProjectionBuilderService;
    @Mock
    private WosIndexMaintenanceService wosIndexMaintenanceService;

    @InjectMocks
    private RankingMaintenanceFacade facade;

    @Test
    void legacyComputePositionsOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::computePositionsForKnownQuarters);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService);
    }

    @Test
    void legacyComputeQuartersOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::computeQuartersAndRankingsWhereMissing);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService);
    }

    @Test
    void legacyMergeOperationIsDisabled() {
        assertThrows(IllegalStateException.class, facade::mergeDuplicateRankings);
        verifyNoInteractions(wosProjectionBuilderService, wosIndexMaintenanceService);
    }

    @Test
    void rebuildWosProjectionsDelegatesToBuilder() {
        ImportProcessingResult expected = new ImportProcessingResult(5);
        when(wosProjectionBuilderService.rebuildWosProjections()).thenReturn(expected);

        ImportProcessingResult result = facade.rebuildWosProjections();

        verify(wosProjectionBuilderService).rebuildWosProjections();
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }

    @Test
    void ensureWosIndexesDelegatesToIndexMaintenanceService() {
        WosIndexMaintenanceService.WosIndexEnsureResult expected =
                new WosIndexMaintenanceService.WosIndexEnsureResult(List.of("a"), List.of("b"), List.of(), List.of());
        when(wosIndexMaintenanceService.ensureWosIndexes()).thenReturn(expected);

        WosIndexMaintenanceService.WosIndexEnsureResult result = facade.ensureWosIndexes();

        verify(wosIndexMaintenanceService).ensureWosIndexes();
        org.junit.jupiter.api.Assertions.assertSame(expected, result);
    }
}
