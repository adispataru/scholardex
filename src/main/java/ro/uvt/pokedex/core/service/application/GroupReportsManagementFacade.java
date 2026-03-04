package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.GroupReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.repository.reporting.GroupReportRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GroupReportsManagementFacade {

    private final GroupReportRepository groupReportRepository;
    private final IndicatorRepository indicatorRepository;
    private final GroupRepository groupRepository;

    public List<GroupReport> listGroupReports() {
        return groupReportRepository.findAll();
    }

    public List<Indicator> listIndicators() {
        return indicatorRepository.findAll();
    }

    public List<ro.uvt.pokedex.core.model.reporting.Group> listGroups() {
        return groupRepository.findAll();
    }

    public GroupReport saveGroupReport(GroupReport groupReport) {
        return groupReportRepository.save(groupReport);
    }

    public Optional<GroupReport> findGroupReport(String id) {
        return groupReportRepository.findById(id);
    }

    public void deleteGroupReport(String id) {
        groupReportRepository.deleteById(id);
    }
}
