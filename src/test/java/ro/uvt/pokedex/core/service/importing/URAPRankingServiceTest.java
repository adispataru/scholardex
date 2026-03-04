package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class URAPRankingServiceTest {

    @Test
    void loadRankingsFromFolderWithNoMatchingFilesSavesDeterministicEmptyBatch() throws Exception {
        URAPUniversityRankingRepository repository = mock(URAPUniversityRankingRepository.class);
        when(repository.count()).thenReturn(0L);

        URAPRankingService service = new URAPRankingService(repository);
        Path dir = Files.createTempDirectory("urap-empty");

        service.loadRankingsFromFolder(dir.toString());

        verify(repository).saveAll(anyList());
    }
}
