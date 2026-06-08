package ro.uvt.pokedex.core.view.user;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.evaluation.EvaluationSnapshot;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.EvaluationSnapshotRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndividualReportRunRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.UserIndividualReportRunService;
import ro.uvt.pokedex.core.service.application.UserIndicatorResultService;
import ro.uvt.pokedex.core.service.application.UserReportFacade;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/user/evaluation")
@RequiredArgsConstructor
public class EvaluationWorkspaceController {

    private static final int SNAPSHOT_CAP = 50;

    private final UserService userService;
    private final UserReportFacade userReportFacade;
    private final UserIndividualReportRunService userIndividualReportRunService;
    private final UserIndicatorResultService userIndicatorResultService;
    private final UserIndividualReportRunRepository userIndividualReportRunRepository;
    private final EvaluationSnapshotRepository evaluationSnapshotRepository;
    private final ReportExportFacade reportExportFacade;
    private final ro.uvt.pokedex.core.service.reporting.transfer.ReportImportVerificationFacade reportImportVerificationFacade;
    private final ro.uvt.pokedex.core.service.application.UserActivityInstanceFacade userActivityInstanceFacade;

    // ── MVC: main evaluation page ────────────────────────────────────────────

    @GetMapping
    public String showEvaluation(@RequestParam(name = "report", required = false) String reportId,
                                 Model model,
                                 Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        List<IndividualReport> reports = userReportFacade.buildIndividualReportsListView(currentUser.getEmail()).individualReports();
        if (reports.isEmpty()) {
            model.addAttribute("user", currentUser);
            model.addAttribute("noReports", true);
            return "user/individual-report-view";
        }

        String resolvedReportId = (reportId != null && !reportId.isBlank()) ? reportId : reports.get(0).getId();
        Optional<IndividualReport> reportOpt = userReportFacade.findIndividualReportById(resolvedReportId);
        if (reportOpt.isEmpty()) {
            return "redirect:/user/evaluation";
        }
        IndividualReport report = reportOpt.get();
        boolean confirmedPublicationScoringWarning =
                userReportFacade.reportUsesPublicationScoring(report.getId())
                        && !userReportFacade.hasConfirmedPublicationsForScoring(currentUser.getEmail());
        model.addAttribute("confirmedPublicationScoringWarning", confirmedPublicationScoringWarning);

        Optional<IndividualReportRunDto> runOpt = userIndividualReportRunService.getOrCreateLatestRun(
                currentUser.getEmail(), resolvedReportId);
        if (runOpt.isEmpty()) {
            model.addAttribute("user", currentUser);
            model.addAttribute("report", report);
            model.addAttribute("noRun", true);
            return "user/individual-report-view";
        }
        IndividualReportRunDto run = runOpt.get();

        // Build indicatorScores map keyed by indicator ID string (safe for Thymeleaf map lookup;
        // Indicator uses @Data which includes @DBRef fields in hashCode, making it unreliable as a Map key)
        Map<String, Double> indicatorScores = new HashMap<>();
        if (report.getIndicators() != null) {
            for (Indicator indicator : report.getIndicators()) {
                if (indicator.getId() != null) {
                    double score = run.indicatorScoresByIndicatorId().getOrDefault(indicator.getId(), 0.0);
                    indicatorScores.put(indicator.getId(), score);
                }
            }
        }

        String researcherPosition = Optional.ofNullable(currentUser.getResearcherProfile())
                .map(p -> p.getPosition())
                .map(Enum::name)
                .orElse("");

        // Collect prior runs for compare picker
        List<RunSummary> priorRuns = userIndividualReportRunRepository
                .findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(currentUser.getEmail(), resolvedReportId)
                .stream()
                .map(r -> new RunSummary(r.getId(), r.getCreatedAt() != null ? r.getCreatedAt().toString() : null, r.getStatus().name(), null))
                .toList();

        Map<Integer, Double> criterionScores = run.criteriaScores() != null ? run.criteriaScores() : Map.of();

        // Criteria-met count: only count criteria that have a threshold for the researcher's position.
        // Criteria with no threshold for the current position are not applicable and excluded from both
        // the numerator and denominator (avoids penalising e.g. a lecturer for criteria that only
        // apply to associate/full professors).
        int criteriaMet = 0;
        int criteriaTotal = 0;
        if (report.getCriteria() != null && !researcherPosition.isEmpty()) {
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion crit = report.getCriteria().get(i);
                if (crit.getThresholds() == null) continue;
                final String pos = researcherPosition;
                boolean hasThresholdForPosition = crit.getThresholds().stream()
                        .anyMatch(t -> t.getPosition() != null && t.getPosition().name().equals(pos));
                if (!hasThresholdForPosition) continue;   // not applicable for this position
                criteriaTotal++;
                double cScore = criterionScores.getOrDefault(i, 0.0);
                boolean met = crit.getThresholds().stream()
                        .filter(t -> t.getPosition() != null && t.getPosition().name().equals(pos))
                        .anyMatch(t -> cScore >= t.getValue());
                if (met) criteriaMet++;
            }
        }

