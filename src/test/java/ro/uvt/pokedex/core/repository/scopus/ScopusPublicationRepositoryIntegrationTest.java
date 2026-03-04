package ro.uvt.pokedex.core.repository.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataMongoTest
class ScopusPublicationRepositoryIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private ScopusPublicationRepository repository;

    @BeforeEach
    void cleanAndSeed() {
        repository.deleteAll();
    }

    @Test
    void findAllByAuthorsInReturnsMatchingDocuments() {
        repository.save(publication("p1", "Title One", "2023-01-01", List.of("a1")));
        repository.save(publication("p2", "Title Two", "2022-01-01", List.of("a2")));

        List<Publication> results = repository.findAllByAuthorsIn(List.of("a1"));

        assertEquals(1, results.size());
        assertEquals("p1", results.getFirst().getId());
    }

    @Test
    void findAllByAuthorsInAndTitleContainsFiltersByAuthorAndTitle() {
        repository.save(publication("p1", "Deep Learning Systems", "2023-01-01", List.of("a1")));
        repository.save(publication("p2", "Deep Space Exploration", "2023-01-01", List.of("a2")));
        repository.save(publication("p3", "Compiler Design", "2023-01-01", List.of("a1")));

        List<Publication> results = repository.findAllByAuthorsInAndTitleContains(List.of("a1"), "Deep");

        assertEquals(1, results.size());
        assertEquals("p1", results.getFirst().getId());
    }

    @Test
    void findByTitleContainingIgnoreCaseOrderByCoverDateDescMatchesCaseInsensitively() {
        repository.save(publication("p1", "Deep Learning Systems", "2023-01-01", List.of("a1")));
        repository.save(publication("p2", "DEEP Space Exploration", "2024-01-01", List.of("a2")));
        repository.save(publication("p3", "Compiler Design", "2025-01-01", List.of("a1")));

        List<Publication> results = repository.findByTitleContainingIgnoreCaseOrderByCoverDateDesc("deep");

        assertEquals(2, results.size());
        assertEquals("p2", results.get(0).getId());
        assertEquals("p1", results.get(1).getId());
    }

    @Test
    void findTopByAuthorsContainsOrderByCoverDateDescReturnsLatestCoverDate() {
        repository.save(publication("p1", "Old", "2021-02-01", List.of("a1")));
        repository.save(publication("p2", "New", "2024-05-01", List.of("a1")));
        repository.save(publication("p3", "Other", "2023-03-01", List.of("a2")));

        var result = repository.findTopByAuthorsContainsOrderByCoverDateDesc("a1");

        assertTrue(result.isPresent());
        assertEquals("p2", result.get().getId());
    }

    private static Publication publication(String id, String title, String coverDate, List<String> authors) {
        Publication publication = new Publication();
        publication.setId(id);
        publication.setTitle(title);
        publication.setCoverDate(coverDate);
        publication.setAuthors(authors);
        publication.setForum("f1");
        return publication;
    }
}
