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
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndividualReportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserIndicatorWorkbookExportViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.application.model.UserWorkbookExportResult;
import ro.uvt.pokedex.core.service.reporting.ActivityReportingService;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.WoSExtractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserReportFacade {
    private static final String WOS_EXTRACTOR_SOURCE = "WOSEXTRACTOR";
    private static final String LINKER_VERSION = "h17.10";

    private final UserService userService;
    private final ResearcherService researcherService;
    private final IndicatorRepository indicatorRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ScopusProjectionReadService scopusProjectionReadService;
    private final DomainRepository domainRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final WoSExtractor woSExtractor;
    private final CNFISReportExportService exportService;
    private final CacheService cacheService;
    private final PublicationEnrichmentLinkerService publicationEnrichmentLinkerService;

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
        List<Author> authors = findAuthorsByIds(researcher.getScopusId());
        if (authors.isEmpty()) {
            return Optional.empty();
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        List<Publication> publications = findPublicationsByAuthorIds(authorIds);
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        Map<String, Forum> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

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

        List<String> authorIds = new ArrayList<>(researcherOpt.get().getScopusId());
        List<Publication> publications = findPublicationsByAuthorIds(authorIds);
        publications = publications.stream().filter(publication -> {
            return PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                    .map(pubYear -> pubYear >= startYear && pubYear <= endYear)
                    .orElse(false);
        }).toList();

        Domain domain = domainRepository.findByName("ALL").orElse(null);
        List<CNFISReport2025> cnfisReports = new ArrayList<>();
        String linkerRunId = "user-cnfis-" + System.currentTimeMillis();
        for (Publication publication : publications) {
            Publication enrichedPublication = woSExtractor.findPublicationWosId(publication);
            publicationEnrichmentLinkerService.linkWosEnrichment(
                    enrichedPublication,
                    WOS_EXTRACTOR_SOURCE,
                    LINKER_VERSION,
                    linkerRunId
            );
            cnfisReports.add(cnfiSScoringService2025.getReport(enrichedPublication, domain));
        }

        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        Map<String, Forum> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

        byte[] workbookBytes = exportService.generateCNFISReportWorkbook(publications, cnfisReports, forumMap, authorIds, false);
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
        List<Author> authors = findAuthorsByIds(researcher.getScopusId());
        if (authors.isEmpty()) {
            return UserWorkbookExportResult.notFound();
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        List<Publication> publications = findPublicationsByAuthorIds(authorIds);
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        Map<String, Forum> forumMap = findForumsByIds(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

        ClassPathResource resource = new ClassPathResource("/data/templates/Anexa5-Fisa_articole_brevete.xlsx");
        try (Workbook workbook = new XSSFWorkbook(resource.getInputStream()); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowNum = 15;
            for (Publication publication : publications) {
                Row row = sheet.createRow(rowNum++);

                String year = PersistenceYearSupport.extractYearString(publication.getCoverDate(), publication.getId(), log);
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
                String issnOnline = forumMap.getOrDefault(publication.getForum(), new Forum()).getEIssn();
                String issnPrint = forumMap.getOrDefault(publication.getForum(), new Forum()).getIssn();
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

        List<Author> authors = findAuthorsByIds(researcher.getScopusId());
        List<String> authorIds = authors.stream().map(Author::getId).toList();
        if (authors.isEmpty()) {
            return new UserIndicatorApplyViewModel("user/indicators", attrs);
        }

        List<Publication> publications = findPublicationsByAuthorIds(authorIds);
        if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
            return handlePublications(indicator, authors, publications, attrs);
        }
        if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
            return handleCitations(indicator, authors, publications, attrs);
        }

        return new UserIndicatorApplyViewModel("user/indicators", attrs);
    }

    public UserIndividualReportViewModel buildIndividualReportView(String userEmail, String reportId) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return new UserIndividualReportViewModel("redirect:/error", Map.of());
        }
        User currentUser = userOpt.get();

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(reportId);
        Map<String, Object> attrs = new HashMap<>();
        if (reportOpt.isEmpty()) {
            return new UserIndividualReportViewModel(null, attrs);
        }

        IndividualReport report = reportOpt.get();
        attrs.put("report", report);

        Researcher researcher = researcherService.findResearcherById(currentUser.getResearcherId()).orElse(null);
        if (researcher == null) {
            return new UserIndividualReportViewModel("redirect:/error", attrs);
        }

        List<Author> authors = findAuthorsByIds(researcher.getScopusId());
        if (authors.isEmpty()) {
            return new UserIndividualReportViewModel("redirect:/error", attrs);
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        List<Publication> publications = findPublicationsByAuthorIds(authorIds);
        if (!"ANY".equals(report.getIndividualAffiliation().getName())) {
            publications = publications.stream().filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream().anyMatch(aff -> p.getAffiliations().contains(aff.getAfid()))).collect(Collectors.toList());
        }

        List<Indicator> indicators = report.getIndicators();
        Map<Indicator, Double> indicatorScores = new HashMap<>();

        for (Indicator indicator : indicators) {
            double indicatorScore = 0;
            if (indicator.getOutputType().toString().contains("ACTIVIT")) {
                List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcher.getId());
                activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
                indicatorScore = activityReportingService.calculateActivityScores(activities, indicator).get("total").getAuthorScore();
            }
            if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                indicatorScore = calculatePublicationScore(indicator, authors, publications);
            } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                indicatorScore = calculateCitationScore(indicator, authors, publications);
            }

            indicatorScores.put(indicator, indicatorScore);
        }

        Map<Integer, Double> criterionScores = new HashMap<>();
        for (int i = 0; i < report.getCriteria().size(); i++) {
            AbstractReport.Criterion criterion = report.getCriteria().get(i);
            double criterionScore = 0;
            for (Integer in : criterion.getIndicatorIndices()) {
                Indicator ind = report.getIndicators().get(in);
                if (indicatorScores.containsKey(ind)) {
                    criterionScore += indicatorScores.get(ind);
                }
            }
            criterionScores.put(i, criterionScore);
        }

        attrs.put("indicatorScores", indicatorScores);
        attrs.put("criterionScores", criterionScores);

        return new UserIndividualReportViewModel(null, attrs);
    }

    private UserIndicatorApplyViewModel handlePublications(Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Object> attrs) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        attrs.put("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        attrs.put("scores", scores);
        filteredPublications = filteredPublications.stream().filter(p -> scores.containsKey(p.getTitle()) && scores.get(p.getTitle()).getAuthorScore() > 0.0).collect(Collectors.toList());
        attrs.put("publications", filteredPublications);

        Set<String> forumKeys = new HashSet<>();
        filteredPublications.forEach(p -> forumKeys.add(p.getForum()));
        List<Forum> forums = findForumsByIds(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        attrs.put("forumMap", forumMap);
        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());

        return new UserIndicatorApplyViewModel("user/indicators-apply-publications", attrs);
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

        return new UserIndicatorApplyViewModel("user/indicators-apply-activities", attrs);
    }

    private UserIndicatorApplyViewModel handleCitations(Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Object> attrs) {
        AtomicInteger totalCit = new AtomicInteger();
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        Map<String, Map<String, Score>> scores = new HashMap<>();
        Map<String, Publication> citationsMap = new HashMap<>();

        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = findCitationsByCitedIds(pubIds);
        List<String> allCitationsIds = allCitations.stream().map(Citation::getCitingId).collect(Collectors.toList());
        List<Publication> allByEidIn = findPublicationsByIds(allCitationsIds);
        Map<String, List<Publication>> citationsMapRetrieved = allByEidIn.stream().collect(Collectors.groupingBy(Publication::getId));

        Set<String> forumKeys = new HashSet<>();
        for (Publication pub : publications) {
            forumKeys.add(pub.getForum());
            List<Publication> citations = new ArrayList<>();
            for (Citation cit : allCitations) {
                if (cit.getCitedId().equals(pub.getId())) {
                    Publication citing = citationsMapRetrieved.get(cit.getCitingId()).get(0);
                    if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                        continue;
                    }
                    citations.add(citing);
                    citationsMap.put(citing.getTitle(), citing);
                    totalCit.getAndIncrement();
                }
            }

            citations.forEach(c -> forumKeys.add(c.getForum()));
            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(pub, citations, indicator);
            scores.put(pub.getTitle(), citScores);
        }

        applyFinalSelector(indicator, scores);
        double total = scores.values().stream().mapToDouble(s -> s.get("total").getAuthorScore()).sum();

        List<Forum> forums = findForumsByIds(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));
        attrs.put("forumMap", forumMap);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> v.forEach((kk, vv) -> {
            quarterHistogram.putIfAbsent(vv.getQuarter(), 0);
            quarterHistogram.put(vv.getQuarter(), quarterHistogram.get(vv.getQuarter()) + 1);
        }));
        quarterHistogram.remove(null);

        attrs.put("allQuarters", quarterHistogram.keySet());
        attrs.put("allValues", quarterHistogram.values());
        attrs.put("total", String.format("%.2f", total));
        attrs.put("totalCit", totalCit.get());
        scores.remove("total");
        attrs.put("scores", scores);
        attrs.put("publications", publications);
        attrs.put("citationMap", citationsMap);

        return new UserIndicatorApplyViewModel("user/indicators-apply-citations", attrs);
    }

    private double calculatePublicationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        return scores.get("total").getAuthorScore();
    }

    private double calculateCitationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);

        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = findCitationsByCitedIds(pubIds);
        List<String> citationIds = allCitations.stream().map(Citation::getCitingId).toList();
        List<Publication> allCitationsPub = findPublicationsByIds(citationIds);
        Map<String, List<Publication>> pubCitationsMap = allCitationsPub.stream().collect(Collectors.groupingBy(Publication::getId));
        Map<String, Map<String, Score>> scores = new HashMap<>();

        for (Publication pub : publications) {
            List<Publication> citations = new ArrayList<>();
            for (Citation cit : allCitations) {
                if (cit.getCitedId().equals(pub.getId())) {
                    Publication citing = pubCitationsMap.get(cit.getCitingId()).get(0);
                    if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                        continue;
                    }
                    citations.add(citing);
                }
            }
            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(pub, citations, indicator);
            scores.put(pub.getTitle(), citScores);
        }

        applyFinalSelector(indicator, scores);
        return scores.values().stream().map(value -> {
            double t = 0.0;
            value.remove("total");
            for (Score score : value.values()) {
                t += score.getAuthorScore();
            }
            return t;
        }).reduce(0.0, Double::sum);
    }

    private void handlePublicationsWorkbook(Workbook workbook, Indicator indicator, List<Publication> publications, Map<String, Forum> forumMap) {
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(publications, indicator);
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
        for (Publication publication : publications) {
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
            dataRow.createCell(6).setCellValue(scores.get(publication.getTitle()).getCategory());
            dataRow.createCell(7).setCellValue(scores.get(publication.getTitle()).getScore());
            dataRow.createCell(8).setCellValue(scores.get(publication.getTitle()).getAuthorScore());
        }
    }

    private void handleCitationsWorkbook(Workbook workbook, Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Forum> forumMap) {
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
        for (Publication publication : publications) {
            sheet.createRow(++rowIdx);
            List<Citation> citations = findCitationsByCitedId(publication.getId());
            List<String> citingIds = citations.stream().map(Citation::getCitingId).collect(Collectors.toList());
            List<Publication> citingPublications = findPublicationsByIds(citingIds);

            Set<String> forumKeys = citingPublications.stream().map(Publication::getForum).collect(Collectors.toSet());
            Set<String> authorIds = citingPublications.stream().map(Publication::getAuthors).flatMap(Collection::stream).collect(Collectors.toSet());
            List<Forum> forums = findForumsByIds(forumKeys);
            findAuthorsByIds(authorIds);
            Map<String, Forum> forumMap2 = forums.stream().collect(Collectors.toMap(Forum::getId, forum -> forum));
            forumMap.putAll(forumMap2);

            for (Publication citingPublication : citingPublications) {
                if (excludeSelf && authors.stream().anyMatch(author -> citingPublication.getAuthors().contains(author.getId()))) {
                    continue;
                }
                if (forumMap.get(citingPublication.getForum()) == null) {
                    continue;
                }

                Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(publication, Collections.singletonList(citingPublication), indicator);
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
                row.createCell(7).setCellValue(citationScore.getCategory());
                row.createCell(8).setCellValue(citationScore.getScore());
                row.createCell(9).setCellValue(citationScore.getAuthorScore());
            }
        }
    }

    private String[] getAuthorNames(List<String> authorIds, Map<String, Author> authorMap) {
        String[] result = new String[authorIds.size()];
        for (int i = 0; i < authorIds.size(); i++) {
            if (authorMap.containsKey(authorIds.get(i))) {
                result[i] = authorMap.get(authorIds.get(i)).getName();
            }
        }
        return result;
    }

    private void applyFinalSelector(Indicator indicator, Map<String, Map<String, Score>> scores) {
        if (indicator.getSelector() != null && indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
            Map<String, Score> topScores = new HashMap<>();
            scores.forEach((k, v) -> topScores.putAll(v));
            List<String> top10 = topScores.entrySet().stream()
                    .filter(x -> !x.getKey().equals("total"))
                    .sorted(Map.Entry.<String, Score>comparingByValue(Comparator.comparingDouble(Score::getAuthorScore)).reversed())
                    .limit(10)
                    .map(Map.Entry::getKey)
                    .toList();
            boolean[] used = new boolean[top10.size()];
            for (String key : scores.keySet()) {
                Iterator<String> titleIterator = scores.get(key).keySet().iterator();
                while (titleIterator.hasNext()) {
                    String title = titleIterator.next();
                    if (title.equals("total")) {
                        continue;
                    }
                    if (!top10.contains(title) || used[top10.indexOf(title)]) {
                        titleIterator.remove();
                    }
                    if (top10.contains(title)) {
                        used[top10.indexOf(title)] = true;
                    }
                }
                double totalA = 0.0;
                double totalF = 0.0;
                scores.get(key).remove("total");
                for (Score score : scores.get(key).values()) {
                    totalA += score.getAuthorScore();
                    totalF += score.getScore();
                }
                Score score = new Score();
                score.setScore(totalF);
                score.setAuthorScore(totalA);
                scores.get(key).put("total", score);
            }
        }
    }

    private List<Author> findAuthorsByIds(Collection<String> authorIds) {
        return scopusProjectionReadService.findAuthorsByIdIn(authorIds);
    }

    private List<Publication> findPublicationsByAuthorIds(Collection<String> authorIds) {
        return scopusProjectionReadService.findAllPublicationsByAuthorsIn(authorIds);
    }

    private List<Publication> findPublicationsByIds(Collection<String> publicationIds) {
        return scopusProjectionReadService.findAllPublicationsByIdIn(publicationIds);
    }

    private List<Citation> findCitationsByCitedIds(Collection<String> publicationIds) {
        return scopusProjectionReadService.findAllCitationsByCitedIdIn(publicationIds);
    }

    private List<Citation> findCitationsByCitedId(String publicationId) {
        return scopusProjectionReadService.findAllCitationsByCitedId(publicationId);
    }

    private List<Forum> findForumsByIds(Collection<String> forumIds) {
        return scopusProjectionReadService.findForumsByIdIn(forumIds);
    }

}
