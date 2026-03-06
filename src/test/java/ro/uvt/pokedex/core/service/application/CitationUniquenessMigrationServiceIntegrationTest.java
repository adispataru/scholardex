package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataMongoTest
@Import(CitationUniquenessMigrationService.class)
class CitationUniquenessMigrationServiceIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private CitationUniquenessMigrationService service;

    @Autowired
    private ScopusCitationRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollectionAndIndexes() {
        repository.deleteAll();
        mongoTemplate.indexOps(Citation.class).dropAllIndexes();
    }

    @Test
    void reportScanFindsDuplicatesWithoutMutation() {
        repository.save(citation("c3", "p1", "p2"));
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p1", "p2"));
        repository.save(citation("c4", "p2", "p3"));

        CitationUniquenessMigrationService.DuplicateScanResult scan = service.scanDuplicates();

        assertEquals(1, scan.duplicateGroupCount());
        assertEquals(3, scan.duplicateRowCount());
        assertEquals(4, repository.count());
    }

    @Test
    void applyModeDedupeAndUniqueIndexEnforcesPairUniqueness() {
        repository.save(citation("c3", "p1", "p2"));
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p1", "p2"));
        repository.save(citation("c4", "p2", "p3"));

        CitationUniquenessMigrationService.DuplicateScanResult scan = service.scanDuplicates();
        CitationUniquenessMigrationService.DedupeResult result = service.applyDedupeKeepingLowestId(scan);
        service.ensureUniqueIndex();
        CitationUniquenessMigrationService.VerificationResult verification = service.verifyPostConditions();

        assertEquals(1, result.affectedPairs());
        assertEquals(2, result.deletedRows());
        assertTrue(verification.duplicatesRemoved());
        assertTrue(verification.uniqueIndexPresent());

        List<Citation> remaining = repository.findAllByCitedId("p1");
        assertEquals(1, remaining.size());
        assertEquals("c1", remaining.getFirst().getId());
        assertEquals(2, repository.count());

        repository.save(citation("c10", "p9", "p9"));
        assertThrows(DuplicateKeyException.class, () -> repository.save(citation("c11", "p9", "p9")));

        List<Citation> nonDuplicatePair = repository.findAllByCitedId("p2");
        assertFalse(nonDuplicatePair.isEmpty());
        assertEquals("p3", nonDuplicatePair.getFirst().getCitingId());
    }

    private static Citation citation(String id, String citedId, String citingId) {
        Citation citation = new Citation();
        citation.setId(id);
        citation.setCitedId(citedId);
        citation.setCitingId(citingId);
        return citation;
    }
}
