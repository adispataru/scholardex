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
import ro.uvt.pokedex.core.controller.dto.CoreRankingPageResponse;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoreRankingQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private CoreRankingQueryService service;

    @BeforeEach
    void setUp() {
        service = new CoreRankingQueryService(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsResponse() {
        CoreConferenceRanking a = ranking("1", "Conference A", "CA", "A");
        CoreConferenceRanking b = ranking("2", "Conference B", "CB", null);

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(11L);

        CoreRankingPageResponse result = service.search(1, 5, "name", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals("A", result.items().get(0).category2023());
        assertEquals(null, result.items().get(1).category2023());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(CoreConferenceRanking.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("name"));
    }

    @Test
    void searchWithQueryAddsOrRegexCriteria() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(0L);

        service.search(0, 25, "acronym", "desc", "abc");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(CoreConferenceRanking.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("acronym"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("name"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("acronym"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("sourceId"));
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

    private CoreConferenceRanking ranking(String id, String name, String acronym, String category2023) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId(id);
        ranking.setName(name);
        ranking.setAcronym(acronym);
        ranking.setYearlyRankings(category2023 == null ? Map.of() : Map.of(2023, yearly(category2023)));
        return ranking;
    }

    private CoreConferenceRanking.YearlyRanking yearly(String rankName) {
        CoreConferenceRanking.YearlyRanking yearly = new CoreConferenceRanking.YearlyRanking();
        yearly.setRank(CoreConferenceRanking.Rank.valueOf(rankName));
        return yearly;
    }
}
