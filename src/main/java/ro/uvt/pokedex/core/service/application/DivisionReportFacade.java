package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentAffiliationRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;
import ro.uvt.pokedex.core.service.application.reporting.IndividualReportComputer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Division (Faculty / Institute / Service)-level roll-up. Aggregates researchers across
 * every department directly under the division. Joint-appointed researchers under multiple
 * departments are de-duplicated; the view model records which department contributed each
 * researcher for grouping in the UI.
 */
@Service
@RequiredArgsConstructor
public class DivisionReportFacade {

    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentAffiliationRepository departmentAffiliationRepository;
    private final UserRepository userRepository;
    private final IndividualReportRepository individualReportRepository;
    private final IndividualReportComputer individualReportComputer;
    private final ReportingLookupMemoization reportingLookupMemoization;
    private final ReportVisibilityService reportVisibilityService;

    /**
     * @deprecated use {@link #listReportsVisibleForDivision(String)} so the listing respects
     * the division-head selection.
     */
    @Deprecated
    public List<IndividualReport> listAvailableReports() {
        return individualReportRepository.findAll();
    }

    public List<IndividualReport> listReportsVisibleForDivision(String divisionId) {
        return reportVisibilityService.listVisibleReportsForDivision(divisionId);
    }

    public Optional<OrgDivision> findDivision(String divisionId) {
        return orgDivisionRepository.findById(divisionId);
    }

    public Optional<OrgUnitReportViewModel> buildView(String divisionId, String reportId) {
        Optional<OrgDivision> divOpt = orgDivisionRepository.findById(divisionId);
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (divOpt.isEmpty() || reportOpt.isEmpty()) return Optional.empty();
        OrgDivision division = divOpt.get();
        IndividualReport report = reportOpt.get();

        // Departments under the division → user ids + which department each maps to.
        List<Department> departments = departmentRepository.findByDivisionId(divisionId);
        Map<String, String> labelByUserId = new LinkedHashMap<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (Department d : departments) {
            for (DepartmentAffiliation a : departmentAffiliationRepository
                    .findByDepartmentIdAndValidToIsNull(d.getId())) {
                if (userIds.add(a.getUserId())) {
                    labelByUserId.put(a.getUserId(), d.getName() == null ? d.getId() : d.getName());
                } else {
                    // Joint appointment — append the secondary department.
                    String existing = labelByUserId.get(a.getUserId());
                    String addName = d.getName() == null ? d.getId() : d.getName();
                    if (existing != null && !existing.contains(addName)) {
                        labelByUserId.put(a.getUserId(), existing + " + " + addName);
                    }
                }
            }
        }

        List<User> researchers = new ArrayList<>(userRepository.findAllById(userIds));
        researchers.removeIf(u -> u.getResearcherProfile() == null);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));

        IndividualReportComputer.Computation computation = reportingLookupMemoization.withRefreshScope(
                () -> individualReportComputer.compute(researchers, report));

        Map<String, Map<Integer, Double>> scoresByEmail = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (IndividualReportComputer.ResearcherScoreEntry entry : computation.researcherScores()) {
            seenIds.add(entry.userId());
        }
        for (User u : researchers) {
            for (IndividualReportComputer.ResearcherScoreEntry entry : computation.researcherScores()) {
                if (entry.userId() != null && entry.userId().equals(u.getEmail())) {
                    scoresByEmail.put(u.getEmail(), entry.criterionScores());
                    break;
                }
            }
        }

        return Optional.of(new OrgUnitReportViewModel(
                division.getId(),
                division.getName(),
                report,
                researchers,
                scoresByEmail,
                computation.criteriaThresholds(),
                computation.errors(),
                labelByUserId));
    }
}
