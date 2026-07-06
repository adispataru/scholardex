package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.OrgUnitReportRefreshEventRepository;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.GroupPublicationAggregator;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Thin orchestrator for group-level reporting flows. The individual-report roll-up shares the
 * org-unit path (roster → latest persisted user runs → shared view model); publication views
 * stay with {@link GroupPublicationAggregator}.
 */
@Service
@RequiredArgsConstructor
public class GroupReportFacade {

    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final OrgUnitReportRefreshEventRepository orgUnitReportRefreshEventRepository;
    private final GroupPublicationAggregator groupPublicationAggregator;

    public Optional<GroupPublicationsViewModel> buildGroupPublicationsView(String groupId) {
        return groupPublicationAggregator.buildView(groupId);
    }

    public Optional<OrgUnitReportViewModel> buildGroupIndividualReportView(String groupId, String reportId,
                                                                           Instant compareTo) {
        Optional<Group> groupOpt = groupRepository.findById(groupId);
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (groupOpt.isEmpty() || reportOpt.isEmpty()) return Optional.empty();
        Group group = groupOpt.get();
        IndividualReport report = reportOpt.get();

        List<OrgUnitRosterService.RosterMember> members = orgUnitRosterService.groupRoster(groupId);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(members, report, compareTo);
        List<OrgUnitReportViewModel.CompareOption> compareOptions = orgUnitRunRollupService.toCompareOptions(
                orgUnitReportRefreshEventRepository.findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(
                        OrgUnitReportRefreshEvent.UnitType.GROUP, groupId, reportId));
        return Optional.of(orgUnitRunRollupService.toViewModel(
                group.getId(), group.getName(), report, rollup, compareOptions));
    }
}
