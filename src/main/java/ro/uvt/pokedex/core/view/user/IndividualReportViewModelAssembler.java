package ro.uvt.pokedex.core.view.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ReportTransferFacade;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Populates the shared {@code user/individual-report-view} model attributes from a resolved run.
 *
 * <p>Used by both the researcher's own evaluation page ({@code EvaluationWorkspaceController}) and
 * the delegated admin/supervisor read-only view ({@code ResearcherReportController}) so the two
 * render identically — fidelity ("what the user sees") comes from sharing this assembly plus the
 * template. Callers resolve the run and report list themselves (self vs delegated differ only in
 * run resolution and access control); this method is pure assembly: it reads no principal and
 * persists nothing.
 */
@Component
@RequiredArgsConstructor
public class IndividualReportViewModelAssembler {

    /** Static mapper for the page-level thresholds JSON — not a bean, so no @WebMvcTest mock ripple. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final UserIndividualReportRunService userIndividualReportRunService;
    private final ReportTransferFacade reportTransferFacade;

    public void populate(Model model,
                         User researcher,
                         IndividualReport report,
                         IndividualReportRunDto run,
                         List<IndividualReport> allReports) {
        // Build indicatorScores map keyed by indicator ID string (safe for Thymeleaf map lookup;
        // Indicator uses @Data which includes @DBRef fields in hashCode, making it unreliable as a Map key).
        Map<String, Double> indicatorScores = new HashMap<>();
        if (report.getIndicators() != null) {
            for (Indicator indicator : report.getIndicators()) {
                if (indicator.getId() != null) {
                    double score = run.indicatorScoresByIndicatorId().getOrDefault(indicator.getId(), 0.0);
                    indicatorScores.put(indicator.getId(), score);
                }
            }
        }

        String researcherPosition = Optional.ofNullable(researcher.getResearcherProfile())
                .map(p -> p.getPosition())
                .map(Enum::name)
                .orElse("");

        List<PriorRunView> priorRuns = userIndividualReportRunService
                .listRuns(researcher.getEmail(), report.getId())
                .stream()
                .map(r -> new PriorRunView(r.getId(),
                        r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                        r.getStatus().name(),
                        r.getCriteriaScores() != null ? r.getCriteriaScores() : Map.of()))
                .toList();

        Map<Integer, Double> criterionScores = run.criteriaScores() != null ? run.criteriaScores() : Map.of();

        // Criteria-met count: only count criteria that have a threshold for the researcher's position.
        // Criteria with no threshold for the current position are not applicable and excluded from both
        // numerator and denominator.
        int criteriaMet = 0;
        int criteriaTotal = 0;
        if (report.getCriteria() != null && !researcherPosition.isEmpty()) {
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion crit = report.getCriteria().get(i);
                if (crit.getThresholds() == null) continue;
                final String pos = researcherPosition;
                boolean hasThresholdForPosition = crit.getThresholds().stream()
                        .anyMatch(t -> t.getPosition() != null && t.getPosition().name().equals(pos));
                if (!hasThresholdForPosition) continue;
                criteriaTotal++;
                double cScore = criterionScores.getOrDefault(i, 0.0);
                boolean met = crit.getThresholds().stream()
                        .filter(t -> t.getPosition() != null && t.getPosition().name().equals(pos))
                        .anyMatch(t -> cScore >= t.getValue());
                if (met) criteriaMet++;
            }
        }

        // Total score: sum of criterion scores for criteria flagged contributesToTotal.
        double totalScore = 0.0;
        boolean anyContributesToTotal = false;
        if (report.getCriteria() != null) {
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion crit = report.getCriteria().get(i);
                if (crit.isContributesToTotal()) {
                    anyContributesToTotal = true;
                    totalScore += criterionScores.getOrDefault(i, 0.0);
                }
            }
        }

        model.addAttribute("report", report);
        model.addAttribute("allReports", allReports);
        model.addAttribute("indicatorScores", indicatorScores);
        model.addAttribute("criterionScores", criterionScores);
        model.addAttribute("criteriaMet", criteriaMet);
        model.addAttribute("criteriaTotal", criteriaTotal);
        model.addAttribute("totalScore", anyContributesToTotal ? totalScore : null);
        model.addAttribute("researcherPosition", researcherPosition);
        model.addAttribute("runMetaId", run.runId());
        model.addAttribute("runMetaCreatedAt", run.createdAt());
        model.addAttribute("runMetaSource", run.source());
        // Provenance: who last triggered this run. Differs from the researcher only on a delegated
        // (admin/supervisor) refresh; the template surfaces it when it is not the researcher.
        model.addAttribute("runMetaTriggeredBy", run.triggeredByEmail());
        model.addAttribute("priorRuns", priorRuns);
        model.addAttribute("user", researcher);
        model.addAttribute("thresholdsJson", thresholdsJson(report, criterionScores));

        // Export format the report type drives (XLSX → "Excel", DOCX → "Word"), so the export
        // action labels/links correctly instead of hardcoding xlsx.
        ReportFormat exportFormat = resolveExportFormat(report);
        model.addAttribute("exportFormat", exportFormat.name());
        model.addAttribute("exportFormatLabel", exportFormat == ReportFormat.DOCX ? "Word" : "Excel");
    }

    /**
     * The page-level criteria/thresholds JSON (script tag {@code #eval-thresholds-data}) that the
     * workbench JS renders the rail, criteria-met count, and position selector from. Shape:
     * {@code [{index, name, score, contributesToTotal, thresholds:[{position, value}]}]}.
     */
    private String thresholdsJson(IndividualReport report, Map<Integer, Double> criterionScores) {
        List<Map<String, Object>> criteria = new java.util.ArrayList<>();
        List<AbstractReport.Criterion> reportCriteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < reportCriteria.size(); i++) {
            AbstractReport.Criterion crit = reportCriteria.get(i);
            List<Map<String, Object>> thresholds = new java.util.ArrayList<>();
            if (crit.getThresholds() != null) {
                for (AbstractReport.Threshold t : crit.getThresholds()) {
                    if (t.getPosition() != null && t.getValue() != null) {
                        thresholds.add(Map.of("position", t.getPosition().name(), "value", t.getValue()));
                    }
                }
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("index", i);
            entry.put("name", crit.getName() != null && !crit.getName().isBlank()
                    ? crit.getName().trim() : "Criterion " + (i + 1));
            entry.put("score", criterionScores.getOrDefault(i, 0.0));
            entry.put("contributesToTotal", crit.isContributesToTotal());
            entry.put("thresholds", thresholds);
            criteria.add(entry);
        }
        try {
            // '<' is escaped so a hostile criterion name cannot close the inline <script> tag.
            return JSON.writeValueAsString(criteria).replace("<", "\\u003c");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    private ReportFormat resolveExportFormat(IndividualReport report) {
        return reportTransferFacade.preferredExportFormat(report.getReportTypeKey());
    }
}
