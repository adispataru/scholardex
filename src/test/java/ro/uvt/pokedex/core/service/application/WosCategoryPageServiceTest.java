package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosCategoryPageServiceTest {

    @Mock
    private PostgresWosCategoryReadPort postgresWosCategoryReadPort;

    private WosCategoryPageService service;

    @BeforeEach
    void setUp() {
        service = new WosCategoryPageService(postgresWosCategoryReadPort);
    }

    @Test
    void findCategoryDelegatesToPostgresPort() {
        WosCategoryDetailViewModel viewModel = new WosCategoryDetailViewModel(
                "Computer Science - SCIE", "Computer Science", "SCIE", 2, 2024, false, List.of(),
                new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics(
                        1.2, 5.0, 1.0, 2024, 2.5, 30.0, 2.0, 2019,
                        new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics.QuartileSplit(1, 1, 0, 0),
                        List.of())
        );
        when(postgresWosCategoryReadPort.findCategoryPage("Computer Science", ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized.SCIE))
                .thenReturn(Optional.of(viewModel));

        Optional<WosCategoryDetailViewModel> detail = service.findCategory("Computer Science - SCIE");

        assertTrue(detail.isPresent());
        assertEquals("Computer Science", detail.get().categoryName());
        assertEquals(2, detail.get().journalCount());
    }

    @Test
    void findCategoryRejectsUnsupportedKeys() {
        assertTrue(service.findCategory("invalid").isEmpty());
        assertTrue(service.findCategory("Computer Science - AHCI").isEmpty());
    }
}
