package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
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
import ro.uvt.pokedex.core.service.ResearcherService;
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
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private final ResearcherService researcherService;
    private final IndicatorRepository indicatorRepository;
    private final IndividualReportRepository individualReportRepository;
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

    public UserIndicatorsViewModel buildIndicatorsView(String userEmail) {
        // userEmail kept in signature to lock facade contract for later permission-aware extensions.
        return new UserIndicatorsViewModel(indicatorRepository.findAll());
    }

    public UserReportsListViewModel buildIndividualReportsListView(String userEmail) {
        // userEmail kept in signature to lock facade contract for future permission-aware filtering.
        return new UserReportsListViewModel(individualReportRepository.findAll());
    }

    public Optional<Indicator> findIndicatorById(String indicatorId) {
        return indicatorRepository.findById(indicatorId);
    }

    public Optional<IndividualReport> findIndividualReportById(String reportId) {
        return individualReportRepository.findById(reportId);
    }

    public Optional<UserIndicatorWorkbookExportViewModel> buildIndicatorWorkbookExport(String userEmail, String indicatorId) throws IOException {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        Optional<Researcher> researcherOpt = researcherService.findResearcherById(userOpt.get().getResearcherId());
        Optional<Indicator> indicatorOpt = indicatorRepository.findById(indicatorId);
        if (researcherOpt.isEmpty() || indicatorOpt.isEmpty()) {
            return Optional.empty();
        }

        Researcher researcher = researcherOpt.get();
        Indicator indicator = indicatorOpt.get();
        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        if (authors.isEmpty()) {
            return Optional.empty();
        }

        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findPublicationsByAuthorIds(authorIds);
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        Map<String, ScholardexForumView> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                handlePublicationsWorkbook(workbook, indicator, publications, forumMap);
            } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                handleCitationsWorkbook(workbook, indicator, authors, publications, forumMap);
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

        Optional<Researcher> researcherOpt = researcherService.findResearcherById(userOpt.get().getResearcherId());
        if (researcherOpt.isEmpty()) {
            return UserWorkbookExportResult.notFound();
        }

        List<String> lookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(researcherOpt.get());
        List<String> authorIds = findAuthorsByIds(lookupKeys).stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findPublicationsByAuthorIds(authorIds);
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

        Optional<Researcher> researcherOpt = researcherService.findResearcherById(userOpt.get().getResearcherId());
        if (researcherOpt.isEmpty()) {
            return UserWorkbookExportResult.notFound();
        }

        Researcher researcher = researcherOpt.get();
        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        if (authors.isEmpty()) {
            return UserWorkbookExportResult.notFound();
        }

        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findPublicationsByAuthorIds(authorIds);
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
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        User user = userOpt.get();
        String researcherId = user.getResearcherId();
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        Optional<Indicator> indicatorOpt = indicatorRepository.findById(indicatorId);

        if (indicatorOpt.isEmpty() || researcherOpt.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", Map.of());
        }

        Indicator indicator = indicatorOpt.get();
        Researcher researcher = researcherOpt.get();

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("indicator", indicator);

        if (indicator.getOutputType().toString().contains("ACTIVIT")) {
            List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcherId);
            activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
            return handleActivities(indicator, activities, attrs);
        }

        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        if (authors.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", attrs);
        }

        List<ScholardexPublicationView> publications = findPublicationsByAuthorIds(authorIds);
        if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
            return handlePublications(indicator, authors, publications, attrs);
        }
        if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
            return handleCitations(indicator, authors, publications, attrs);
        }

        return new UserIndicatorApplyViewModel("user/indicators", attrs);
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
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            return Optional.empty();
        }

        IndividualReport report = reportOpt.get();
        Researcher researcher = researcherService.findResearcherById(userOpt.get().getResearcherId()).orElse(null);
        if (researcher == null) {
            return Optional.empty();
        }

        List<ScholardexAuthorView> authors = findAuthorsByIds(researcherAuthorLookupService.resolveAuthorLookupKeys(researcher));
        if (authors.isEmpty()) {
            return Optional.empty();
        }

        List<String> authorIds = authors.stream().map(ScholardexAuthorView::getId).toList();
        List<ScholardexPublicationView> publications = findPublicationsByAuthorIds(authorIds);
        if (report.getIndividualAffiliation() != null
                && !"ANY".equals(report.getIndividualAffiliation().getName())) {
            publications = publications.stream()
                    .filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream()
                            .anyMatch(aff -> p.getAffiliations().contains(aff.getAfid())))
                    .collect(Collectors.toList());
        }

        List<Indicator> indicators = report.getIndicators() == null ? List.of() : report.getIndicators();
        Map<Indicator, Double> indicatorScores = new HashMap<>();
        Map<String, Double> indicatorScoresByIndicatorId = new HashMap<>();
        Map<String, IndicatorApplyResultDto> reportScopedIndicatorResultsByIndicatorId = new HashMap<>();

        boolean hasCitationIndicators = indicators.stream()
                .filter(Objects::nonNull)
                .anyMatch(indicator -> Indicator.Type.CITATIONS.equals(indicator.getOutputType())
                        || Indicator.Type.CITATIONS_EXCLUDE_SELF.equals(indicator.getOutputType()));
        List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcher.getId());
        Set<String> researcherAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        ReportScopedIndicatorScoringSupport.CitationContext citationContext = hasCitationIndicators
                ? ReportScopedIndicatorScoringSupport.prepareCitationContext(publications, scholardexProjectionReadService)
                : ReportScopedIndicatorScoringSupport.CitationContext.empty();
        Map<Indicator, Map<String, Score>> citationBaseScoresByIndicator = hasCitationIndicators
                ? ReportScopedIndicatorScoringSupport.precomputeCitationBaseScoresByIndicator(indicators, citationContext, scientificProductionService)
                : Map.of();

        for (Indicator indicator : indicators) {
            if (indicator == null || indicator.getId() == null) {
                continue;
            }

            double indicatorScore = 0.0;
            if (indicator.getOutputType().toString().contains("ACTIVIT")) {
                List<ActivityInstance> filteredActivities = activities.stream()
                        .filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName()))
                        .toList();
                indicatorScore = activityReportingService.calculateActivityScores(filteredActivities, indicator)
                        .get("total")
                        .getAuthorScore();
            }
            if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                indicatorScore = calculatePublicationScore(indicator, authors, publications);
            } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS)
                    || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                indicatorScore = ReportScopedIndicatorScoringSupport.calculateCitationScore(
                                indicator,
                                publications,
                                researcherAuthorIds,
                                citationContext,
                                citationBaseScoresByIndicator.getOrDefault(indicator, Map.of()),
                                scientificProductionService)
                        .score();
            }

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

        Map<Integer, Double> criterionScores = new HashMap<>();
        List<AbstractReport.Criterion> criteria = report.getCriteria() == null ? List.of() : report.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            AbstractReport.Criterion criterion = criteria.get(i);
            double criterionScore = 0;
            if (criterion.getIndicatorIndices() != null) {
                for (Integer indicatorIndex : criterion.getIndicatorIndices()) {
                    if (indicatorIndex == null || indicatorIndex < 0 || indicatorIndex >= indicators.size()) {
                        continue;
                    }
                    Indicator indicator = indicators.get(indicatorIndex);
                    if (indicator != null && indicator.getId() != null) {
                        criterionScore += indicatorScoresByIndicatorId.getOrDefault(indicator.getId(), 0.0);
                    }
                }
            }
            criterionScores.put(i, criterionScore);
        }

        return Optional.of(new ReportScopedIndividualReportComputation(
                indicatorScores,
                indicatorScoresByIndicatorId,
                criterionScores,
                reportScopedIndicatorResultsByIndicatorId
        ));
    }

    private UserIndicatorApplyViewModel handlePublications(Indicator indicator, List<ScholardexAuthorView> authors, List<ScholardexPublicationView> publications, Map<String, Object> attrs) {
        List<ScholardexPublicationView> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().getFirst()))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().getFirst()))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(
                filteredPublications.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                indicator
        );
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
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
        return ReportingComputationSupport.calculatePublicationScore(indicator, authors, publications, scientificProductionService);
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
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(
                publications.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                indicator
        );
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
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
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

    private List<ScholardexAuthorView> findAuthorsByIds(Collection<String> authorIds) {
        return scholardexProjectionReadService.findAuthorsByIdIn(authorIds);
    }

    private List<ScholardexPublicationView> findPublicationsByAuthorIds(Collection<String> authorIds) {
        return scholardexProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
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
