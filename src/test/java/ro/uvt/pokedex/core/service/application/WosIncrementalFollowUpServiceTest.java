package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.MetricType;
import ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosIncrementalFollowUpServiceTest {

    @Mock
    private WosImportEventRepository wosImportEventRepository;
    @Mock
    private WosMetricFactRepository wosMetricFactRepository;
    @Mock
    private WosCategoryFactRepository wosCategoryFactRepository;
    @Mock
    private WosFactBuilderService wosFactBuilderService;
    @Mock
    private WosProjectionBuilderService wosProjectionBuilderService;

    @InjectMocks
    private WosIncrementalFollowUpService service;

    @Test
    void rebuildProjectionsUsesMixedScopeFromStoredLineage() {
        WosImportEvent event = new WosImportEvent();
        event.setSourceType(WosSourceType.GOV_AIS_RIS);
        event.setSourceFile("AIS_2024.xlsx");
        event.setSourceVersion("v2024");

        WosMetricFact metricFact = new WosMetricFact();
        metricFact.setJournalId("jid-1");
        metricFact.setYear(2024);
        metricFact.setMetricType(MetricType.AIS);
        metricFact.setSourceType(WosSourceType.GOV_AIS_RIS);
        metricFact.setSourceFile("AIS_2024.xlsx");
        metricFact.setSourceVersion("v2024");

        WosCategoryFact categoryFact = new WosCategoryFact();
        categoryFact.setJournalId("jid-1");
        categoryFact.setYear(2024);
        categoryFact.setMetricType(MetricType.AIS);
        categoryFact.setCategoryNameCanonical("ECONOMICS");
        categoryFact.setEditionNormalized(EditionNormalized.SCIE);
        categoryFact.setSourceType(WosSourceType.GOV_AIS_RIS);
        categoryFact.setSourceFile("AIS_2024.xlsx");
        categoryFact.setSourceVersion("v2024");

        ImportProcessingResult expected = new ImportProcessingResult(10);
        expected.markProcessed();

        when(wosImportEventRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024"))
                .thenReturn(List.of(event));
        when(wosMetricFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024"))
                .thenReturn(List.of(metricFact));
        when(wosCategoryFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(WosSourceType.GOV_AIS_RIS, "AIS_2024.xlsx", "v2024"))
                .thenReturn(List.of(categoryFact));
        when(wosProjectionBuilderService.rebuildWosProjectionsForScope(eq(Set.of("jid-1")), eq(List.of(metricFact)), eq(List.of(categoryFact))))
                .thenReturn(expected);

        ImportProcessingResult result = service.rebuildProjections(WosUploadSourceType.GOVERNMENT_EXCEL, "AIS_2024.xlsx", "v2024");

        assertSame(expected, result);
        verify(wosProjectionBuilderService).rebuildWosProjectionsForScope(Set.of("jid-1"), List.of(metricFact), List.of(categoryFact));
    }
}