        // Total score: sum of criterion scores for criteria flagged as contributesToTotal
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
        model.addAttribute("allReports", reports);
        model.addAttribute("indicatorScores", indicatorScores);
        model.addAttribute("criterionScores", criterionScores);
        model.addAttribute("criteriaMet", criteriaMet);
        model.addAttribute("criteriaTotal", criteriaTotal);
        model.addAttribute("totalScore", anyContributesToTotal ? totalScore : null);
        model.addAttribute("researcherPosition", researcherPosition);
        model.addAttribute("runMetaId", run.runId());
        model.addAttribute("runMetaCreatedAt", run.createdAt());
        model.addAttribute("runMetaSource", run.source());
        model.addAttribute("priorRuns", priorRuns);
        model.addAttribute("user", currentUser);
        model.addAttribute("importAvailable", reportImportVerificationFacade.isImportAvailable(resolvedReportId));
        return "user/individual-report-view";
    }

    // ── MVC: refresh actions ─────────────────────────────────────────────────

    @PostMapping("/refresh")
    public String refreshReport(@RequestParam(name = "report", required = false) String reportId,
                                Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        if (reportId != null && !reportId.isBlank()) {
            userIndividualReportRunService.refreshRunWithAllIndicators(currentUser.getEmail(), reportId);
        }
        return "redirect:/user/evaluation" + (reportId != null && !reportId.isBlank() ? "?report=" + reportId : "");
    }

    @PostMapping("/indicator/{id}/refresh")
    public String refreshIndicator(@PathVariable("id") String indicatorId,
                                   @RequestParam(name = "report", required = false) String reportId,
                                   Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        userIndividualReportRunService.refreshRun(currentUser.getEmail(), reportId != null ? reportId : "");
        return "redirect:/user/evaluation" + (reportId != null && !reportId.isBlank() ? "?report=" + reportId : "");
    }

    // ── Export: render the run into the report's bound template ───────────────

    @GetMapping("/export")
    public ResponseEntity<?> exportReport(
            @RequestParam("report") String reportId,
            @RequestParam(name = "run", required = false) String runId,
            @RequestParam(name = "format", defaultValue = "XLSX") ReportFormat format,
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var outcome = reportExportFacade.exportRunOutcome(currentUser.getEmail(), reportId, runId, format, refresh);
        if (!outcome.isSuccess()) {
            return ResponseEntity.status(statusFor(outcome.failureReason())).body(outcome.message());
        }
        var exported = outcome.exportedReport();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exported.contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + exported.filename() + "\"")
                .body(new ByteArrayResource(exported.bytes()));
    }

    // ── Import: read-only score verification against an uploaded file ─────────

    @GetMapping("/import")
    public String showImportForm(@RequestParam("report") String reportId,
                                 @RequestParam(name = "run", required = false) String runId,
                                 Model model,
                                 Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        if (!reportImportVerificationFacade.isImportAvailable(reportId)) {
            return "redirect:/user/evaluation?report=" + reportId;
        }
        model.addAttribute("user", currentUser);
        model.addAttribute("reportId", reportId);
        model.addAttribute("runId", runId);
        return "user/individual-report-import";
    }

    @PostMapping("/import")
    public String handleImport(@RequestParam("report") String reportId,
                               @RequestParam(name = "run", required = false) String runId,
                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                               Model model,
                               HttpServletResponse response,
                               Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }
        model.addAttribute("user", currentUser);
        model.addAttribute("reportId", reportId);
        model.addAttribute("runId", runId);
        if (file == null || file.isEmpty()) {
            model.addAttribute("errorMessage", "Please choose a file to upload.");
            return "user/individual-report-import";
        }
        try {
            var outcome = reportImportVerificationFacade.verifyOutcome(currentUser.getEmail(), reportId, runId, ReportFormat.XLSX, file.getInputStream());
            if (outcome.isSuccess()) {
                var result = outcome.result();
                model.addAttribute("comparison", result.displayedRunComparison());
                model.addAttribute("displayedRunId", result.displayedRunId());
                model.addAttribute("currentRunId", result.currentRunId());
                model.addAttribute("currentRunComparison", result.currentRunComparison());
                model.addAttribute("activityDefs", loadActivityDefs(result.displayedRunComparison()));
            } else {
                response.setStatus(statusFor(outcome.failureReason()).value());
                model.addAttribute("errorMessage", outcome.message());
            }
        } catch (Exception ex) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            model.addAttribute("errorMessage", "Could not read the uploaded file: " + ex.getMessage());
        }
        return "user/individual-report-import";
    }

    /** Activity definitions (with their field schema) keyed by id, for rendering the inline add-form. */
    private Map<String, ro.uvt.pokedex.core.model.activities.Activity> loadActivityDefs(
            ro.uvt.pokedex.core.service.reporting.transfer.compare.ReportScoreComparison comparison) {
        Map<String, ro.uvt.pokedex.core.model.activities.Activity> defs = new HashMap<>();
        if (comparison.activityBlocks() == null) return defs;
        comparison.activityBlocks().forEach(block -> {
            if (block.activityOptions() == null) return;
            block.activityOptions().forEach(opt -> {
                if (opt.activityId() != null && !defs.containsKey(opt.activityId())) {
                    userActivityInstanceFacade.findActivity(opt.activityId()).ifPresent(a -> defs.put(opt.activityId(), a));
                }
            });
        });
        return defs;
    }

    private HttpStatus statusFor(ro.uvt.pokedex.core.service.reporting.transfer.ReportExportFacade.ExportFailureReason reason) {
        return switch (reason) {
            case REPORT_NOT_FOUND, RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN_RUN -> HttpStatus.FORBIDDEN;
            case REPORT_TYPE_NOT_CONFIGURED, UNSUPPORTED_FORMAT -> HttpStatus.BAD_REQUEST;
            case RENDERER_NOT_AVAILABLE -> HttpStatus.NOT_IMPLEMENTED;
            case NOT_READY, RUN_REPORT_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private HttpStatus statusFor(ro.uvt.pokedex.core.service.reporting.transfer.ReportImportVerificationFacade.VerificationFailureReason reason) {
        return switch (reason) {
            case REPORT_NOT_FOUND, RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN_RUN -> HttpStatus.FORBIDDEN;
            case REPORT_TYPE_NOT_CONFIGURED, UNSUPPORTED_FORMAT -> HttpStatus.BAD_REQUEST;
            case PARSER_NOT_AVAILABLE -> HttpStatus.NOT_IMPLEMENTED;
            case IMPORT_DISABLED, NOT_READY, RUN_REPORT_MISMATCH, INVALID_WORKBOOK -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    // ── JSON: indicator detail (for inline criterion expansion in H37.3) ─────

    @GetMapping("/indicator/{id}/detail")
    @ResponseBody
    public ResponseEntity<IndicatorDetailResponse> getIndicatorDetail(
            @PathVariable("id") String indicatorId,
            @RequestParam(value = "report", required = false) String reportId,
            Authentication authentication) {
        return currentUser(authentication).map(user -> {
            IndicatorApplyResultDto result = (reportId != null && !reportId.isBlank())
                    ? userReportFacade.buildReportScopedIndicatorDetail(user.getEmail(), reportId, indicatorId)
                            .orElseGet(() -> userIndicatorResultService.getOrCreateLatest(user.getEmail(), indicatorId))
                    : userIndicatorResultService.getOrCreateLatest(user.getEmail(), indicatorId);
            Map<String, Object> graph = result.rawGraph();

            String outputMode = graph.getOrDefault("outputMode", "publications").toString();
            List<ScoredItem> items = extractScoredItems(graph, outputMode);

            return ResponseEntity.ok(new IndicatorDetailResponse(
                    result.indicatorId(),
                    indicatorNameFrom(graph),
                    outputTypeFrom(graph),
                    outputMode,
                    result.summary().totalScore(),
                    totalCitFrom(graph),
                    result.summary().quarterLabels(),
                    result.summary().quarterValues(),
                    items,
                    result.updatedAt(),
                    result.refreshVersion()
            ));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: citation drilldown for a single cited publication ──────────────

    @GetMapping("/indicator/{id}/citations")
    @ResponseBody
    public ResponseEntity<CitationDetailResponse> getCitationDetail(
            @PathVariable("id") String indicatorId,
            @RequestParam("pub") String pubTitle,
            @RequestParam(value = "report", required = false) String reportId,
            Authentication authentication) {
        return currentUser(authentication).map(user -> {
            IndicatorApplyResultDto result = (reportId != null && !reportId.isBlank())
                    ? userReportFacade.buildReportScopedIndicatorDetail(user.getEmail(), reportId, indicatorId)
                            .orElseGet(() -> userIndicatorResultService.getOrCreateLatest(user.getEmail(), indicatorId))
                    : userIndicatorResultService.getOrCreateLatest(user.getEmail(), indicatorId);
            Map<String, Object> graph = result.rawGraph();

            List<ScoredItem> citations = new ArrayList<>();
            double total = 0.0;

            Object scoresObj = graph.get("scores");
            if (scoresObj instanceof Map<?, ?> pubScores) {
                Object citMapObj = pubScores.get(pubTitle);
                if (citMapObj instanceof Map<?, ?> citScores) {
                    for (Map.Entry<?, ?> entry : citScores.entrySet()) {
                        if ("total".equals(entry.getKey().toString())) continue;
                        double authorScore = extractAuthorScore(entry.getValue());
                        double forumScore  = extractForumScore(entry.getValue());
                        if (authorScore > 0) {
                            citations.add(new ScoredItem(
                                    entry.getKey().toString(),
                                    extractYear(entry.getValue()),
                                    authorScore, forumScore,
                                    extractQuarter(entry.getValue()),
                                    extractCoreRankingEquivalent(entry.getValue()),
                                    extractScoringSource(entry.getValue()),
                                    "publication", null));
                            total += authorScore;
                        }
                    }
                }
            }
            citations.sort(Comparator.comparingDouble(ScoredItem::authorScore).reversed());
            return ResponseEntity.ok(new CitationDetailResponse(pubTitle, total, citations));
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── JSON: compare two runs ────────────────────────────────────────────────

    @GetMapping("/compare")
    @ResponseBody
    public ResponseEntity<ComparisonResponse> compareRuns(
            @RequestParam("runA") String runAId,
            @RequestParam("runB") String runBId,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userOpt.get();

        Optional<UserIndividualReportRun> runAOpt = userIndividualReportRunRepository.findById(runAId);
        Optional<UserIndividualReportRun> runBOpt = userIndividualReportRunRepository.findById(runBId);
        if (runAOpt.isEmpty() || runBOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UserIndividualReportRun runA = runAOpt.get();
        UserIndividualReportRun runB = runBOpt.get();

        if (!runA.getUserEmail().equals(user.getEmail()) || !runB.getUserEmail().equals(user.getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Map<String, ScoreDelta> indicatorDeltas = buildIndicatorDeltas(runA, runB);
        Map<Integer, ScoreDelta> criterionDeltas = buildCriterionDeltas(runA, runB);
        double totalA = sumScores(runA.getIndicatorScoresByIndicatorId());
        double totalB = sumScores(runB.getIndicatorScoresByIndicatorId());

        return ResponseEntity.ok(new ComparisonResponse(
                new RunSummary(runA.getId(), runA.getCreatedAt() != null ? runA.getCreatedAt().toString() : null, runA.getStatus().name(), null),
                new RunSummary(runB.getId(), runB.getCreatedAt() != null ? runB.getCreatedAt().toString() : null, runB.getStatus().name(), null),
                indicatorDeltas,
                criterionDeltas,
                new ScoreDelta(totalA, totalB, totalB - totalA)
        ));
    }

    // ── JSON: compare snapshot vs. current run ───────────────────────────────

    @GetMapping("/compare-snapshot")
    @ResponseBody
    public ResponseEntity<ComparisonResponse> compareSnapshot(
            @RequestParam("snapshotId") String snapshotId,
            @RequestParam("runId") String runBId,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userOpt.get();

        Optional<EvaluationSnapshot> snapOpt = evaluationSnapshotRepository.findByIdAndUserEmail(snapshotId, user.getEmail());
        Optional<UserIndividualReportRun> runBOpt = userIndividualReportRunRepository.findById(runBId);
        if (snapOpt.isEmpty() || runBOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        EvaluationSnapshot snap = snapOpt.get();
        UserIndividualReportRun runB = runBOpt.get();
        if (!runB.getUserEmail().equals(user.getEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Map<String, Double> snapInd = snap.getIndicatorScores() != null ? snap.getIndicatorScores() : Map.of();
        Map<Integer, Double> snapCrit = snap.getCriteriaScores() != null ? snap.getCriteriaScores() : Map.of();
        Map<String, Double> runBInd = runB.getIndicatorScoresByIndicatorId() != null ? runB.getIndicatorScoresByIndicatorId() : Map.of();
        Map<Integer, Double> runBCrit = runB.getCriteriaScores() != null ? runB.getCriteriaScores() : Map.of();

        Set<String> indKeys = new HashSet<>(snapInd.keySet());
        indKeys.addAll(runBInd.keySet());
        Map<String, ScoreDelta> indicatorDeltas = new LinkedHashMap<>();
        for (String key : indKeys) {
            indicatorDeltas.put(key, new ScoreDelta(
                    snapInd.getOrDefault(key, 0.0),
                    runBInd.getOrDefault(key, 0.0),
                    runBInd.getOrDefault(key, 0.0) - snapInd.getOrDefault(key, 0.0)));
        }

        Set<Integer> critKeys = new HashSet<>(snapCrit.keySet());
        critKeys.addAll(runBCrit.keySet());
        Map<Integer, ScoreDelta> criterionDeltas = new LinkedHashMap<>();
        for (Integer key : critKeys) {
            criterionDeltas.put(key, new ScoreDelta(
                    snapCrit.getOrDefault(key, 0.0),
                    runBCrit.getOrDefault(key, 0.0),
                    runBCrit.getOrDefault(key, 0.0) - snapCrit.getOrDefault(key, 0.0)));
        }

        double totalA = sumDoubles(snapInd.values());
        double totalB = sumScores(runB.getIndicatorScoresByIndicatorId());

        return ResponseEntity.ok(new ComparisonResponse(
                new RunSummary("snap:" + snap.getId(), snap.getCreatedAt() != null ? snap.getCreatedAt().toString() : null, "SNAPSHOT", snap.getName()),
                new RunSummary(runB.getId(), runB.getCreatedAt() != null ? runB.getCreatedAt().toString() : null, runB.getStatus().name(), null),
                indicatorDeltas,
                criterionDeltas,
                new ScoreDelta(totalA, totalB, totalB - totalA)
        ));
    }

    // ── JSON: snapshot CRUD ───────────────────────────────────────────────────

    @PostMapping("/snapshots")
    @ResponseBody
    public ResponseEntity<SnapshotSummary> createSnapshot(
            @RequestBody SnapshotRequest request,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userOpt.get();

        long existing = evaluationSnapshotRepository.countByUserEmailAndReportId(user.getEmail(), request.reportId());
        if (existing >= SNAPSHOT_CAP) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }

        Optional<UserIndividualReportRun> runOpt = userIndividualReportRunRepository
                .findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(user.getEmail(), request.reportId());
        if (runOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UserIndividualReportRun run = runOpt.get();

        EvaluationSnapshot snapshot = new EvaluationSnapshot();
        snapshot.setUserEmail(user.getEmail());
        snapshot.setResearcherId(user.getEmail());
        snapshot.setReportId(request.reportId());
        snapshot.setName(request.name() != null && !request.name().isBlank()
                ? request.name().trim()
                : "Snapshot " + Instant.now());
        snapshot.setCreatedAt(Instant.now());
        snapshot.setIndicatorScores(run.getIndicatorScoresByIndicatorId() != null
                ? new HashMap<>(run.getIndicatorScoresByIndicatorId()) : new HashMap<>());
        snapshot.setCriteriaScores(run.getCriteriaScores() != null
                ? new HashMap<>(run.getCriteriaScores()) : new HashMap<>());

        EvaluationSnapshot saved = evaluationSnapshotRepository.save(snapshot);
        return ResponseEntity.status(HttpStatus.CREATED).body(toSnapshotSummary(saved));
    }

    @GetMapping("/snapshots")
    @ResponseBody
    public ResponseEntity<List<SnapshotSummary>> listSnapshots(
            @RequestParam("report") String reportId,
            Authentication authentication) {
        return currentUser(authentication).map(user -> {
            List<SnapshotSummary> list = evaluationSnapshotRepository
                    .findByUserEmailAndReportIdOrderByCreatedAtDesc(user.getEmail(), reportId)
                    .stream()
                    .map(this::toSnapshotSummary)
                    .toList();
            return ResponseEntity.ok(list);
        }).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @GetMapping("/snapshots/{id}")
    @ResponseBody
    public ResponseEntity<SnapshotDetail> getSnapshot(
            @PathVariable String id,
            Authentication authentication) {
        return currentUser(authentication).map(user ->
                evaluationSnapshotRepository.findByIdAndUserEmail(id, user.getEmail())
                        .map(s -> ResponseEntity.ok(new SnapshotDetail(
                                s.getId(), s.getName(), s.getReportId(), s.getCreatedAt(),
                                s.getIndicatorScores(), s.getCriteriaScores(),
                                sumDoubles(s.getIndicatorScores().values()))))
                        .<ResponseEntity<SnapshotDetail>>map(r -> r)
                        .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build())
        ).orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @DeleteMapping("/snapshots/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteSnapshot(
            @PathVariable String id,
            Authentication authentication) {
        Optional<User> userOpt = currentUser(authentication);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userOpt.get();

        Optional<EvaluationSnapshot> snapshotOpt = evaluationSnapshotRepository.findByIdAndUserEmail(id, user.getEmail());
        if (snapshotOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        evaluationSnapshotRepository.delete(snapshotOpt.get());
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Optional<User> currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    @SuppressWarnings("unchecked")
    private List<ScoredItem> extractScoredItems(Map<String, Object> graph, String outputMode) {
        Object scoresObj = graph.get("scores");
        if (scoresObj == null) return List.of();

        List<ScoredItem> items = new ArrayList<>();
        if ("citations".equals(outputMode)) {
            // scores is Map<String, Map<String, Score>>
            if (scoresObj instanceof Map<?, ?> pubScores) {
                for (Map.Entry<?, ?> pubEntry : pubScores.entrySet()) {
                    String pubTitle = pubEntry.getKey().toString();
                    if (pubEntry.getValue() instanceof Map<?, ?> citScores) {
                        Object totalObj = citScores.get("total");
                        double authorScore = extractAuthorScore(totalObj);
                        double forumScore = extractForumScore(totalObj);
                        if (authorScore > 0) {
                            items.add(new ScoredItem(pubTitle, extractYear(totalObj), authorScore, forumScore,
                                    extractQuarter(totalObj), extractCoreRankingEquivalent(totalObj),
                                    extractScoringSource(totalObj), "citation", null));
                        }
                    }
                }
            }
        } else if ("activities".equals(outputMode)) {
            // scores is Map<String, Score> keyed by activity id
            Object activitiesObj = graph.get("activities");
            if (scoresObj instanceof Map<?, ?> actScores) {
                for (Map.Entry<?, ?> entry : actScores.entrySet()) {
                    String key = entry.getKey().toString();
                    double authorScore = extractAuthorScore(entry.getValue());
                    double forumScore = extractForumScore(entry.getValue());
                    if (authorScore > 0) {
                        String label = resolveActivityLabel(activitiesObj, key);
                        items.add(new ScoredItem(label != null ? label : key, extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "activity", extractDetails(entry.getValue())));
                    }
                }
            }
        } else {
            // publications: scores is Map<String, Score> keyed by title
            if (scoresObj instanceof Map<?, ?> pubScores) {
                for (Map.Entry<?, ?> entry : pubScores.entrySet()) {
                    double authorScore = extractAuthorScore(entry.getValue());
                    double forumScore = extractForumScore(entry.getValue());
                    if (authorScore > 0) {
                        items.add(new ScoredItem(entry.getKey().toString(), extractYear(entry.getValue()),
                                authorScore, forumScore, extractQuarter(entry.getValue()),
                                extractCoreRankingEquivalent(entry.getValue()),
                                extractScoringSource(entry.getValue()), "publication", null));
                    }
                }
            }
        }
        return items;
    }

    private double extractAuthorScore(Object scoreObj) {
        if (scoreObj == null) return 0.0;
        try {
            return (double) scoreObj.getClass().getMethod("getAuthorScore").invoke(scoreObj);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double extractForumScore(Object scoreObj) {
        if (scoreObj == null) return 0.0;
        try {
            return (double) scoreObj.getClass().getMethod("getScore").invoke(scoreObj);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String extractQuarter(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object q = scoreObj.getClass().getMethod("getQuarter").invoke(scoreObj);
            return q != null ? q.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDetails(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object d = scoreObj.getClass().getMethod("getDetails").invoke(scoreObj);
            return d != null ? d.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int extractYear(Object scoreObj) {
        if (scoreObj == null) return 0;
        try {
            return (int) scoreObj.getClass().getMethod("getYear").invoke(scoreObj);
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractCoreRankingEquivalent(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object v = scoreObj.getClass().getMethod("getCoreRankingEquivalent").invoke(scoreObj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractScoringSource(Object scoreObj) {
        if (scoreObj == null) return null;
        try {
            Object v = scoreObj.getClass().getMethod("getScoringSource").invoke(scoreObj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveActivityLabel(Object activitiesObj, String activityId) {
        if (activitiesObj instanceof List<?> activities) {
            for (Object act : activities) {
                try {
                    Object id = act.getClass().getMethod("getId").invoke(act);
                    if (activityId.equals(id != null ? id.toString() : null)) {
                        Object name = act.getClass().getMethod("getName").invoke(act);
                        return name != null ? name.toString() : activityId;
                    }
                } catch (Exception ignored) { /* fall through */ }
            }
        }
        return activityId;
    }

    private String indicatorNameFrom(Map<String, Object> graph) {
        Object ind = graph.get("indicator");
        if (ind == null) return null;
        try {
            Object name = ind.getClass().getMethod("getName").invoke(ind);
            return name != null ? name.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String outputTypeFrom(Map<String, Object> graph) {
        Object ind = graph.get("indicator");
        if (ind == null) return null;
        try {
            Object ot = ind.getClass().getMethod("getOutputType").invoke(ind);
            return ot != null ? ot.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Double totalCitFrom(Map<String, Object> graph) {
        Object tc = graph.get("totalCit");
        if (tc == null) return null;
        return tc instanceof Number n ? n.doubleValue() : null;
    }

    private Map<String, ScoreDelta> buildIndicatorDeltas(UserIndividualReportRun runA, UserIndividualReportRun runB) {
        Set<String> keys = new HashSet<>();
        if (runA.getIndicatorScoresByIndicatorId() != null) keys.addAll(runA.getIndicatorScoresByIndicatorId().keySet());
        if (runB.getIndicatorScoresByIndicatorId() != null) keys.addAll(runB.getIndicatorScoresByIndicatorId().keySet());
        Map<String, ScoreDelta> deltas = new LinkedHashMap<>();
        for (String key : keys) {
            double a = runA.getIndicatorScoresByIndicatorId() != null
                    ? runA.getIndicatorScoresByIndicatorId().getOrDefault(key, 0.0) : 0.0;
            double b = runB.getIndicatorScoresByIndicatorId() != null
                    ? runB.getIndicatorScoresByIndicatorId().getOrDefault(key, 0.0) : 0.0;
            deltas.put(key, new ScoreDelta(a, b, b - a));
        }
        return deltas;
    }

    private Map<Integer, ScoreDelta> buildCriterionDeltas(UserIndividualReportRun runA, UserIndividualReportRun runB) {
        Set<Integer> keys = new HashSet<>();
        if (runA.getCriteriaScores() != null) keys.addAll(runA.getCriteriaScores().keySet());
        if (runB.getCriteriaScores() != null) keys.addAll(runB.getCriteriaScores().keySet());
        Map<Integer, ScoreDelta> deltas = new LinkedHashMap<>();
        for (Integer key : keys) {
            double a = runA.getCriteriaScores() != null ? runA.getCriteriaScores().getOrDefault(key, 0.0) : 0.0;
            double b = runB.getCriteriaScores() != null ? runB.getCriteriaScores().getOrDefault(key, 0.0) : 0.0;
            deltas.put(key, new ScoreDelta(a, b, b - a));
        }
        return deltas;
    }

    private double sumScores(Map<String, Double> scores) {
        if (scores == null) return 0.0;
        return scores.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private double sumDoubles(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private SnapshotSummary toSnapshotSummary(EvaluationSnapshot s) {
        return new SnapshotSummary(s.getId(), s.getName(), s.getReportId(),
                s.getCreatedAt(), sumDoubles(s.getIndicatorScores().values()));
    }

    // ── Response / request records ────────────────────────────────────────────

    record IndicatorDetailResponse(
            String indicatorId,
            String indicatorName,
            String outputType,
            String outputMode,
            double totalScore,
            Double totalCitations,
            List<String> quarterLabels,
            List<Integer> quarterValues,
            List<ScoredItem> items,
            Instant updatedAt,
            int refreshVersion
    ) {}

    record ScoredItem(String key, int year, double authorScore, double forumScore,
                      String quarter, String coreRankingEquivalent, String scoringSource,
                      String type, String details) {}

    record CitationDetailResponse(String pubTitle, double totalScore, List<ScoredItem> citations) {}

    record RunSummary(String runId, String createdAt, String status, String name) {}

    record ScoreDelta(double before, double after, double delta) {}

    record ComparisonResponse(
            RunSummary runA,
            RunSummary runB,
            Map<String, ScoreDelta> indicatorDeltas,
            Map<Integer, ScoreDelta> criterionDeltas,
            ScoreDelta aggregateDelta
    ) {}

    record SnapshotRequest(String reportId, String name) {}

    record SnapshotSummary(String id, String name, String reportId, Instant createdAt, double totalScore) {}

    record SnapshotDetail(
            String id,
            String name,
            String reportId,
            Instant createdAt,
            Map<String, Double> indicatorScores,
            Map<Integer, Double> criteriaScores,
            double totalScore
    ) {}
}
