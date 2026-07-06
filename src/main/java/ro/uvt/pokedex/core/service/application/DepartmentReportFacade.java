package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.OrgUnitRunRollupService;

import java.util.List;
import java.util.Optional;

/**
 * Department-level roll-up of an {@link IndividualReport}. Researchers are resolved through
 * the current (validTo == null) {@link DepartmentAffiliation} entries, so joint appointments
 * count toward every department they're currently affiliated with — which is what the
 * "every faculty/department report" semantics demand. Scores come from each member's latest
 * persisted report run (what the researcher sees in their workspace) — the view never recomputes.
 */
@Service
@RequiredArgsConstructor
public class DepartmentReportFacade {

    private final DepartmentRepository departmentRepository;
    private final IndividualReportRepository individualReportRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final OrgUnitRunRollupService orgUnitRunRollupService;
    private final ReportVisibilityService reportVisibilityService;

    /**
     * @deprecated use {@link #listReportsVisibleForDepartment(String)} so the listing respects
     * the division-head selection and department-head hide overrides.
     */
    @Deprecated
    public List<IndividualReport> listAvailableReports() {
        return individualReportRepository.findAll();
    }

    public List<IndividualReport> listReportsVisibleForDepartment(String departmentId) {
        return reportVisibilityService.listVisibleReportsForDepartment(departmentId);
    }

    public Optional<Department> findDepartment(String departmentId) {
        return departmentRepository.findById(departmentId);
    }

    public Optional<OrgUnitReportViewModel> buildView(String departmentId, String reportId) {
        Optional<Department> deptOpt = departmentRepository.findById(departmentId);
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (deptOpt.isEmpty() || reportOpt.isEmpty()) return Optional.empty();
        Department department = deptOpt.get();
        IndividualReport report = reportOpt.get();

        List<OrgUnitRosterService.RosterMember> members = orgUnitRosterService.departmentRoster(departmentId);
        OrgUnitRunRollupService.OrgUnitRunRollup rollup = orgUnitRunRollupService.rollup(members, report);
        return Optional.of(orgUnitRunRollupService.toViewModel(
                department.getId(), department.getName(), report, rollup));
    }
}
