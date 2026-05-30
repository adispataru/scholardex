package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.IndividualReportComputer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Department-level roll-up of an {@link IndividualReport}. Researchers are resolved through
 * the current (validTo == null) {@link DepartmentAffiliation} entries, so joint appointments
 * count toward every department they're currently affiliated with — which is what the
 * "every faculty/department report" semantics demand.
 */
@Service
@RequiredArgsConstructor
public class DepartmentReportFacade {

    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final UserRepository userRepository;
    private final IndividualReportRepository individualReportRepository;
    private final IndividualReportComputer individualReportComputer;
    private final ReportingLookupMemoization reportingLookupMemoization;
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

        List<User> researchers = loadResearchers(departmentId);

        IndividualReportComputer.Computation computation = reportingLookupMemoization.withRefreshScope(
                () -> individualReportComputer.compute(researchers, report));

        Map<String, Map<Integer, Double>> scoresByEmail = new LinkedHashMap<>();
        for (User u : researchers) {
            for (IndividualReportComputer.ResearcherScoreEntry entry : computation.researcherScores()) {
                if (u.getEmail() != null && u.getEmail().equals(entry.userId())) {
                    scoresByEmail.put(u.getEmail(), entry.criterionScores());
                    break;
                }
            }
        }

        return Optional.of(new OrgUnitReportViewModel(
                department.getId(),
                department.getName(),
                report,
                researchers,
                scoresByEmail,
                computation.criteriaThresholds(),
                computation.errors(),
                Map.of()));
    }

    /** Researchers currently affiliated with the department, sorted by display name. */
    List<User> loadResearchers(String departmentId) {
        List<DepartmentAffiliation> affiliations =
                departmentAffiliationRepository.findByDepartmentIdAndValidToIsNull(departmentId);
        if (affiliations.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> userIds = new java.util.LinkedHashSet<>();
        for (DepartmentAffiliation a : affiliations) userIds.add(a.getUserId());
        java.util.List<User> researchers = new java.util.ArrayList<>(userRepository.findAllById(userIds));
        researchers.removeIf(u -> u.getResearcherProfile() == null);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));
        return researchers;
    }
}
