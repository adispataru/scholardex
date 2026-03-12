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
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusForumQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private MongoScopusForumReadPort service;

    @BeforeEach
    void setUp() {
        service = new MongoScopusForumReadPort(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsResponse() {
        ScholardexForumView a = forum("1", "ACM Journal", "1111", "2222", "Journal");
        ScholardexForumView b = forum("2", "IEEE Journal", "4444", "5555", "Conference");

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexForumView.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexForumView.class)))
                .thenReturn(11L);

        ScopusForumPageResponse result = service.search(1, 5, "publicationName", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals("1", result.items().get(0).id());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexForumView.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("publicationName"));
        assertEquals(1, sortDoc.getInteger("_id"));
    }

    @Test
    void searchWithQueryAddsOrRegexCriteria() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexForumView.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(ScholardexForumView.class)))
                .thenReturn(0L);

        service.search(0, 25, "issn", "desc", "abc");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(ScholardexForumView.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("issn"));
        assertEquals(-1, findQuery.getSortObject().getInteger("_id"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("publicationName"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("issn"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("eIssn"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("aggregationType"));
    }

    @Test
    void invalidSortThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "bad", "asc", null));
    }

    @Test
    void invalidDirectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "publicationName", "up", null));
    }

    @Test
    void invalidQueryLengthThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "publicationName", "asc", "x".repeat(101)));
    }

    private ScholardexForumView forum(String id, String name, String issn, String eIssn, String aggregationType) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(name);
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        forum.setAggregationType(aggregationType);
        return forum;
    }
}
