package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.service.application.GroupMembershipService;
import ro.uvt.pokedex.core.service.application.model.GroupIndividualReportViewModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates a {@link GroupIndividualReportRun} into the Thymeleaf-ready attribute map.
 * Researcher scores are exposed as a {@code Map<email, Map<criterionIdx, score>>} — the
 * legacy {@code mongoSafeReportKey} indirection (and the parallel
 * {@code researcherScoreKeyByEmail} attribute) are gone.
 */
@Component
@RequiredArgsConstructor
public class GroupReportViewModelAssembler {

    private final UserRepository userRepository;
    private final GroupMembershipService groupMembershipService;

    public GroupIndividualReportViewModel toViewModel(Group group, IndividualReport report,
                                                      GroupIndividualReportRun run) {
        List<User> researchers = loadResearchers(group);
        researchers.sort(Comparator.comparing(u -> u.getResearcherProfile().getName()));

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("report", report);
        attrs.put("group", group);
        attrs.put("researchers", researchers);
        attrs.put("researcherScores", indexScoresByUserId(run, researchers));
        attrs.put("criteriaThresholds",
                run.getCriteriaThresholds() == null ? Map.of() : run.getCriteriaThresholds());
        attrs.put("runCreatedAt", run.getCreatedAt());
        attrs.put("runStatus", run.getStatus());
        attrs.put("runBuildErrors", run.getBuildErrors() == null ? List.of() : run.getBuildErrors());
        return new GroupIndividualReportViewModel(null, attrs);
    }

    private Map<String, Map<Integer, Double>> indexScoresByUserId(
            GroupIndividualReportRun run, List<User> researchers) {
        Map<String, Map<Integer, Double>> byUserId = new HashMap<>();
        if (run.getResearcherScores() != null) {
            for (GroupIndividualReportRun.ResearcherScore s : run.getResearcherScores()) {
                if (s.getUserId() != null) {
                    byUserId.put(s.getUserId(), s.getCriterionScores());
                }
            }
        }
        Map<String, Map<Integer, Double>> ordered = new LinkedHashMap<>();
        for (User researcher : researchers) {
            String email = researcher.getEmail();
            if (email == null || email.isBlank()) continue;
            Map<Integer, Double> scores = byUserId.get(email);
            if (scores != null) ordered.put(email, scores);
        }
        return ordered;
    }

    private List<User> loadResearchers(Group group) {
        List<String> memberIds = groupMembershipService.listCurrentMemberUserIds(group.getId());
        if (memberIds.isEmpty()) return new ArrayList<>();
        return userRepository.findAllById(memberIds).stream()
                .filter(u -> u.getResearcherProfile() != null)
                .collect(Collectors.toList());
    }
}
