package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtisticEventsServiceTest {

    @Mock ArtisticEventRepository artisticEventRepository;
    @Mock DomainRepository domainRepository;

    @BeforeAll
    static void stageFixture() throws Exception {
        Path target = Paths.get("data/arts/event_rankings.json");
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            try (InputStream in = ArtisticEventsServiceTest.class
                    .getResourceAsStream("/fixtures/arts/event_rankings.json")) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath fixture: /fixtures/arts/event_rankings.json");
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private ArtisticEventsService service() {
        return new ArtisticEventsService(artisticEventRepository, domainRepository);
    }

    @Test
    void importArtisticEventsFromJson_repoNotEmpty_skipsImport() {
        when(artisticEventRepository.count()).thenReturn(5L);
        service().importArtisticEventsFromJson();
        verify(artisticEventRepository, never()).save(any());
    }

    @Test
    void importArtisticEventsFromJson_repoEmpty_runsWithoutThrowing() {
        when(artisticEventRepository.count()).thenReturn(0L);
        Domain mockDomain = new Domain();
        mockDomain.setName("TestDomain");
        // File at data/arts/event_rankings.json exists in repo — stub domain lookup and save
        lenient().when(domainRepository.findByName(any())).thenReturn(Optional.of(mockDomain));
        lenient().when(domainRepository.save(any())).thenReturn(mockDomain);
        lenient().when(artisticEventRepository.save(any())).thenReturn(null);

        service().importArtisticEventsFromJson();
        // No assert needed — verifying it completes without NPE or uncaught exception
        verify(artisticEventRepository, atLeastOnce()).save(any());
    }
}
