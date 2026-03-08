package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
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
    private ScholardexCitationFactRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollectionAndIndexes() {
        repository.deleteAll();
        mongoTemplate.indexOps(ScholardexCitationFact.class).dropAllIndexes();
    }

    @Test
    void reportScanFindsDuplicatesWithoutMutation() {
        repository.save(citation("c3", "p1", "p2", "SCOPUS"));
        repository.save(citation("c1", "p1", "p2", "SCOPUS"));
        repository.save(citation("c2", "p1", "p2", "SCOPUS"));
        repository.save(citation("c4", "p2", "p3", "SCOPUS"));

        CitationUniquenessMigrationService.DuplicateScanResult scan = service.scanDuplicates();

        assertEquals(1, scan.duplicateGroupCount());
        assertEquals(3, scan.duplicateRowCount());
        assertEquals(4, repository.count());
    }

    @Test
    void applyModeDedupeAndUniqueIndexEnforcesPairUniqueness() {
        repository.save(citation("c3", "p1", "p2", "SCOPUS"));
        repository.save(citation("c1", "p1", "p2", "SCOPUS"));
        repository.save(citation("c2", "p1", "p2", "SCOPUS"));
        repository.save(citation("c4", "p2", "p3", "SCOPUS"));

        CitationUniquenessMigrationService.DuplicateScanResult scan = service.scanDuplicates();
        CitationUniquenessMigrationService.DedupeResult result = service.applyDedupeKeepingLowestId(scan);
        service.ensureUniqueIndex();
        CitationUniquenessMigrationService.VerificationResult verification = service.verifyPostConditions();

        assertEquals(1, result.affectedPairs());
        assertEquals(2, result.deletedRows());
        assertTrue(verification.duplicatesRemoved());
        assertTrue(verification.uniqueIndexPresent());

        List<ScholardexCitationFact> remaining = repository.findByCitedPublicationId("p1");
        assertEquals(1, remaining.size());
        assertEquals("c1", remaining.getFirst().getId());
        assertEquals(2, repository.count());

        repository.save(citation("c10", "p9", "p9", "SCOPUS"));
        assertThrows(DuplicateKeyException.class, () -> repository.save(citation("c11", "p9", "p9", "SCOPUS")));
        repository.save(citation("c12", "p9", "p9", "WOS"));

        List<ScholardexCitationFact> nonDuplicatePair = repository.findByCitedPublicationId("p2");
        assertFalse(nonDuplicatePair.isEmpty());
        assertEquals("p3", nonDuplicatePair.getFirst().getCitingPublicationId());
    }

    private static ScholardexCitationFact citation(String id, String citedId, String citingId, String source) {
        ScholardexCitationFact citation = new ScholardexCitationFact();
        citation.setId(id);
        citation.setCitedPublicationId(citedId);
        citation.setCitingPublicationId(citingId);
        citation.setSource(source);
        return citation;
    }
}
