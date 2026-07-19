package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.application.model.ReportScopedIndividualReportComputation;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;
import ro.uvt.pokedex.core.service.reporting.ScoringReferenceYearContext;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserReportFacade {
    private static final String WOS_EXTRACTOR_SOURCE = "WOSEXTRACTOR";
    private static final String LINKER_VERSION = "h17.10";
    private static final Pattern ISSN_PATTERN = Pattern.compile("(?i)\\b[0-9]{4}-?[0-9]{3}[0-9x]\\b");

    private final UserService userService;
    private final IndicatorRepository indicatorRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ReportVisibilityService reportVisibilityService;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final DomainRepository domainRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final WoSExtractor woSExtractor;
    private final CNFISReportExportService exportService;
    private final CacheService cacheService;
    private final PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;
    private final ReportingLookupPort reportingLookupPort;
    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;
    private final ReportingLookupMemoization reportingLookupMemoization;

    public UserIndicatorsViewModel buildIndicatorsView(String userEmail) {
        // userEmail kept in signature to lock facade contract for later permission-aware extensions.
        return new UserIndicatorsViewModel(indicatorRepository.findAll());
    }

    public UserReportsListViewModel buildIndividualReportsListView(String userEmail) {
        // Reports visible to a user are the union of (selection ∩ ¬hide) across all their groups.
        // Falls back to the full catalog for users without any group membership so we don't
        // silently strip away the admin's own view; explicit empty state is preferred where it
        // makes sense, but the catalog fallback keeps platform admins from getting an empty list.
        if (userEmail == null || userEmail.isBlank()) {
            return new UserReportsListViewModel(individualReportRepository.findAll());
        }
        java.util.List<IndividualReport> visible = reportVisibilityService.listVisibleReportsForUser(userEmail);
        return new UserReportsListViewModel(visible);
    }

    public Optional<Indicator> findIndicatorById(String indicatorId) {
        return indicatorRepository.findById(indicatorId);
    }

    public Optional<IndividualReport> findIndividualReportById(String reportId) {
        return individualReportRepository.findById(reportId);
    }

    public Optional<UserIndicatorWorkbookExportViewModel> buildIndicatorWorkbookExport(String userEmail, String indicatorId) throws IOException {
        Optional<User> userOpt = findUserWithProfile(userEmail);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        User user = userOpt.get();
        Optional<Indicator> indicatorOpt = indicatorRepository.findById(indicatorId);
        if (indicatorOpt.isEmpty()) {
            return Optional.empty();
        }

        Indicator indicator = indicatorOpt.get();
        List<ScholardexPublicationView> publications = findConfirmedPublicationsForScoring(userEmail);
        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        if (authors.isEmpty()) {
            return Optional.empty();
        }

        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        Map<String, ScholardexForumView> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // H60: score the export inside the referenceYear context so relative specs (e.g. Mate_S's LatestNRankings)
            // resolve here too — otherwise this live per-indicator export would score them against an empty window.
            if (indicator != null && indicator.isPublicationOutput()) {
                ScoringReferenceYearContext.with(effectiveReferenceYear(), () -> {
                    handlePublicationsWorkbook(workbook, indicator, publications, forumMap);
                    return null;
                });
            } else if (indicator != null && indicator.isCitationsOutput()) {
                ScoringReferenceYearContext.with(effectiveReferenceYear(), () -> {
                    handleCitationsWorkbook(workbook, indicator, authors, publications, forumMap);
                    return null;
                });
            }

            workbook.write(outputStream);
            return Optional.of(new UserIndicatorWorkbookExportViewModel(
                    outputStream.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "indicator_results.xlsx"
            ));
        }
    }

    public UserWorkbookExportResult buildUserCnfisWorkbookExport(String userEmail, int startYear, int endYear) throws IOException {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return UserWorkbookExportResult.unauthorized();
        }

        User user0 = userOpt.get();
        if (user0.getResearcherProfile() == null) {
            return UserWorkbookExportResult.notFound();
        }

        List<String> lookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(user0.getResearcherProfile());
        List<String> authorIds = findAuthorsByIds(lookupKeys).stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findConfirmedPublicationsForScoring(userEmail);
        publications = publications.stream().filter(publication -> {
            return PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                    .map(pubYear -> pubYear >= startYear && pubYear <= endYear)
                    .orElse(false);
        }).toList();

        Domain domain = domainRepository.findByName("ALL").orElse(null);
        List<CNFISReport2025> cnfisReports = new ArrayList<>();
        String linkerRunId = "user-cnfis-" + System.currentTimeMillis();
        List<ScoringPublicationReadModel> scoringPublications = new ArrayList<>();
        for (ScholardexPublicationView publication : publications) {
            String resolvedWosId = publication.getWosId();
            if ((resolvedWosId == null || resolvedWosId.isBlank())
                    && publication.getDoi() != null && !publication.getDoi().isBlank()) {
                resolvedWosId = woSExtractor.resolveWosId(publication.getDoi())
                        .orElse(CanonicalPublicationConstants.NON_WOS_ID);
            }
            publicationEnrichmentLinkerService.linkWosEnrichment(
                    publication.getId(),
                    publication.getEid(),
                    publication.getDoi(),
                    resolvedWosId,
                    WOS_EXTRACTOR_SOURCE,
                    LINKER_VERSION,
                    linkerRunId
            );
            publication.setWosId(resolvedWosId);
            ScoringPublicationReadModel scoringPublication = publication.toScoringPublication();
            scoringPublications.add(scoringPublication);
            cnfisReports.add(cnfiSScoringService2025.getReport(scoringPublication, domain));
        }

        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        Map<String, ScholardexForumView> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));

        byte[] workbookBytes = exportService.generateCNFISReportWorkbook(scoringPublications, cnfisReports, forumMap, authorIds, false);
        return UserWorkbookExportResult.ok(
                workbookBytes,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "data/templates/AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx"
        );
    }

    public UserWorkbookExportResult buildLegacyUserCnfisWorkbookExport(String userEmail) throws IOException {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return UserWorkbookExportResult.unauthorized();
        }

        User user = userOpt.get();
        if (user.getResearcherProfile() == null) {
            return UserWorkbookExportResult.notFound();
        }

        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        if (authors.isEmpty()) {
            return UserWorkbookExportResult.notFound();
        }

        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findConfirmedPublicationsForScoring(userEmail);
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        Map<String, ScholardexForumView> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));

        ClassPathResource resource = new ClassPathResource("/data/templates/Anexa5-Fisa_articole_brevete.xlsx");
        try (Workbook workbook = new XSSFWorkbook(resource.getInputStream()); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowNum = 15;
            for (ScholardexPublicationView publication : publications) {
                Row row = sheet.createRow(rowNum++);

                String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String forumName = forumMap.getOrDefault(publication.getForum(), new ScholardexForumView()).getPublicationName();
                String issnOnline = forumMap.getOrDefault(publication.getForum(), new ScholardexForumView()).getEIssn();
                String issnPrint = forumMap.getOrDefault(publication.getForum(), new ScholardexForumView()).getIssn();
                int totalAuthors = publication.getAuthors().size();
                long universityAuthors = publication.getAuthors().stream().filter(authorIds::contains).count();

                row.createCell(0).setCellValue(year);
                row.createCell(1).setCellValue(title);
                row.createCell(2).setCellValue(doi);
                row.createCell(3).setCellValue("");
                row.createCell(4).setCellValue("");
                row.createCell(5).setCellValue(forumName);
                row.createCell(6).setCellValue(issnOnline);
                row.createCell(7).setCellValue(issnPrint);
                row.createCell(8).setCellValue("");
                row.createCell(13).setCellValue(totalAuthors);
                row.createCell(14).setCellValue(universityAuthors);
            }

            workbook.write(outputStream);
            return UserWorkbookExportResult.ok(
                    outputStream.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "Anexa5-Fisa_articole_brevete.xlsx"
            );
        }
    }

    public UserIndicatorApplyViewModel buildIndicatorApplyView(String userEmail, String indicatorId) {
        Optional<User> userOpt = findUserWithProfile(userEmail);
        if (userOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        User user = userOpt.get();
        Optional<Indicator> indicatorOpt = indicatorRepository.findById(indicatorId);

        if (indicatorOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        Indicator indicator = indicatorOpt.get();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("indicator", indicator);

        if (indicator != null && indicator.isActivityOutput()) {
            List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(user.getEmail());
            activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
            return handleActivities(indicator, activities, attrs);
        }

        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        List<ScholardexPublicationView> publications = findConfirmedPublicationsForScoring(userEmail);
        if (requiresPublicationScoring(indicator)) {
            attrs.put("confirmedPublicationScoringWarning", publications.isEmpty());
        }
        if (indicator != null && indicator.isPublicationOutput()) {
            return handlePublications(indicator, authors, publications, attrs);
        }
        if (indicator != null && indicator.isCitationsOutput()) {
            return handleCitations(indicator, authors, publications, attrs);
        }
        if (indicator != null && indicator.isHIndexOutput()) {
            return handleHIndex(indicator, authors, publications, attrs);
        }

        return new UserIndicatorApplyViewModel("user/indicators", attrs);
    }

    /**
     * H67: interactive preview for an aggregate Hirsch (h-index) indicator — a single number (the indicative h) over
     * the candidate's publications, reusing {@link #computeHIndex} so the preview agrees with the report roll-up + detail.
     */
    private UserIndicatorApplyViewModel handleHIndex(Indicator indicator,
                                                     List<ScholardexAuthorView> authors,
                                                     List<ScholardexPublicationView> publications,
                                                     Map<String, Object> attrs) {
        ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex kind = indicator.hIndexKind();
        boolean itemYear = "IY".equals(indicator.getScoreYearRange());
        int[] r = computeHIndex(indicator, authors, publications);
        attrs.put("outputMode", "hindex");
        attrs.put("total", String.valueOf(r[0]));
        attrs.put("totalCit", r[1]);
        attrs.put("hIndexSource", kind.source().name());
        attrs.put("hIndexYearBasis", itemYear ? "ITEM_YEAR" : "CURRENT");
        return new UserIndicatorApplyViewModel("user/indicators-apply", attrs);
    }

    public UserIndividualReportViewModel buildIndividualReportView(String userEmail, String reportId) {
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return new UserIndividualReportViewModel(null, Map.of());
        }

        IndividualReport report = reportOpt.get();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("report", report);
        Optional<ReportScopedIndividualReportComputation> computationOpt = computeReportScopedIndividualReport(userEmail, reportId);
        if (computationOpt.isEmpty()) {
            return new UserIndividualReportViewModel("redirect:/error", attrs);
        }
        ReportScopedIndividualReportComputation computation = computationOpt.get();
        attrs.put("indicatorScores", computation.indicatorScores());
        attrs.put("criterionScores", computation.criterionScores());

        return new UserIndividualReportViewModel(null, attrs);
    }

    public Optional<ReportScopedIndividualReportComputation> computeReportScopedIndividualReport(String userEmail, String reportId) {
        // H60: ensure a referenceYear is in scope for relative year specs. The run-build path already set the run's
        // year (nested → preserved); the live apply path defaults to the current year.
        return ScoringReferenceYearContext.with(effectiveReferenceYear(),
                () -> computeReportScopedIndividualReportInternal(userEmail, reportId));
    }

    /** H60: the current referenceYear in scope, or the current year when none is set (live apply/detail path). */
    private int effectiveReferenceYear() {
        Integer current = ScoringReferenceYearContext.current();
        return current != null ? current : java.time.LocalDate.now().getYear();
    }

    private Optional<ReportScopedIndividualReportComputation> computeReportScopedIndividualReportInternal(String userEmail, String reportId) {
        Optional<User> userOpt = findUserWithProfile(userEmail);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        Optional<IndividualReport> reportOpt = findReport(reportId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        User user = userOpt.get();
        AuthorshipContext context = new AuthorshipContext(
                findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile())),
                findConfirmedPublicationsForScoring(userEmail),
                activityInstanceRepository.findAllByResearcherId(user.getEmail()));
        return Optional.of(scoreReport(reportOpt.get(), context));
    }

    /**
     * H77: score a report from a DECLARED authorship context — a set of canonical author ids resolved from an org
     * roster / declared source ids, NOT from a researcher's confirmed decisions. Read-only and admin-only: the
     * candidate publication set is every publication attributed to those authors (no {@code PublicationAuthorshipDecision}
     * involved), and those same author ids drive self-citation exclusion + author-share division. There is no
     * {@code User}/profile and no activity instances for a provisional subject, so activities are empty.
     */
    public Optional<ReportScopedIndividualReportComputation> computeProvisionalReport(
            Collection<String> resolvedAuthorIds, String reportId, Integer referenceYear) {
        int year = referenceYear != null ? referenceYear : effectiveReferenceYear();
        return ScoringReferenceYearContext.with(year, () -> {
            Optional<IndividualReport> reportOpt = findReport(reportId);
            if (reportOpt.isEmpty() || resolvedAuthorIds == null || resolvedAuthorIds.isEmpty()) {
                return Optional.empty();
            }
            AuthorshipContext context = new AuthorshipContext(
                    findAuthorsByIds(resolvedAuthorIds),
                    findPublicationsByAuthorIds(resolvedAuthorIds),
                    List.of());
            return Optional.of(scoreReport(reportOpt.get(), context));
        });
    }

    /** H77: the authorship inputs that differ between a CONFIRMED (self) and DECLARED (provisional) scoring run. */
    private record AuthorshipContext(List<ScholardexAuthorView> authors,
                                     List<ScholardexPublicationView> publications,
                                     List<ActivityInstance> activities) {
    }

    /**
     * Shared report-scoring loop. Both the CONFIRMED (researcher self-scoring) and DECLARED (H77 admin provisional)
     * paths converge here once they have produced the authorship context. Applies the report's affiliation filter to
     * the candidate publications, then scores every indicator; relative year specs resolve against the referenceYear
     * already in scope (callers wrap in {@link ScoringReferenceYearContext}).
     */
    private ReportScopedIndividualReportComputation scoreReport(IndividualReport report, AuthorshipContext context) {
        List<ScholardexAuthorView> authors = context.authors();
        List<ScholardexPublicationView> publications = applyAffiliationFilter(report, context.publications());
        List<ActivityInstance> activities = context.activities();

        List<Indicator> indicators = report.getIndicators() == null ? List.of() : report.getIndicators();
        Map<Indicator, Double> indicatorScores = new HashMap<>();
        Map<String, Double> indicatorScoresByIndicatorId = new HashMap<>();
        Map<String, IndicatorApplyResultDto> reportScopedIndicatorResultsByIndicatorId = new HashMap<>();

        boolean hasCitationIndicators = indicators.stream()
                .filter(Objects::nonNull)
                .anyMatch(Indicator::isCitationsOutput);
        Set<String> researcherAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        CitationPrecomputeBundle citationPrecompute = precomputeCitationData(indicators, publications, hasCitationIndicators);
        ReportScopedIndicatorScoringSupport.CitationContext citationContext = citationPrecompute.citationContext();
        Map<Indicator, Map<String, Score>> citationBaseScoresByIndicator = citationPrecompute.baseScoresByIndicator();

        for (Indicator indicator : indicators) {
            if (indicator == null || indicator.getId() == null) {
                continue;
            }

            double indicatorScore = 0.0;
            if (indicator != null && indicator.isActivityOutput()) {
                List<ActivityInstance> filteredActivities = activities.stream()
                        .filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName()))
                        .toList();
                indicatorScore = activityReportingService.calculateActivityScores(filteredActivities, indicator)
                        .get("total")
                        .getAuthorScore();
            }
            if (indicator != null && indicator.isPublicationOutput()) {
                indicatorScore = calculatePublicationScore(indicator, authors, publications);
            } else if (indicator != null && indicator.isCitationsOutput()) {
                indicatorScore = ReportScopedIndicatorScoringSupport.calculateCitationScore(
                                indicator,
                                publications,
                                researcherAuthorIds,
                                citationContext,
                                citationBaseScoresByIndicator.getOrDefault(indicator, Map.of()),
                                scientificProductionService)
                        .score();
            } else if (indicator != null && indicator.isHIndexOutput()) {
                // H67: the aggregate Hirsch reduce — h over the per-pub source citation counts, NOT a sum. Same
                // computation the indicator detail uses, so the roll-up score (and the H77 provisional table) agree.
                indicatorScore = computeHIndex(indicator, authors, publications)[0];
            }

            // Per-indicator absolute cap (e.g. Info_D_ix visiting-professor "maximum 24 puncte").
            // Applied at the single point where all output types converge, so it governs the
            // official indicator score and the criterion thresholds computed below.
            indicatorScore = indicator.applyPointsCap(indicatorScore);

            indicatorScores.put(indicator, indicatorScore);
            indicatorScoresByIndicatorId.put(indicator.getId(), indicatorScore);
            reportScopedIndicatorResultsByIndicatorId.put(
                    indicator.getId(),
                    new IndicatorApplyResultDto(
                            null,
                            indicator.getId(),
                            ReportScopedIndicatorScoringSupport.viewNameFor(indicator),
                            Map.of("indicator", indicator, "total", String.format(Locale.ROOT, "%.2f", indicatorScore)),
                            new IndicatorApplyResultDto.Summary(indicatorScore, null, List.of(), List.of()),
                            IndicatorApplyResultDto.Source.COMPUTED,
                            null,
                            null,
                            0
                    )
            );
        }

        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        Map<Integer, Double> criterionScores = ReportingComputationSupport.computeCriterionScores(
                criteria,
                indicators,
                indicatorScoresByIndicatorId
        );

        return new ReportScopedIndividualReportComputation(
                indicatorScores,
                indicatorScoresByIndicatorId,
                criterionScores,
                reportScopedIndicatorResultsByIndicatorId
        );
    }

    /**
     * Forum display name for the drilldown's forum links (the scores map only carries forum ids);
     * null when the id is unknown. Backed by the startup-preloaded forum cache — a plain map get.
     */
    public String resolveForumName(String forumId) {
        if (forumId == null || forumId.isBlank()) {
            return null;
        }
        ScholardexForumView forum = cacheService.getCachedForums(forumId);
        return forum != null ? forum.getPublicationName() : null;
    }

    /**
     * Computes the full indicator detail (raw graph with per-item scores) in the context of a
     * specific report, applying the report's affiliation filter to the publication set.
     * Used by the detail and citation-drilldown endpoints so that expanded indicator rows show
     * exactly the same publications/citations that contributed to the report-level score.
     */
    public Optional<IndicatorApplyResultDto> buildReportScopedIndicatorDetail(
            String userEmail, String reportId, String indicatorId) {
        // H60: resolve relative year specs against a referenceYear (current year on this live-detail path).
        // Memoization refresh-scope: without it every getOrCompute in the reporting lookups is a raw
        // pass-through, so one interactive detail click re-queried the same forum's rankings dozens of
        // times (~1.6k Postgres round-trips for a citations indicator). The run-refresh path already
        // opens an outer scope; withRefreshScope nests as a no-op there (owner-checked).
        return ScoringReferenceYearContext.with(effectiveReferenceYear(),
                () -> reportingLookupMemoization.withRefreshScope(
                        () -> buildReportScopedIndicatorDetailInternal(userEmail, reportId, indicatorId)));
    }

    private Optional<IndicatorApplyResultDto> buildReportScopedIndicatorDetailInternal(
            String userEmail, String reportId, String indicatorId) {
        if (indicatorId == null) {
            return Optional.empty();
        }
        Optional<User> userOpt = findUserWithProfile(userEmail);
        if (userOpt.isEmpty()) return Optional.empty();

        Optional<IndividualReport> reportOpt = findReport(reportId);
        if (reportOpt.isEmpty()) return Optional.empty();
        IndividualReport report = reportOpt.get();

        // Find the indicator in the report's indicator list
        Indicator foundIndicator = null;
        if (report.getIndicators() != null) {
            for (Indicator ind : report.getIndicators()) {
                if (ind != null && indicatorId.equals(ind.getId())) {
                    foundIndicator = ind;
                    break;
                }
            }
        }
        if (foundIndicator == null) return Optional.empty();
        final Indicator indicator = foundIndicator;

        User user = userOpt.get();
        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile()));
        List<ScholardexPublicationView> publications = applyAffiliationFilter(
                report,
                findConfirmedPublicationsForScoring(userEmail)
        );

        Map<String, Object> rawGraph = new HashMap<>();
        rawGraph.put("indicator", indicator);

        if (indicator != null && indicator.isActivityOutput()) {
            List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(user.getEmail());
            final String activityName = indicator.getActivity().getName();
            List<ActivityInstance> filteredActivities = activities.stream()
                    .filter(act -> act.getActivity().getName().equals(activityName))
                    .toList();
            Map<String, Score> scores = new HashMap<>(activityReportingService.calculateActivityScores(filteredActivities, indicator));
            Score totalScore = scores.remove("total");
            double total = totalScore != null ? totalScore.getAuthorScore() : 0.0;
            rawGraph.put("total", String.format(Locale.ROOT, "%.2f", total));
            rawGraph.put("scores", scores);
            rawGraph.put("activities", filteredActivities);
            rawGraph.put("outputMode", "activities");
            return Optional.of(new IndicatorApplyResultDto(
                    null, indicatorId,
                    ReportScopedIndicatorScoringSupport.viewNameFor(indicator),
                    rawGraph,
                    new IndicatorApplyResultDto.Summary(total, null, List.of(), List.of()),
                    IndicatorApplyResultDto.Source.COMPUTED, null, Instant.now(), 0));
        }

        if (indicator != null && indicator.isPublicationOutput()) {
            // Shared typed author-role dispatch (ALL / MAIN / CO / FIRST_OR_CORRESPONDING) — the same filter the
            // roll-up uses, so the detail rows always match the publications that contributed to the score.
            // (The inline copy this replaces skipped FIRST_OR_CORRESPONDING, so H63 indicators showed extra rows.)
            List<ScholardexPublicationView> filteredPublications =
                    ReportingComputationSupport.filterByAuthorRole(indicator, authors, publications);
            ScientificProductionService.ScoredProductionResult detailed =
                    scoredPublicationDetailed(indicator, filteredPublications);
            Map<String, Score> scores = new HashMap<>(detailed.scores());
            Score totalScore = scores.remove("total");
            double total = totalScore != null ? totalScore.getAuthorScore() : 0.0;
            rawGraph.put("total", String.format(Locale.ROOT, "%.2f", total));
            rawGraph.put("scores", scores);
            rawGraph.put("publications", filteredPublications);
            rawGraph.put("excludedItems", excludedItemsFor(detailed, publications, filteredPublications));
            rawGraph.put("outputMode", "publications");
            Map<String, Integer> qHist = new HashMap<>();
            scores.forEach((k, v) -> {
                if (v.getQuarter() != null) qHist.merge(v.getQuarter(), 1, Integer::sum);
            });
            rawGraph.put("allQuarters", new ArrayList<>(qHist.keySet()));
            rawGraph.put("allValues", new ArrayList<>(qHist.values()));
            return Optional.of(new IndicatorApplyResultDto(
                    null, indicatorId,
                    ReportScopedIndicatorScoringSupport.viewNameFor(indicator),
                    rawGraph,
                    new IndicatorApplyResultDto.Summary(total, null,
                            new ArrayList<>(qHist.keySet()), new ArrayList<>(qHist.values())),
                    IndicatorApplyResultDto.Source.COMPUTED, null, Instant.now(), 0));
        }

        // H67 S4a: aggregate Hirsch (h-index) — the value is hIndex over the per-pub source citation counts, NOT a sum.
        // !excludeSelf reads the S1 projection columns directly (fast). excludeSelf (S4a 2/2b) walks the citation graph,
        // drops self-citations (citing pub shares a researcher author), and classifies by venue: WoS = YEAR-TRUE Core
        // (SCIE/SSCI/AHCI in the citing paper's year, via the scoped category read); Scopus = current scopusId; else all.
        if (indicator != null && indicator.isHIndexOutput()) {
            ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex kind = indicator.hIndexKind();
            // H67: WoS-Core membership basis — "IY" (ScoreYearRangeSpec.ItemYear) = year-true (citing paper's year);
            // anything else = current snapshot membership.
            boolean itemYear = "IY".equals(indicator.getScoreYearRange());
            int[] r = computeHIndex(indicator, authors, publications);
            int h = r[0];
            int totalCit = r[1];
            rawGraph.put("hIndexYearBasis", itemYear ? "ITEM_YEAR" : "CURRENT");
            rawGraph.put("total", String.valueOf(h));
            rawGraph.put("totalCit", totalCit);
            rawGraph.put("outputMode", "hindex");
            rawGraph.put("hIndexSource", kind.source().name());
            return Optional.of(new IndicatorApplyResultDto(
                    null, indicatorId,
                    ReportScopedIndicatorScoringSupport.viewNameFor(indicator),
                    rawGraph,
                    new IndicatorApplyResultDto.Summary((double) h, totalCit, List.of(), List.of()),
                    IndicatorApplyResultDto.Source.COMPUTED, null, Instant.now(), 0));
        }

        // Citations (CITATIONS or CITATIONS_EXCLUDE_SELF)
        Set<String> researcherAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        CitationPrecomputeBundle citationPrecompute = precomputeCitationData(List.of(indicator), publications, true);
        ReportScopedIndicatorScoringSupport.CitationContext citationContext = citationPrecompute.citationContext();
        ReportScopedIndicatorScoringSupport.CitationViewComputation citationView =
                ReportScopedIndicatorScoringSupport.computeCitationView(
                        indicator, publications, researcherAuthorIds, citationContext,
                        citationPrecompute.baseScoresByIndicator().getOrDefault(indicator, Map.of()),
                        scientificProductionService);
        rawGraph.put("total", String.format(Locale.ROOT, "%.2f", citationView.totalScore()));
        rawGraph.put("scores", citationView.displayScores());
        rawGraph.put("totalCit", citationView.totalCitationCount());
        rawGraph.put("outputMode", "citations");
        rawGraph.put("allQuarters", citationView.quarterLabels());
        rawGraph.put("allValues", citationView.quarterValues());
        return Optional.of(new IndicatorApplyResultDto(
                null, indicatorId,
                ReportScopedIndicatorScoringSupport.viewNameFor(indicator),
                rawGraph,
                new IndicatorApplyResultDto.Summary(citationView.totalScore(),
                        citationView.totalCitationCount(),
                        citationView.quarterLabels(), citationView.quarterValues()),
                IndicatorApplyResultDto.Source.COMPUTED, null, Instant.now(), 0));
    }

    /**
     * H67 S4a 2/2b: h-index over the citation graph. Drops self-citations when {@code kind.excludeSelf()} (citing pub
     * shares a researcher author), and classifies citations by venue source — WoS = Core membership (year-true via the
     * scoped category read when {@code itemYear}, else the current snapshot); Scopus = current scopusId; GRAPH/SCHOLARDEX
     * = all internal. Returns {@code [h, totalCitationsCounted]}.
     */
    /**
     * H67: compute {@code [h, totalCitationsCounted]} for an HIndex indicator over the candidate's publications.
     * Non-WoS without self-citation exclusion reads the fast S1 projection columns; WoS (year-true / current Core) and
     * any {@code excludeSelf} walk the citation graph ({@link #hIndexFromGraph}). Shared by the report roll-up
     * ({@link #scoreReport}), the indicator detail, and the interactive preview so all three agree on the same h.
     */
    private int[] computeHIndex(Indicator indicator,
                                List<ScholardexAuthorView> authors,
                                List<ScholardexPublicationView> publications) {
        ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex kind = indicator.hIndexKind();
        boolean itemYear = "IY".equals(indicator.getScoreYearRange());
        if (kind.source() == ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.WOS_VENUE || kind.excludeSelf()) {
            return hIndexFromGraph(kind, itemYear, authors, publications);
        }
        int h = HIndexCalculator.hIndexForSource(publications, kind.source());
        int totalCit = publications.stream().mapToInt(HIndexCalculator.extractorFor(kind.source())).sum();
        return new int[]{h, totalCit};
    }

    private int[] hIndexFromGraph(ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind.HIndex kind,
                                  boolean itemYear,
                                  List<ScholardexAuthorView> authors,
                                  List<ScholardexPublicationView> publications) {
        boolean excludeSelf = kind.excludeSelf();
        Set<String> researcherAuthorIds = excludeSelf
                ? authors.stream().map(ScholardexAuthorView::getId).filter(Objects::nonNull).collect(Collectors.toSet())
                : Set.of();
        ReportScopedIndicatorScoringSupport.CitationContext ctx =
                ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService);
        Map<String, List<ScholardexPublicationView>> citingByCited = ctx.citingPublicationsByCitedPublicationId();
        ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource source = kind.source();

        Set<String> citingForumIds = new HashSet<>();
        for (ScholardexPublicationView pub : publications) {
            for (ScholardexPublicationView c : citingByCited.getOrDefault(pub.getId(), List.of())) {
                if (c.getForum() != null) {
                    citingForumIds.add(c.getForum());
                }
            }
        }

        java.util.function.Predicate<ScholardexPublicationView> venueOk;
        if (source == ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.WOS_VENUE && itemYear) {
            Set<Integer> years = new HashSet<>();
            for (ScholardexPublicationView pub : publications) {
                for (ScholardexPublicationView c : citingByCited.getOrDefault(pub.getId(), List.of())) {
                    Integer y = parseYear(c.getCoverDate());
                    if (y != null) {
                        years.add(y);
                    }
                }
            }
            Map<String, Set<Integer>> coreYears =
                    scholardexProjectionReadService.findForumCoreCollectionYears(citingForumIds, years);
            venueOk = c -> {
                Integer y = parseYear(c.getCoverDate());
                return y != null && c.getForum() != null
                        && coreYears.getOrDefault(c.getForum(), Set.of()).contains(y);
            };
        } else if (source == ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.WOS_VENUE) {
            Set<String> currentCore = scholardexProjectionReadService.findForumsCurrentlyInCore(citingForumIds);
            venueOk = c -> c.getForum() != null && currentCore.contains(c.getForum());
        } else if (source == ro.uvt.pokedex.core.model.reporting.scoring.HIndexSource.SCOPUS_VENUE) {
            Map<String, ScholardexForumView> forums = new HashMap<>();
            for (ScholardexForumView f : scholardexProjectionReadService.findForumsByIdIn(citingForumIds)) {
                forums.put(f.getId(), f);
            }
            venueOk = c -> c.getForum() != null && forums.get(c.getForum()) != null
                    && forums.get(c.getForum()).getScopusId() != null;
        } else {
            venueOk = c -> true; // GRAPH / SCHOLARDEX: all internal citations
        }

        int[] counts = new int[publications.size()];
        int total = 0;
        int idx = 0;
        for (ScholardexPublicationView pub : publications) {
            int cnt = 0;
            for (ScholardexPublicationView c : citingByCited.getOrDefault(pub.getId(), List.of())) {
                if (excludeSelf && sharesAnyAuthor(c, researcherAuthorIds)) {
                    continue; // self-citation
                }
                if (venueOk.test(c)) {
                    cnt++;
                }
            }
            counts[idx++] = cnt;
            total += cnt;
        }
        return new int[]{HIndexCalculator.hIndexOfCounts(counts), total};
    }

    private static boolean sharesAnyAuthor(ScholardexPublicationView pub, Set<String> authorIds) {
        if (pub == null || pub.getAuthors() == null) {
            return false;
        }
        for (String a : pub.getAuthors()) {
            if (authorIds.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseYear(String coverDate) {
        if (coverDate == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{4})").matcher(coverDate);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private UserIndicatorApplyViewModel handlePublications(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications, Map<String, Object> attrs) {
        // Shared typed author-role dispatch — same filter as the roll-up and the report-scoped detail
        // (the inline copy this replaces skipped FIRST_OR_CORRESPONDING).
        List<ScholardexPublicationView> filteredPublications =
                ReportingComputationSupport.filterByAuthorRole(indicator, authors, publications);
        ScientificProductionService.ScoredProductionResult detailed =
                scoredPublicationDetailed(indicator, filteredPublications);
        Map<String, Score> scores = new HashMap<>(detailed.scores());
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
        attrs.put("excludedItems", excludedItemsFor(detailed, publications, filteredPublications));
        filteredPublications = filteredPublications.stream().filter(p -> scores.containsKey(p.getTitle()) && scores.get(p.getTitle()).getAuthorScore() > 0.0).collect(Collectors.toList());
        attrs.put("publications", filteredPublications);

        Set<String> forumKeys = new HashSet<>();
        filteredPublications.forEach(p -> forumKeys.add(p.getForum()));
        List<ScholardexForumView> forums = findForumsByIds(forumKeys);
        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        attrs.put("forumMap", forumMap);
        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());
        attrs.put("outputMode", "publications");

        return new UserIndicatorApplyViewModel("user/indicators-apply", attrs);
    }

    private UserIndicatorApplyViewModel handleActivities(Indicator indicator, List<ActivityInstance> activities, Map<String, Object> attrs) {
        Map<String, Score> scores = activityReportingService.calculateActivityScores(activities, indicator);
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
        attrs.put("activities", activities);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());
        attrs.put("outputMode", "activities");

        return new UserIndicatorApplyViewModel("user/indicators-apply", attrs);
    }

    private UserIndicatorApplyViewModel handleCitations(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications, Map<String, Object> attrs) {
        Set<String> researcherAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        ReportScopedIndicatorScoringSupport.CitationContext citationContext =
                ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService);
        Map<Indicator, Map<String, Score>> citationBaseScores =
                ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                        List.of(indicator),
                        citationContext,
                        scientificProductionService
                );
        ReportScopedIndicatorScoringSupport.CitationViewComputation citationView =
                ReportScopedIndicatorScoringSupport.computeCitationView(
                        indicator,
                        publications,
                        researcherAuthorIds,
                        citationContext,
                        citationBaseScores.getOrDefault(indicator, Map.of()),
                        scientificProductionService
                );

        List<ScholardexForumView> forums = findForumsByIds(citationView.forumIds());
        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));
        attrs.put("forumMap", forumMap);
        attrs.put("forumWosLinkMap", buildForumWosLinkMap(forums));

        attrs.put("allQuarters", citationView.quarterLabels());
        attrs.put("allValues", citationView.quarterValues());
        attrs.put("total", String.format("%.2f", citationView.totalScore()));
        attrs.put("totalCit", citationView.totalCitationCount());
        attrs.put("scores", citationView.displayScores());
        attrs.put("publications", publications.stream()
                .filter(publication -> {
                    if (publication == null || publication.getTitle() == null) {
                        return false;
                    }
                    Map<String, Score> publicationScores = citationView.displayScores().get(publication.getTitle());
                    if (publicationScores == null) {
                        return false;
                    }
                    Score totalScore = publicationScores.get("total");
                    return totalScore != null && totalScore.getAuthorScore() > 0.0;
                })
                .toList());
        attrs.put("citationMap", citationView.citationMap());
        attrs.put("outputMode", "citations");

        return new UserIndicatorApplyViewModel("user/indicators-apply", attrs);
    }

    private Map<String, String> buildForumWosLinkMap(List<ScholardexForumView> forums) {
        Map<String, String> forumWosLinkMap = new HashMap<>();
        for (ScholardexForumView forum : forums) {
            if (forum == null || forum.getId() == null) {
                continue;
            }
            String wosJournalId = resolveWosJournalId(forum);
            if (wosJournalId != null) {
                forumWosLinkMap.put(forum.getId(), wosJournalId);
            }
        }
        return forumWosLinkMap;
    }

    private String resolveWosJournalId(ScholardexForumView forum) {
        LinkedHashSet<String> issns = extractIssnCandidates(forum.getIssn(), forum.getEIssn());
        for (String issn : issns) {
            List<WoSRanking> rankings = reportingLookupPort.getRankingsByIssn(issn);
            if (!rankings.isEmpty()) {
                String journalId = rankings.getFirst().getId();
                if (journalId != null && !journalId.isBlank()) {
                    return journalId;
                }
            }
        }
        return null;
    }

    private LinkedHashSet<String> extractIssnCandidates(String... rawValues) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            Matcher matcher = ISSN_PATTERN.matcher(rawValue);
            while (matcher.find()) {
                String normalized = normalizeIssnToken(matcher.group());
                if (normalized != null) {
                    candidates.add(normalized);
                }
            }
            String directNormalized = normalizeIssnToken(rawValue);
            if (directNormalized != null) {
                candidates.add(directNormalized);
            }
        }
        return candidates;
    }

    private String normalizeIssnToken(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9X]", "");
        if (normalized.length() != 8) {
            return null;
        }
        return normalized;
    }

    private double calculatePublicationScore(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications) {
        List<ScholardexPublicationView> filtered =
                ReportingComputationSupport.filterByAuthorRole(indicator, authors, publications);
        return scoredPublicationMap(indicator, filtered).get("total").getAuthorScore();
    }

    /**
     * The one heavy publication-scoring step, memoized in the surrounding refresh scope. A Refresh All computes
     * each indicator up to three times (LATEST refresh, report-scoped totals, per-indicator run detail) over the
     * same publication set — the score map is identical each time, so compute it once per
     * (indicator, referenceYear, publication set) and hand every caller its own shallow copy (callers restructure
     * the map — remove("total") etc. — but never mutate the Score values). Outside a scope this is a pass-through.
     */
    private Map<String, Score> scoredPublicationMap(Indicator indicator, List<ScholardexPublicationView> publications) {
        return new HashMap<>(scoredPublicationDetailed(indicator, publications).scores());
    }

    private ScientificProductionService.ScoredProductionResult scoredPublicationDetailed(
            Indicator indicator, List<ScholardexPublicationView> publications) {
        String key = indicator.getId()
                + "|y=" + ro.uvt.pokedex.core.service.reporting.ScoringReferenceYearContext.currentOrCurrentYear()
                + "|" + publications.stream().map(ScholardexPublicationView::getId).sorted().collect(Collectors.joining(","));
        return reportingLookupMemoization.getOrCompute("userReport", "publicationScoreMap", key,
                () -> scientificProductionService.calculateScientificProductionScoreDetailed(
                        publications.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                        indicator));
    }

    /**
     * Gate-dropped publications for the detail views: the scorer's excluded map (zero score, with
     * {@code scoringInfo.zeroReason}) plus publications removed by the author-role filter before
     * scoring. Only the two detail-build paths attach this — the scores map every roll-up/export
     * consumer iterates stays untouched.
     */
    private Map<String, Score> excludedItemsFor(ScientificProductionService.ScoredProductionResult detailed,
                                                List<ScholardexPublicationView> allPublications,
                                                List<ScholardexPublicationView> filteredPublications) {
        Map<String, Score> excluded = new LinkedHashMap<>(detailed.excluded());
        Set<String> filteredIds = filteredPublications.stream()
                .map(ScholardexPublicationView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (ScholardexPublicationView pub : allPublications) {
            if (pub.getId() != null && !filteredIds.contains(pub.getId()) && pub.getTitle() != null) {
                Score roleFiltered = new Score();
                roleFiltered.getScoringInfo().put("zeroReason", "ROLE_FILTERED");
                excluded.putIfAbsent(pub.getTitle(), roleFiltered);
            }
        }
        return excluded;
    }

    private double calculateCitationScore(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications) {
        Set<String> researcherAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        ReportScopedIndicatorScoringSupport.CitationContext citationContext =
                ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService);
        Map<Indicator, Map<String, Score>> cachedCitationBaseScores =
                ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                        List.of(indicator),
                        citationContext,
                        scientificProductionService
                );
        return ReportScopedIndicatorScoringSupport.calculateCitationScore(
                        indicator,
                        publications,
                        researcherAuthorIds,
                        citationContext,
                        cachedCitationBaseScores.getOrDefault(indicator, Map.of()),
                        scientificProductionService
                )
                .score();
    }

    private void handlePublicationsWorkbook(Workbook workbook, Indicator indicator, List<ScholardexPublicationView> publications, Map<String, ScholardexForumView> forumMap) {
        Map<String, Score> scores = scoredPublicationMap(indicator, publications);
        Sheet sheet = workbook.getSheet("Publications");
        if (sheet == null) {
            sheet = workbook.createSheet("Publications");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Title");
            headerRow.createCell(1).setCellValue("Authors");
            headerRow.createCell(2).setCellValue("Forum");
            headerRow.createCell(3).setCellValue("Volume");
            headerRow.createCell(4).setCellValue("Year");
            headerRow.createCell(5).setCellValue("Workshop");
            headerRow.createCell(6).setCellValue("Category");
            headerRow.createCell(7).setCellValue("Forum Score");
            headerRow.createCell(8).setCellValue("Author Score");
        }

        int rowNum = sheet.getLastRowNum();
        for (ScholardexPublicationView publication : publications) {
            if (scores.get(publication.getTitle()) == null) {
                continue;
            }
            Row dataRow = sheet.createRow(++rowNum);
            dataRow.createCell(0).setCellValue(publication.getTitle());
            String authorDetails = String.join(", ", getAuthorNames(publication.getAuthors(), cacheService.getAuthorCache()));
            dataRow.createCell(1).setCellValue(authorDetails);
            dataRow.createCell(2).setCellValue(forumMap.get(publication.getForum()).getPublicationName());
            dataRow.createCell(3).setCellValue(publication.getVolume());
            dataRow.createCell(4).setCellValue(PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log));
            dataRow.createCell(5).setCellValue("No");
            dataRow.createCell(6).setCellValue(scores.get(publication.getTitle()).getCoreRankingEquivalent());
            dataRow.createCell(7).setCellValue(scores.get(publication.getTitle()).getScore());
            dataRow.createCell(8).setCellValue(scores.get(publication.getTitle()).getAuthorScore());
        }
    }

    private void handleCitationsWorkbook(Workbook workbook, Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications, Map<String, ScholardexForumView> forumMap) {
        // H52 slice 11d.2: typed-kind check.
        boolean excludeSelf = indicator.isCitationsExcludeSelf();
        Sheet sheet = workbook.getSheet("Citations");
        if (sheet == null) {
            sheet = workbook.createSheet("Citations");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("Cited Title");
            headerRow.createCell(1).setCellValue("Citing Title");
            headerRow.createCell(2).setCellValue("Authors");
            headerRow.createCell(3).setCellValue("Forum");
            headerRow.createCell(4).setCellValue("Volume");
            headerRow.createCell(5).setCellValue("Year");
            headerRow.createCell(6).setCellValue("Workshop");
            headerRow.createCell(7).setCellValue("Category");
            headerRow.createCell(8).setCellValue("Forum Score");
            headerRow.createCell(9).setCellValue("Author Score");
        }

        int rowIdx = sheet.getLastRowNum();
        for (ScholardexPublicationView publication : publications) {
            sheet.createRow(++rowIdx);
            List<ScholardexCitationView> citations = findCitationsByCitedId(publication.getId());
            List<String> citingIds = citations.stream().map(ScholardexCitationView::getCitingId).collect(Collectors.toList());
            List<ScholardexPublicationView> citingPublications = findPublicationsByIds(citingIds);

            Set<String> forumKeys = citingPublications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
            Set<String> authorIds = citingPublications.stream().map(ScholardexPublicationView::getAuthors).flatMap(Collection::stream).collect(Collectors.toSet());
            List<ScholardexForumView> forums = findForumsByIds(forumKeys);
            findAuthorsByIds(authorIds);
            Map<String, ScholardexForumView> forumMap2 = forums.stream().collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));
            forumMap.putAll(forumMap2);

            for (ScholardexPublicationView citingPublication : citingPublications) {
                if (excludeSelf && authors.stream().anyMatch(author -> citingPublication.getAuthors().contains(author.getId()))) {
                    continue;
                }
                if (forumMap.get(citingPublication.getForum()) == null) {
                    continue;
                }

                Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(
                        publication.toScoringPublication(),
                        Collections.singletonList(citingPublication.toScoringPublication()),
                        indicator
                );
                Score citationScore = citScores.get(citingPublication.getTitle());
                if (citationScore == null) {
                    continue;
                }

                String authorDetails = String.join(", ", getAuthorNames(citingPublication.getAuthors(), cacheService.getAuthorCache()));

                Row row = sheet.createRow(++rowIdx);
                row.createCell(0).setCellValue(publication.getTitle());
                row.createCell(1).setCellValue(citingPublication.getTitle());
                row.createCell(2).setCellValue(authorDetails);
                row.createCell(3).setCellValue(forumMap.get(citingPublication.getForum()).getPublicationName());
                row.createCell(4).setCellValue(citingPublication.getVolume());
                row.createCell(5).setCellValue(PersistenceYearSupport.extractYearString(citingPublication.getCoverDate(), citingPublication.getId(), log));
                row.createCell(6).setCellValue("No");
                row.createCell(7).setCellValue(citationScore.getCoreRankingEquivalent());
                row.createCell(8).setCellValue(citationScore.getScore());
                row.createCell(9).setCellValue(citationScore.getAuthorScore());
            }
        }
    }

    private String[] getAuthorNames(List<String> authorIds, Map<String, ScholardexAuthorView> authorMap) {
        String[] result = new String[authorIds.size()];
        for (int i = 0; i < authorIds.size(); i++) {
            if (authorMap.containsKey(authorIds.get(i))) {
                result[i] = authorMap.get(authorIds.get(i)).getName();
            }
        }
        return result;
    }

    private void applyFinalSelector(Indicator indicator, Map<String, Map<String, Score>> scores) {
        ReportingComputationSupport.applyFinalSelector(indicator, scores);
    }

    // The three per-user scoring inputs below are re-requested for every indicator of a Refresh All
    // (3 passes x N indicators). Memoized in the surrounding refresh scope (opened by
    // UserIndividualReportRunService / the group runners) so each is fetched once per refresh;
    // outside a scope the memoization is a pass-through and behavior is unchanged.
    private List<ScholardexAuthorView> findAuthorsByIds(Collection<String> authorIds) {
        String key = authorIds == null ? "" : authorIds.stream().sorted().collect(Collectors.joining(","));
        return reportingLookupMemoization.getOrCompute("userReport", "authorsByIds", key,
                () -> scholardexProjectionReadService.findAuthorsByIdIn(authorIds));
    }

    private List<ScholardexPublicationView> findPublicationsByAuthorIds(Collection<String> authorIds) {
        return scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
    }

    private List<ScholardexPublicationView> findEffectivePublicationsForUser(String userEmail) {
        return effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail);
    }

    private List<ScholardexPublicationView> findConfirmedPublicationsForScoring(String userEmail) {
        return reportingLookupMemoization.getOrCompute("userReport", "confirmedPublications", userEmail,
                () -> effectiveAuthorshipReadService.findConfirmedPublicationsForScoring(userEmail));
    }

    public boolean hasConfirmedPublicationsForScoring(String userEmail) {
        return effectiveAuthorshipReadService.hasConfirmedPublicationsForScoring(userEmail);
    }

    public boolean reportUsesPublicationScoring(String reportId) {
        return individualReportRepository.findById(reportId)
                .map(this::reportUsesPublicationScoring)
                .orElse(false);
    }

    private boolean reportUsesPublicationScoring(IndividualReport report) {
        if (report == null || report.getIndicators() == null) {
            return false;
        }
        return report.getIndicators().stream()
                .filter(Objects::nonNull)
                .anyMatch(this::requiresPublicationScoring);
    }

    private boolean requiresPublicationScoring(Indicator indicator) {
        return indicator != null && indicator.isPublicationOutput()
                || indicator != null && indicator.isCitationsOutput();
    }

    private Optional<User> findUserWithProfile(String userEmail) {
        return reportingLookupMemoization.getOrCompute("userReport", "userWithProfile", userEmail,
                () -> userService.getUserByEmail(userEmail)
                        .filter(user -> user.getResearcherProfile() != null));
    }

    private Optional<IndividualReport> findReport(String reportId) {
        return individualReportRepository.findById(reportId);
    }

    private List<ScholardexPublicationView> applyAffiliationFilter(
            IndividualReport report,
            List<ScholardexPublicationView> publications) {
        if (report.getIndividualAffiliation() == null
                || "ANY".equals(report.getIndividualAffiliation().getName())) {
            return publications;
        }
        return publications.stream()
                .filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream()
                        .anyMatch(aff -> p.getAffiliations().contains(aff.getAfid())))
                .toList();
    }

    private CitationPrecomputeBundle precomputeCitationData(
            List<Indicator> indicators,
            List<ScholardexPublicationView> publications,
            boolean enabled) {
        if (!enabled) {
            return new CitationPrecomputeBundle(
                    ReportScopedIndicatorScoringSupport.CitationContext.empty(),
                    Map.of()
            );
        }
        // The citation context (the cited->citing publication graph) is indicator-independent and Mongo-heavy;
        // a Refresh All rebuilds it once for the report pass and once per citation indicator's run detail over
        // the same publication set — memoize it in the refresh scope. Read-only after construction.
        String contextKey = publications.stream()
                .map(ScholardexPublicationView::getId).sorted().collect(Collectors.joining(","));
        ReportScopedIndicatorScoringSupport.CitationContext citationContext =
                reportingLookupMemoization.getOrCompute("userReport", "citationContext", contextKey,
                        () -> ReportScopedIndicatorScoringSupport.prepareCitationContext(
                                publications, scholardexProjectionReadService));
        // Same treatment for the per-indicator base scores over the citing publications (the heaviest citation
        // step): score each citation indicator's citing set once per refresh. The maps are read-only downstream.
        int referenceYear = ro.uvt.pokedex.core.service.reporting.ScoringReferenceYearContext.currentOrCurrentYear();
        Map<Indicator, Map<String, Score>> baseScoresByIndicator = new HashMap<>();
        for (Indicator indicator : indicators) {
            if (indicator == null || !indicator.isCitationsOutput()) {
                continue;
            }
            Map<String, Score> baseScores = reportingLookupMemoization.getOrCompute(
                    "userReport", "citationBaseScores",
                    indicator.getId() + "|y=" + referenceYear + "|" + contextKey,
                    () -> ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(
                                    List.of(indicator), citationContext, scientificProductionService)
                            .getOrDefault(indicator, Map.of()));
            baseScoresByIndicator.put(indicator, baseScores);
        }
        return new CitationPrecomputeBundle(citationContext, baseScoresByIndicator);
    }

    private record CitationPrecomputeBundle(
            ReportScopedIndicatorScoringSupport.CitationContext citationContext,
            Map<Indicator, Map<String, Score>> baseScoresByIndicator
    ) {
    }

    private List<ScholardexPublicationView> findPublicationsByIds(Collection<String> publicationIds) {
        return scholardexProjectionReadService.findAllPublicationsByIdIn(publicationIds);
    }

    private List<ScholardexCitationView> findCitationsByCitedIds(Collection<String> publicationIds) {
        return scholardexProjectionReadService.findAllCitationsByCitedIdIn(publicationIds);
    }

    private List<ScholardexCitationView> findCitationsByCitedId(String publicationId) {
        return scholardexProjectionReadService.findAllCitationsByCitedId(publicationId);
    }

    private List<ScholardexForumView> findForumsByIds(Collection<String> forumIds) {
        return scholardexProjectionReadService.findForumsByIdIn(forumIds);
    }

}
