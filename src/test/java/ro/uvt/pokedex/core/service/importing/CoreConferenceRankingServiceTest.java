package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CoreConferenceRankingServiceTest {

    @Test
    void loadRankingsFromCsvHandlesEmptyDirectory() throws Exception {
        CoreConferenceRankingService service = new CoreConferenceRankingService();
        ReflectionTestUtils.setField(service, "coreConferenceRankingRepository", mock(CoreConferenceRankingRepository.class));
        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);

        Path dir = Files.createTempDirectory("core-empty");
        service.loadRankingsFromCSV(dir.toString());

        verifyNoInteractions(cacheService);
    }
}
