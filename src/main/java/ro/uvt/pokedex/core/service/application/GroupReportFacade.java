package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.reporting.GroupIndividualReportRunRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.GroupPublicationsViewModel;
import ro.uvt.pokedex.core.service.application.reporting.GroupPublicationAggregator;
import ro.uvt.pokedex.core.service.application.reporting.GroupReportRunner;
import ro.uvt.pokedex.core.service.application.reporting.GroupReportViewModelAssembler;
import ro.uvt.pokedex.core.service.application.reporting.ReportRunTelemetry;

import java.util.Map;
import java.util.Optional;

/**
 * Thin orchestrator for group-level reporting flows. Real work lives in
 * {@link GroupPublicationAggregator}, {@link GroupReportRunner},
 * {@link GroupReportViewModelAssembler}, and {@link ReportRunTelemetry}.
 */
@Service
@RequiredArgsConstructor
public class GroupReportFacade {

    private final GroupRepository groupRepository;
    private final IndividualReportRepository individualReportRepository;
    private final GroupIndividualReportRunRepository groupIndividualReportRunRepository;
    private final GroupMembershipService groupMembershipService;

    private final GroupPublicationAggregator groupPublicationAggregator;
    private final GroupReportRunner groupReportRunner;
    private final GroupReportViewModelAssembler viewModelAssembler;
    private final ReportRunTelemetry reportRunTelemetry;

    public Optional<GroupPublicationsViewModel> buildGroupPublicationsView(String groupId) {
        return groupPublicationAggregator.buildView(groupId);
    }

    public GroupIndividualReportViewModel buildGroupIndividualReportView(String groupId, String reportId) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return redirectToGroupList();

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) return redirectToGroupList();
        IndividualReport report = reportOpt.get();

        GroupIndividualReportRun run = groupIndividualReportRunRepository
                .findTopByGroupIdAndReportDefinitionIdOrderByCreatedAtDesc(groupId, reportId)
                .orElseGet(() -> groupReportRunner.computeAndPersist(group, report).run());

        return viewModelAssembler.toViewModel(group, report, run);
    }

    public GroupIndividualReportViewModel refreshGroupIndividualReportView(String groupId, String reportId) {
        long refreshStart = System.nanoTime();
        long lookupStart = System.nanoTime();
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return redirectToGroupList();

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) return redirectToGroupList();
        long lookupMs = nanosToMs(System.nanoTime() - lookupStart);

        long computeStart = System.nanoTime();
        GroupReportRunner.ComputeResult computeResult = groupReportRunner.compute(group, reportOpt.get());
        long computeMs = nanosToMs(System.nanoTime() - computeStart);

        long saveStart = System.nanoTime();
        GroupIndividualReportRun run = groupReportRunner.save(computeResult.run());
        long saveMs = nanosToMs(System.nanoTime() - saveStart);

        long totalMs = nanosToMs(System.nanoTime() - refreshStart);
        int memberCount = groupMembershipService.listCurrentMemberUserIds(group.getId()).size();
        reportRunTelemetry.logRefresh(groupId, reportId, memberCount, run, computeResult.timings(),
                lookupMs, computeMs, saveMs, totalMs);

        return viewModelAssembler.toViewModel(group, reportOpt.get(), run);
    }

    private GroupIndividualReportViewModel redirectToGroupList() {
        return new GroupIndividualReportViewModel("redirect:/admin/groups", Map.of());
    }

    private long nanosToMs(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }
}
