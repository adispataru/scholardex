package ro.uvt.pokedex.core.repository.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataMongoTest
class ScopusCitationRepositoryIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private ScopusCitationRepository repository;

    @BeforeEach
    void cleanAndSeed() {
        repository.deleteAll();
    }

    @Test
    void findAllByCitedIdInReturnsMatchingCitations() {
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p3", "p4"));

        List<Citation> results = repository.findAllByCitedIdIn(List.of("p1", "x"));

        assertEquals(1, results.size());
        assertEquals("c1", results.getFirst().getId());
    }

    @Test
    void findAllByCitedIdReturnsOnlyTargetCitations() {
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p1", "p3"));
        repository.save(citation("c3", "p9", "p8"));

        List<Citation> results = repository.findAllByCitedId("p1");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> "p1".equals(c.getCitedId())));
    }

    @Test
    void countAllByCitedIdReturnsCorrectCount() {
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p1", "p3"));
        repository.save(citation("c3", "p2", "p1"));

        long count = repository.countAllByCitedId("p1");

        assertEquals(2, count);
    }

    @Test
    void findByCitedIdAndCitingIdFindsExistingPairDeterministically() {
        repository.save(citation("c1", "p1", "p2"));
        repository.save(citation("c2", "p1", "p3"));

        var existing = repository.findByCitedIdAndCitingId("p1", "p2");
        var missing = repository.findByCitedIdAndCitingId("p1", "p4");

        assertTrue(existing.isPresent());
        assertEquals("c1", existing.get().getId());
        assertFalse(missing.isPresent());
        assertEquals(2, repository.countAllByCitedId("p1"));
    }

    private static Citation citation(String id, String citedId, String citingId) {
        Citation citation = new Citation();
        citation.setId(id);
        citation.setCitedId(citedId);
        citation.setCitingId(citingId);
        return citation;
    }
}
