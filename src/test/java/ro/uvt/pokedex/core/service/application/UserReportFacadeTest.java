package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserReportFacadeTest {

    @Mock
    private UserService userService;
    @Mock
    private ResearcherService researcherService;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private IndividualReportRepository individualReportRepository;
    @Mock
    private ActivityInstanceRepository activityInstanceRepository;
    @Mock
    private ScopusAuthorRepository scopusAuthorRepository;
    @Mock
    private ScopusCitationRepository scopusCitationRepository;
    @Mock
    private ScopusPublicationRepository scopusPublicationRepository;
    @Mock
    private ScopusForumRepository scopusForumRepository;
    @Mock
    private ActivityReportingService activityReportingService;
    @Mock
    private ScientificProductionService scientificProductionService;

    @InjectMocks
    private UserReportFacade facade;

    @Test
    void buildIndicatorsViewReturnsRepositoryValues() {
        Indicator i = new Indicator();
        i.setName("I1");
        when(indicatorRepository.findAll()).thenReturn(List.of(i));

        var vm = facade.buildIndicatorsView("user@uvt.ro");

        assertEquals(1, vm.indicators().size());
        assertEquals("I1", vm.indicators().getFirst().getName());
    }

    @Test
    void buildIndividualReportsListViewReturnsRepositoryValues() {
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");
        when(individualReportRepository.findAll()).thenReturn(List.of(report));

        var vm = facade.buildIndividualReportsListView("user@uvt.ro");

        assertEquals(1, vm.individualReports().size());
        assertEquals("R1", vm.individualReports().getFirst().getTitle());
    }
}
