package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RankingMaintenanceFacadeTest {

    @Mock
    private CacheService cacheService;
    @Mock
    private RankingRepository rankingRepository;

    @InjectMocks
    private RankingMaintenanceFacade facade;

    @Test
    void computePositionsForKnownQuartersPersistsAndRefreshesCache() {
        WoSRanking ranking = ranking("J1");
        when(cacheService.getAllRankings()).thenReturn(List.of(ranking));

        facade.computePositionsForKnownQuarters();

        verify(rankingRepository).saveAll(anyList());
        verify(cacheService).cacheRankings();
    }

    @Test
    void computeQuartersAndRankingsWhereMissingPersistsAndRefreshesCache() {
        WoSRanking ranking = ranking("J1");
        when(cacheService.getAllRankings()).thenReturn(List.of(ranking));

        facade.computeQuartersAndRankingsWhereMissing();

        verify(rankingRepository).saveAll(anyList());
        verify(cacheService).cacheRankings();
    }

    @Test
    void mergeDuplicateRankingsPersistsMergedDeletesDuplicateAndRefreshesCache() {
        WoSRanking first = ranking("Same");
        first.setIssn("0000-0001");
        WoSRanking second = ranking("Same");
        second.setEIssn("0000-0002");
        when(cacheService.getAllRankings()).thenReturn(List.of(first, second));

        facade.mergeDuplicateRankings();

        verify(rankingRepository).save(any(WoSRanking.class));
        verify(rankingRepository).delete(any(WoSRanking.class));
        verify(cacheService).cacheRankings();
    }

    @Test
    void mergeDuplicateRankingsDoesNotDeleteWhenNoDuplicates() {
        WoSRanking first = ranking("A");
        WoSRanking second = ranking("B");
        when(cacheService.getAllRankings()).thenReturn(List.of(first, second));

        facade.mergeDuplicateRankings();

        verify(rankingRepository, never()).delete(any(WoSRanking.class));
        verify(cacheService).cacheRankings();
    }

    @Test
    void computeMethodsHandleEmptyRankingsList() {
        when(cacheService.getAllRankings()).thenReturn(List.of());

        facade.computePositionsForKnownQuarters();
        facade.computeQuartersAndRankingsWhereMissing();

        verify(rankingRepository, times(2)).saveAll(anyList());
        verify(cacheService, times(2)).cacheRankings();
    }

    private static WoSRanking ranking(String name) {
        WoSRanking ranking = new WoSRanking();
        ranking.setName(name);
        ranking.setScore(new WoSRanking.Score());
        return ranking;
    }
}
