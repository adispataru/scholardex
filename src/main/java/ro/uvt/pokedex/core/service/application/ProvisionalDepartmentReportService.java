package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.ProvisionalAuthorMatch;
import ro.uvt.pokedex.core.service.application.ProvisionalAuthorResolutionService.Status;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * H77 S3 — admin provisional scoring for a whole department. Resolves the department roster to canonical authors
 * (by name / declared source ids), then scores each RESOLVED person from that DECLARED identity and persists a
 * provisional run. AMBIGUOUS/UNRESOLVED people are reported, flagged, and left unscored — never silently dropped.
 *
 * <p>Read-only and admin-only: nothing here writes a researcher's confirm/reject decisions; every produced run is
 * marked {@code provisional} so it can never be mistaken for a validated self-scored run.
 */
@Service
@RequiredArgsConstructor
public class ProvisionalDepartmentReportService {

    private final ProvisionalAuthorResolutionService resolutionService;
    private final UserIndividualReportRunService runService;

    /** Resolve + score every roster person of {@code departmentId} against {@code reportDefinitionId}. */
    public DepartmentProvisionalReport run(String departmentId, String reportDefinitionId, String adminEmail) {
        List<ProvisionalAuthorMatch> matches = resolutionService.resolveDepartment(departmentId);
        List<PersonResult> results = new ArrayList<>(matches.size());
        for (ProvisionalAuthorMatch match : matches) {
            if (match.status() == Status.RESOLVED && match.canonicalAuthorId() != null) {
                Optional<IndividualReportRunDto> run = runService.buildAndSaveProvisionalRun(
                        match.email(), reportDefinitionId, List.of(match.canonicalAuthorId()), adminEmail);
                results.add(PersonResult.scored(match, run.orElse(null)));
            } else {
                results.add(PersonResult.unscored(match));
            }
        }
        return new DepartmentProvisionalReport(departmentId, reportDefinitionId, adminEmail, results);
    }

    /** The full department result table. */
    public record DepartmentProvisionalReport(String departmentId,
                                              String reportDefinitionId,
                                              String adminEmail,
                                              List<PersonResult> results) {

        /** Count of roster people actually scored (RESOLVED with a persisted run). */
        public long scoredCount() {
            return results.stream().filter(r -> r.status() == Status.RESOLVED && r.runId() != null).count();
        }
    }

    /** One roster person's outcome: scored (RESOLVED) or flagged-unscored (AMBIGUOUS/UNRESOLVED). */
    public record PersonResult(String email,
                               String firstName,
                               String lastName,
                               Status status,
                               String canonicalAuthorId,
                               List<String> scopusAuthorIds,
                               String runId,
                               Map<String, Double> indicatorScores,
                               Map<Integer, Double> criteriaScores,
                               double totalScore) {

        static PersonResult scored(ProvisionalAuthorMatch match, IndividualReportRunDto run) {
            Map<String, Double> indicatorScores = run == null ? Map.of() : run.indicatorScoresByIndicatorId();
            Map<Integer, Double> criteriaScores = run == null ? Map.of() : run.criteriaScores();
            double total = indicatorScores.values().stream().filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sum();
            return new PersonResult(
                    match.email(), match.firstName(), match.lastName(), match.status(),
                    match.canonicalAuthorId(), match.scopusAuthorIds(),
                    run == null ? null : run.runId(), indicatorScores, criteriaScores, total);
        }

        static PersonResult unscored(ProvisionalAuthorMatch match) {
            return new PersonResult(
                    match.email(), match.firstName(), match.lastName(), match.status(),
                    match.canonicalAuthorId(), match.scopusAuthorIds(),
                    null, Map.of(), Map.of(), 0.0);
        }
    }
}
