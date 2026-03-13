package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosCategoryPageServiceTest {

    @Mock
    private WosCategoryFactRepository wosCategoryFactRepository;
    @Mock
    private WosRankingViewRepository wosRankingViewRepository;

    private WosCategoryPageService service;

    @BeforeEach
    void setUp() {
        service = new WosCategoryPageService(wosCategoryFactRepository, wosRankingViewRepository);
    }

    @Test
    void findCategoryBuildsJournalRowsFromCategoryFactsAndRankingViews() {
        when(wosCategoryFactRepository.findAllByCategoryNameCanonicalAndEditionNormalized("Computer Science", EditionNormalized.SCIE))
                .thenReturn(List.of(
                        fact("j1", "Computer Science", EditionNormalized.SCIE, MetricType.AIS, 2024, "Q1"),
                        fact("j1", "Computer Science", EditionNormalized.SCIE, MetricType.RIS, 2024, "Q2"),
                        fact("j2", "Computer Science", EditionNormalized.SCIE, MetricType.IF, 2023, "Q3")
                ));
        WosRankingView first = new WosRankingView();
        first.setId("j1");
        first.setName("Journal One");
        first.setIssn("1234-5678");
        first.setEIssn("8765-4321");
        WosRankingView second = new WosRankingView();
        second.setId("j2");
        second.setName("Journal Two");
        when(wosRankingViewRepository.findAllById(eq(Set.of("j1", "j2")))).thenReturn(List.of(first, second));

        Optional<WosCategoryDetailViewModel> detail = service.findCategory("Computer Science - SCIE");

        assertTrue(detail.isPresent());
        assertEquals("Computer Science", detail.get().categoryName());
        assertEquals(2, detail.get().journalCount());
        assertEquals("Journal One", detail.get().journals().get(0).journalName());
        assertEquals("Q1", detail.get().journals().get(0).latestAisQuarter());
        assertEquals("Q2", detail.get().journals().get(0).latestRisQuarter());
    }

    @Test
    void findCategoryRejectsUnsupportedKeys() {
        assertTrue(service.findCategory("invalid").isEmpty());
        assertTrue(service.findCategory("Computer Science - AHCI").isEmpty());
    }

    private WosCategoryFact fact(String journalId, String categoryName, EditionNormalized edition, MetricType metricType, Integer year, String quarter) {
        WosCategoryFact fact = new WosCategoryFact();
        fact.setJournalId(journalId);
        fact.setCategoryNameCanonical(categoryName);
        fact.setEditionNormalized(edition);
        fact.setMetricType(metricType);
        fact.setYear(year);
        fact.setQuarter(quarter);
        return fact;
    }
}
