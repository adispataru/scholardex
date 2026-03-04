package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.reporting.GroupReport;
import ro.uvt.pokedex.core.repository.reporting.GroupReportRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupReportsManagementFacadeTest {

    @Mock
    private GroupReportRepository groupReportRepository;
    @Mock
    private IndicatorRepository indicatorRepository;
    @Mock
    private GroupRepository groupRepository;

    @InjectMocks
    private GroupReportsManagementFacade facade;

    @Test
    void listViewSourcesAreDelegated() {
        when(groupReportRepository.findAll()).thenReturn(List.of(new GroupReport()));
        assertEquals(1, facade.listGroupReports().size());
        facade.listIndicators();
        facade.listGroups();
        verify(indicatorRepository).findAll();
        verify(groupRepository).findAll();
    }

    @Test
    void deleteDelegatesToRepository() {
        facade.deleteGroupReport("gr1");
        verify(groupReportRepository).deleteById("gr1");
    }
}
