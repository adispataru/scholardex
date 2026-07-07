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
import ro.uvt.pokedex.core.controller.dto.UrapRankingPageResponse;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrapRankingQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private UrapRankingQueryService service;

    @BeforeEach
    void setUp() {
        service = new UrapRankingQueryService(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedQueryAndMapsLatestYearResponse() {
        URAPUniversityRanking a = ranking("University A", "RO", Map.of(
                2023, score(10, 1.0),
                2025, score(5, 3.0)
        ));
        URAPUniversityRanking b = ranking("University B", "US", Map.of());

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(URAPUniversityRanking.class)))
                .thenReturn(List.of(a, b));
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(URAPUniversityRanking.class)))
                .thenReturn(11L);

        UrapRankingPageResponse result = service.search(1, 5, "name", "asc", null);

        assertEquals(1, result.page());
        assertEquals(5, result.size());
        assertEquals(11L, result.totalItems());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.items().size());
        assertEquals(2025, result.items().get(0).year());
        assertEquals(5, result.items().get(0).rank());
        assertEquals(9.0, result.items().get(0).total());
        // Trend carries both metrics per year, sorted ascending.
        assertEquals(List.of(
                new ro.uvt.pokedex.core.controller.dto.UrapRankingListItemResponse.TrendPoint(2023, 10, 7.0),
                new ro.uvt.pokedex.core.controller.dto.UrapRankingListItemResponse.TrendPoint(2025, 5, 9.0)),
                result.items().get(0).trend());
        assertNull(result.items().get(1).year());
        assertEquals(List.of(), result.items().get(1).trend());

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(URAPUniversityRanking.class));
        Query findQuery = findQueryCaptor.getValue();
        assertEquals(5, findQuery.getLimit());
        assertEquals(5L, findQuery.getSkip());
        Document sortDoc = findQuery.getSortObject();
        assertEquals(1, sortDoc.getInteger("_id"));
    }

    @Test
    void searchWithQueryAddsOrRegexCriteria() {
        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(URAPUniversityRanking.class)))
                .thenReturn(List.of());
        when(mongoTemplate.count(org.mockito.ArgumentMatchers.any(Query.class), eq(URAPUniversityRanking.class)))
                .thenReturn(0L);

        service.search(0, 25, "country", "desc", "romania");

        ArgumentCaptor<Query> findQueryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(findQueryCaptor.capture(), eq(URAPUniversityRanking.class));
        Query findQuery = findQueryCaptor.getValue();
        String queryJson = findQuery.getQueryObject().toJson();
        assertEquals(-1, findQuery.getSortObject().getInteger("country"));
        org.junit.jupiter.api.Assertions.assertTrue(queryJson.contains("_id"));
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

    @Test
    void computedSortsOrderByLatestEditionInMemory() {
        URAPUniversityRanking first = ranking("Uni A", "RO", Map.of(2024, score(100, 1.0)));
        URAPUniversityRanking second = ranking("Uni B", "RO", Map.of(2024, score(50, 2.0)));
        URAPUniversityRanking noData = ranking("Uni C", "RO", Map.of());

        when(mongoTemplate.find(org.mockito.ArgumentMatchers.any(Query.class), eq(URAPUniversityRanking.class)))
                .thenReturn(List.of(first, noData, second));

        UrapRankingPageResponse byRank = service.search(0, 10, "rank", "asc", null);
        assertEquals(3, byRank.totalItems());
        assertEquals("Uni B", byRank.items().get(0).name()); // rank 50 before 100
        assertEquals("Uni A", byRank.items().get(1).name());
        assertEquals("Uni C", byRank.items().get(2).name()); // missing values last

        UrapRankingPageResponse byTotalDesc = service.search(0, 10, "total", "desc", null);
        assertEquals("Uni B", byTotalDesc.items().get(0).name()); // total 8.0 (2.0+6) before 7.0
        assertEquals("Uni C", byTotalDesc.items().get(2).name()); // still last on desc
    }

    private URAPUniversityRanking ranking(String name, String country, Map<Integer, URAPUniversityRanking.Score> scores) {
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setName(name);
        ranking.setCountry(country);
        ranking.setScores(scores);
        return ranking;
    }

    private URAPUniversityRanking.Score score(int rank, double article) {
        URAPUniversityRanking.Score score = new URAPUniversityRanking.Score();
        score.setRank(rank);
        score.setArticle(article);
        score.setCitation(article + 1);
        score.setTotalDocument(article + 2);
        score.setAIT(article + 3);
        score.setCIT(article + 4);
        score.setCollaboration(article + 5);
        score.setTotal(article + 6);
        return score;
    }
}
