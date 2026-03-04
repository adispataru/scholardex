package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndividualReportsManagementFacadeTest {

    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private InstitutionRepository institutionRepository;

    @InjectMocks
    private IndividualReportsManagementFacade facade;

    @Test
    void listIndicatorsSortedByNameReturnsSortedOrder() {
        Indicator b = new Indicator();
        b.setName("B");
        Indicator a = new Indicator();
        a.setName("A");
        when(indicatorRepository.findAll()).thenReturn(List.of(b, a));

        List<Indicator> sorted = facade.listIndicatorsSortedByName();
        assertEquals("A", sorted.get(0).getName());
    }

    @Test
    void duplicateReportCreatesCopy() {
        IndividualReport report = new IndividualReport();
        report.setId("r1");
        report.setTitle("Report");
        IndividualReport saved = new IndividualReport();
        saved.setId("r2");
        when(individualReportRepository.findById("r1")).thenReturn(Optional.of(report));
        when(individualReportRepository.save(any(IndividualReport.class))).thenReturn(saved);

        Optional<IndividualReport> duplicated = facade.duplicateIndividualReport("r1");
        assertTrue(duplicated.isPresent());
        verify(individualReportRepository).save(any(IndividualReport.class));
    }
}
