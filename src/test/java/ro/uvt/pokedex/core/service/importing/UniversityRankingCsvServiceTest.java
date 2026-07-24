package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.UniversityRanking;
import ro.uvt.pokedex.core.repository.UniversityRankingRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H83 S2 — generic multi-source ranking CSV loader (band lower-bounds, per-source load-once guard). */
@ExtendWith(MockitoExtension.class)
class UniversityRankingCsvServiceTest {

    @Mock
    private UniversityRankingRepository repository;

    @TempDir
    Path dir;

    @Test
    void loadsYearsIntoOneDocPerUniversityWithBands() throws IOException {
        Files.writeString(dir.resolve("ARWU_WR_2010.csv"), """
                rank,rankBand,name,country
                1,1,Harvard University,United States
                101,101-150,University of Pisa,Italy
                """);
        Files.writeString(dir.resolve("ARWU_WR_2015.csv"), """
                rank,rankBand,name,country
                151,151-200,University of Pisa,Italy
                ,,broken row,
                """);
        Files.writeString(dir.resolve("ignored.csv"), "rank,rankBand,name,country\n9,9,Nope,X\n");
        when(repository.countBySource("ARWU")).thenReturn(0L);

        new UniversityRankingCsvService(repository).loadSourceFromFolder("ARWU", dir.toString(), "ARWU");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UniversityRanking>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(repository).saveAll(captor.capture());
        Map<String, UniversityRanking> byName = captor.getValue().stream()
                .collect(Collectors.toMap(UniversityRanking::getName, Function.identity()));
        assertEquals(2, byName.size());
        UniversityRanking pisa = byName.get("University of Pisa");
        assertEquals("ARWU|University of Pisa", pisa.getId());
        assertEquals(101, pisa.getRanks().get(2010).getRank());
        assertEquals("101-150", pisa.getRanks().get(2010).getRankBand());
        assertEquals(151, pisa.getRanks().get(2015).getRank());
        assertEquals("1", byName.get("Harvard University").getRanks().get(2010).getRankBand());
    }

    @Test
    void loadOnceGuardSkipsWhenSourceAlreadyLoaded() {
        when(repository.countBySource("ARWU")).thenReturn(5L);
        new UniversityRankingCsvService(repository).loadSourceFromFolder("ARWU", dir.toString(), "ARWU");
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void quotedNamesWithCommasParse() throws IOException {
        Files.writeString(dir.resolve("ARWU_WR_2020.csv"), """
                rank,rankBand,name,country
                55,55,"University of California, Berkeley",United States
                """);
        when(repository.countBySource("ARWU")).thenReturn(0L);

        new UniversityRankingCsvService(repository).loadSourceFromFolder("ARWU", dir.toString(), "ARWU");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UniversityRanking>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals("University of California, Berkeley", captor.getValue().get(0).getName());
    }
}
