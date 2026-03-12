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
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexAffiliationQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private MongoScholardexAffiliationReadPort service;

    @BeforeEach
    void setUp() {
        service = new MongoScholardexAffiliationReadPort(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsResponse() {
        ScholardexAffiliationView a = affiliation("1", "UVT", "Timisoara", "Romania");
        ScholardexAffiliationView b = affiliation("2", "MIT", "Cambridge", "USA");

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAffiliationView.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAffiliationView.class)))
                .thenReturn(11L);

        ScopusAffiliationPageResponse result = service.search(1, 5, "name", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals("1", result.items().get(0).afid());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexAffiliationView.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("name"));
        assertEquals(1, sortDoc.getInteger("_id"));
    }

    @Test
    void searchWithQueryAddsRegexCriteria() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAffiliationView.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexAffiliationView.class)))
                .thenReturn(0L);

        service.search(0, 25, "afid", "desc", "abc");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexAffiliationView.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("_id"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("name"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("_id"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("city"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("country"));
    }

    @Test
    void invalidSortThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "bad", "asc", null));
    }

    @Test
    void invalidDirectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "name", "up", null));
    }

    @Test
    void invalidQueryLengthThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "name", "asc", "x".repeat(101)));
    }

    private ScholardexAffiliationView affiliation(String afid, String name, String city, String country) {
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setId(afid);
        affiliation.setName(name);
        affiliation.setCity(city);
        affiliation.setCountry(country);
        return affiliation;
    }
}
