package ro.uvt.pokedex.core.service.application;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;
import ro.uvt.pokedex.core.controller.dto.WosCategoryListItemResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosCategoryQueryServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private WosCategoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new WosCategoryQueryService(mongoTemplate);
    }

    @Test
    void searchBuildsPagedSortedResponse() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryQueryService.CountRow.class)))
                .thenReturn(new AggregationResults<>(List.of(new WosCategoryQueryService.CountRow(2L)), new Document()));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryListItemResponse.class)))
                .thenReturn(new AggregationResults<>(List.of(
                        new WosCategoryListItemResponse("Computer Science - SCIE", "Computer Science", "SCIE", 2, 2024)
                ), new Document()));

        WosCategoryPageResponse result = service.search(0, 1, "journalCount", "desc", null);

        assertEquals(0, result.page());
        assertEquals(1, result.size());
        assertEquals(2, result.totalItems());
        assertEquals(2, result.totalPages());
        assertEquals(1, result.items().size());
        assertEquals("Computer Science - SCIE", result.items().get(0).key());
        assertEquals(2, result.items().get(0).journalCount());
        verify(mongoTemplate).aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryQueryService.CountRow.class));
        verify(mongoTemplate).aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryListItemResponse.class));
    }

    @Test
    void searchFiltersByCategoryNameAndEdition() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryQueryService.CountRow.class)))
                .thenReturn(new AggregationResults<>(List.of(new WosCategoryQueryService.CountRow(1L)), new Document()));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryListItemResponse.class)))
                .thenReturn(new AggregationResults<>(List.of(
                        new WosCategoryListItemResponse("Economics - SSCI", "Economics", "SSCI", 2, 2023)
                ), new Document()));

        assertEquals(1, service.search(0, 25, "categoryName", "asc", "computer").items().size());
        assertEquals("Economics - SSCI", service.search(0, 25, "categoryName", "asc", "ssci").items().get(0).key());
    }

    @Test
    void pageBeyondTotalUsesLastPage() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryQueryService.CountRow.class)))
                .thenReturn(new AggregationResults<>(List.of(new WosCategoryQueryService.CountRow(2L)), new Document()));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("wos.category_facts"), eq(WosCategoryListItemResponse.class)))
                .thenReturn(new AggregationResults<>(List.of(
                        new WosCategoryListItemResponse("Economics - SSCI", "Economics", "SSCI", 1, 2023)
                ), new Document()));

        WosCategoryPageResponse result = service.search(9, 1, "categoryName", "asc", null);

        assertEquals(1, result.page());
        assertEquals(1, result.items().size());
        assertEquals("Economics - SSCI", result.items().get(0).key());
    }

    @Test
    void invalidSortThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "bad", "asc", null));
    }

    @Test
    void invalidDirectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "categoryName", "up", null));
    }

    @Test
    void invalidQueryLengthThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "categoryName", "asc", "x".repeat(101)));
    }
}
