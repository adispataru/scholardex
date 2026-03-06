package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RankingMaintenanceFacade {
    private final CacheService cacheService;
    private final RankingRepository rankingRepository;
    private final WosProjectionBuilderService wosProjectionBuilderService;
    private final WosIndexMaintenanceService wosIndexMaintenanceService;

    public void computePositionsForKnownQuarters() {
        List<WoSRanking> rankings = cacheService.getAllRankings();
        WoSRanking.rankByAisWithQuarterKnown(rankings);
        rankingRepository.saveAll(rankings);
        cacheService.cacheRankings();
    }

    public void computeQuartersAndRankingsWhereMissing() {
        List<WoSRanking> rankings = cacheService.getAllRankings();
        WoSRanking.rankByAisAndEstablishQuarters(rankings);
        rankingRepository.saveAll(rankings);
        cacheService.cacheRankings();
    }

    public void mergeDuplicateRankings() {
        List<WoSRanking> rankings = cacheService.getAllRankings();
        rankings.stream().collect(Collectors.groupingBy(WoSRanking::getName)).forEach((name, group) -> {
            if (group.size() > 1) {
                WoSRanking merged = group.getFirst();
                for (int i = 1; i < group.size(); i++) {
                    merged.merge(group.get(i));
                }
                rankingRepository.save(merged);
                for (int i = 1; i < group.size(); i++) {
                    rankingRepository.delete(group.get(i));
                }
            }
        });
        cacheService.cacheRankings();
    }

    public ImportProcessingResult rebuildWosProjections() {
        return wosProjectionBuilderService.rebuildWosProjections();
    }

    public WosIndexMaintenanceService.WosIndexEnsureResult ensureWosIndexes() {
        return wosIndexMaintenanceService.ensureWosIndexes();
    }
}
