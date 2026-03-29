package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.controller.dto.WosCategoryListItemResponse;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosCategoryQueryServiceTest {

    @Mock
    private PostgresWosCategoryReadPort postgresWosCategoryReadPort;

    private WosCategoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new WosCategoryQueryService(postgresWosCategoryReadPort);
    }

    @Test
    void searchDelegatesToPostgresPort() {
        WosCategoryPageResponse expected = new WosCategoryPageResponse(
                List.of(new WosCategoryListItemResponse("Computer Science - SCIE", "Computer Science", "SCIE", 2, 2024)),
                0, 1, 2, 2);
        when(postgresWosCategoryReadPort.search(anyInt(), anyInt(), anyString(), anyString(), isNull()))
                .thenReturn(expected);

        WosCategoryPageResponse result = service.search(0, 1, "journalCount", "desc", null);

        assertEquals(0, result.page());
        assertEquals(1, result.size());
        assertEquals(2, result.totalItems());
        assertEquals(2, result.totalPages());
        assertEquals(1, result.items().size());
        assertEquals("Computer Science - SCIE", result.items().get(0).key());
        assertEquals(2, result.items().get(0).journalCount());
        verify(postgresWosCategoryReadPort).search(0, 1, "journalCount", "desc", null);
    }

    @Test
    void searchWithFilterDelegatesToPostgresPort() {
        WosCategoryPageResponse expected = new WosCategoryPageResponse(
                List.of(new WosCategoryListItemResponse("Economics - SSCI", "Economics", "SSCI", 2, 2023)),
                0, 25, 1, 1);
        when(postgresWosCategoryReadPort.search(anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(expected);

        WosCategoryPageResponse result = service.search(0, 25, "categoryName", "asc", "ssci");

        assertEquals(1, result.items().size());
        assertEquals("Economics - SSCI", result.items().get(0).key());
    }
}
