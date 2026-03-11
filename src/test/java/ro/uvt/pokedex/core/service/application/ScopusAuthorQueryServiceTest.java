package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationViewRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusAuthorQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ScholardexAffiliationViewRepository affiliationViewRepository;

    private MongoScopusAuthorReadPort service;

    @BeforeEach
    void setUp() {
        service = new MongoScopusAuthorReadPort(mongoTemplate, affiliationViewRepository);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsResponse() {
        ScholardexAuthorView a = author("1", "Alice", List.of("af-1"));
        ScholardexAuthorView b = author("2", "Bob", List.of());
        ScholardexAffiliationView af1 = affiliation("af-1", "UVT");

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAuthorView.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAuthorView.class)))
                .thenReturn(11L);
        when(affiliationViewRepository.findByIdIn(List.of("af-1"))).thenReturn(List.of(af1));

        ScopusAuthorPageResponse result = service.search("af-1", 1, 5, "name", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals("1", result.items().get(0).id());
        assertEquals(List.of("UVT"), result.items().get(0).affiliations());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexAuthorView.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("name"));
        String queryJson = findQuery.getQueryObject().toJson();
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("affiliationIds"));
    }

    @Test
    void searchWithQueryAddsRegexCriteria() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAuthorView.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAuthorView.class)))
                .thenReturn(0L);

        service.search("af-1", 0, 25, "id", "desc", "abc");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexAuthorView.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("_id"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("name"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("_id"));
    }

    @Test
    void invalidSortThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search("af-1", 0, 25, "bad", "asc", null));
    }

    @Test
    void invalidDirectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search("af-1", 0, 25, "name", "up", null));
    }

    @Test
    void invalidQueryLengthThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search("af-1", 0, 25, "name", "asc", "x".repeat(101)));
    }

    private ScholardexAuthorView author(String id, String name, List<String> affiliationIds) {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId(id);
        author.setName(name);
        author.setAffiliationIds(affiliationIds);
        return author;
    }

    private ScholardexAffiliationView affiliation(String id, String name) {
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setId(id);
        affiliation.setName(name);
        return affiliation;
    }
}
