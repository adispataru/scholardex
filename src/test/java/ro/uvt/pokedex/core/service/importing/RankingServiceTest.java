package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;

class RankingServiceTest {

    @Test
    void loadRankingsFromExcelHandlesEmptyFolderDeterministically() throws Exception {
        RankingService service = new RankingService();
        RankingRepository rankingRepository = mock(RankingRepository.class);
        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "rankingRepository", rankingRepository);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);

        Path dir = Files.createTempDirectory("ranking-empty");
        service.loadRankingsFromExcel(dir.toString(), "pwd");

        verify(cacheService).cacheRankings();
    }
}
