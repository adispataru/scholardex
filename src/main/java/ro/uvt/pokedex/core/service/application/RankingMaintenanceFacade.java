package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

@Service
@RequiredArgsConstructor
public class RankingMaintenanceFacade {
    private final WosProjectionBuilderService wosProjectionBuilderService;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;

    public void computePositionsForKnownQuarters() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public void computeQuartersAndRankingsWhereMissing() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public void mergeDuplicateRankings() {
        throw new IllegalStateException("Legacy WoS maintenance operation is disabled. Use canonical WoS ingestion/facts/projections pipeline.");
    }

    public ImportProcessingResult rebuildWosProjections() {
        return wosProjectionBuilderService.rebuildWosProjections();
    }

    public WosIndexMaintenanceService.WosIndexEnsureResult ensureWosIndexes() {
        return wosIndexMaintenanceService.ensureWosIndexes();
    }
}
