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
        assertEquals("A", result.items().get(0).latestRank());
        assertEquals(2023, result.items().get(0).latestYear());
        assertEquals(List.of(new ro.uvt.pokedex.core.controller.dto.CoreRankingListItemResponse.TrendPoint(2023, 11, "A")),
                result.items().get(0).trend());
        assertEquals(null, result.items().get(1).latestRank());
        assertEquals(List.of(), result.items().get(1).trend());

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

    @Test
    void listItemTrendKeepsTheLastEditionsSortedAndLabelled() {
        CoreConferenceRanking conf = new CoreConferenceRanking();
        conf.setId("c");
        conf.setName("Conf");
        conf.setAcronym("C");
        // Unsorted map with string keys (Mongo shape) across 7 editions; only the last 6 make the trend.
        Map<Object, CoreConferenceRanking.YearlyRanking> editions = new java.util.HashMap<>();
        editions.put("2008", yearly("C"));
        editions.put("2013", yearly("B"));
        editions.put("2014", yearly("B"));
        editions.put("2017", yearly("A"));
        editions.put("2020", yearly("A"));
        editions.put("2023", yearly("A_STAR"));
        editions.put("2026", yearly("A_STAR"));
        @SuppressWarnings("unchecked")
        Map<Integer, CoreConferenceRanking.YearlyRanking> cast = (Map<Integer, CoreConferenceRanking.YearlyRanking>) (Map<?, ?>) editions;
        conf.setYearlyRankings(cast);

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(List.of(conf));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(CoreConferenceRanking.class)))
                .thenReturn(1L);

        var item = service.search(0, 25, "name", "asc", null).items().getFirst();

        assertEquals("A*", item.latestRank());
        assertEquals(2026, item.latestYear());
        assertEquals(6, item.trend().size());
        assertEquals(2013, item.trend().getFirst().year()); // 2008 dropped by the 6-edition window
        assertEquals(2026, item.trend().getLast().year());
        assertEquals(12, item.trend().getLast().value());
        assertEquals("A*", item.trend().getLast().label());
    }
}
