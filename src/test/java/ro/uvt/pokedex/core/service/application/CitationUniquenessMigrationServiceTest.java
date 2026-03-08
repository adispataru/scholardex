package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CitationUniquenessMigrationServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ScholardexCitationFactRepository scholardexCitationFactRepository;

    private CitationUniquenessMigrationService service;

    @BeforeEach
    void setUp() {
        service = new CitationUniquenessMigrationService(mongoTemplate, scholardexCitationFactRepository);
    }

    @Test
    void buildCandidateKeepsLowestIdAndDeletesTheRest() {
        CitationUniquenessMigrationService.DedupeCandidate candidate =
                service.buildCandidate(List.of("c3", "c1", "c2"));

        assertEquals("c1", candidate.keptId());
        assertEquals(List.of("c2", "c3"), candidate.idsToDelete());
    }

    @Test
    void applyDedupeNoOpWhenNoDuplicates() {
        CitationUniquenessMigrationService.DuplicateScanResult scanResult =
                new CitationUniquenessMigrationService.DuplicateScanResult(List.of());

        CitationUniquenessMigrationService.DedupeResult dedupeResult =
                service.applyDedupeKeepingLowestId(scanResult);

        assertEquals(0, dedupeResult.affectedPairs());
        assertEquals(0, dedupeResult.deletedRows());
        verify(scholardexCitationFactRepository, never()).deleteAllById(org.mockito.ArgumentMatchers.anyIterable());
    }

    @Test
    void applyDedupeDeletesAllButLowestIdPerPair() {
        CitationUniquenessMigrationService.DuplicatePair pair1 =
                new CitationUniquenessMigrationService.DuplicatePair("p1", "p2", "SCOPUS", List.of("c3", "c1", "c2"));
        CitationUniquenessMigrationService.DuplicatePair pair2 =
                new CitationUniquenessMigrationService.DuplicatePair("p4", "p5", "WOS", List.of("c10", "c9"));
        CitationUniquenessMigrationService.DuplicateScanResult scanResult =
                new CitationUniquenessMigrationService.DuplicateScanResult(List.of(pair1, pair2));

        CitationUniquenessMigrationService.DedupeResult dedupeResult =
                service.applyDedupeKeepingLowestId(scanResult);

        assertEquals(2, dedupeResult.affectedPairs());
        assertEquals(3, dedupeResult.deletedRows());
        verify(scholardexCitationFactRepository).deleteAllById(List.of("c2", "c3"));
        verify(scholardexCitationFactRepository).deleteAllById(List.of("c9"));
    }

    @Test
    void buildCandidateHandlesNullAndBlankIdsSafely() {
        List<String> ids = new ArrayList<>(Arrays.asList(null, "", "  ", "c7"));
        CitationUniquenessMigrationService.DedupeCandidate candidate =
                service.buildCandidate(ids);

        assertEquals("c7", candidate.keptId());
        assertTrue(candidate.idsToDelete().isEmpty());
    }
}
