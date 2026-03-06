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
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosRankingQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private WosRankingQueryService service;

    @BeforeEach
    void setUp() {
        service = new WosRankingQueryService(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsResponse() {
        WosRankingView a = ranking("1", "A Journal", "1111", "2222", List.of("3333"));
        WosRankingView b = ranking("2", "B Journal", "4444", "5555", List.of());

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(WosRankingView.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(WosRankingView.class)))
                .thenReturn(11L);

        WosRankingPageResponse result = service.search(1, 5, "name", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals("1", result.items().get(0).id());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(WosRankingView.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("name"));
    }

    @Test
    void searchWithQueryAddsPrefixRegexCriteriaOnNormalizedFields() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(WosRankingView.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(WosRankingView.class)))
                .thenReturn(0L);

        service.search(0, 25, "issn", "desc", "ab cd");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(WosRankingView.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("issn"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("nameNorm"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("issnNorm"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("eIssnNorm"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("alternativeIssnsNorm"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("^\\\\Qab cd\\\\E"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("^\\\\QABCD\\\\E"));
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

    private WosRankingView ranking(String id, String name, String issn, String eIssn, List<String> altIssns) {
        WosRankingView view = new WosRankingView();
        view.setId(id);
        view.setName(name);
        view.setIssn(issn);
        view.setEIssn(eIssn);
        view.setAlternativeIssns(altIssns);
        return view;
    }
}
