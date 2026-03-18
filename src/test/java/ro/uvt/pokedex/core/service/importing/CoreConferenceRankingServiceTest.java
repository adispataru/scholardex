package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CoreConferenceRankingServiceTest {

    @Test
    void loadRankingsFromCsvHandlesEmptyDirectory() throws Exception {
        CoreConferenceRankingRepository repository = mock(CoreConferenceRankingRepository.class);
        CacheService cacheService = mock(CacheService.class);
        CoreConferenceRankingService service = new CoreConferenceRankingService(repository, cacheService);

        Path dir = Files.createTempDirectory("core-empty");
        service.loadRankingsFromCSV(dir.toString());

        verifyNoInteractions(cacheService);
    }
}
