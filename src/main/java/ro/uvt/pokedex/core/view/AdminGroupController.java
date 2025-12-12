package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.*;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.GroupRepository;
import ro.uvt.pokedex.core.repository.ResearcherRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.importing.GroupService;
import ro.uvt.pokedex.core.service.reporting.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class AdminGroupController {

    private final GroupRepository groupRepository;
    private final DomainRepository domainRepository;
    private final InstitutionRepository institutionRepository;
    private final ResearcherRepository researcherRepository;
    private final IndividualReportRepository individualReportRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityReportingService activityReportingService;
    private final ScientificProductionService scientificProductionService;
    String Country = "Romania";
    private final GroupService groupService;

    private final ScopusPublicationRepository scopusPublicationRepository;

    private final ScopusAuthorRepository scopusAuthorRepository;

    private final ScopusForumRepository scopusForumRepository;
    private final ScopusCitationRepository scopusCitationRepository;
    private final CNFISScoringService cnfiSScoringService;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final WoSExtractor woSExtractor;
    private final CNFISReportExportService exportService;

    @GetMapping
    public String listGroups(Model model) {
        List<Group> groups = groupRepository.findAll();
        model.addAttribute("groups", groups);
        model.addAttribute("allDomains", domainRepository.findAll());
        model.addAttribute("affiliations", institutionRepository.findAll());
        model.addAttribute("allResearchers", researcherRepository.findAll());
        model.addAttribute("group", new Group());
        return "admin/groups";
    }

    @PostMapping("/create")
    public String createGroup(@ModelAttribute Group group, RedirectAttributes redirectAttributes) {
        groupRepository.save(group);
        redirectAttributes.addFlashAttribute("successMessage", "Group created successfully.");
        return "redirect:/admin/groups";
    }

    @GetMapping("/edit/{id}")
    public String editGroup(@PathVariable String id, Model model) {
        Group group = groupRepository.findById(id).orElse(null);
        model.addAttribute("group", group);
        model.addAttribute("domains", domainRepository.findAll());
        model.addAttribute("affiliations", institutionRepository.findAll());
        model.addAttribute("allResearchers", researcherRepository.findAll());
        return "admin/edit-group";
    }

    @GetMapping("/{id}/publications")
    public String seeGroupPublications(@PathVariable String id, Model model) {
        Group group = groupRepository.findById(id).orElse(null);
        if(group == null)
            return "redirect:/admin/groups";

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> authorIds = new ArrayList<>();
        for (Researcher researcher : researchers) {
             authorIds.addAll(researcher.getScopusId());
        }
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });
        List<Author> byIdIn = scopusAuthorRepository.findByIdIn(authorKeys);
        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> {
            authorMap.put(a.getId(), a);
        });
        model.addAttribute("authorMap", authorMap);
        Map<String, Forum> forumMap = new HashMap<>();
        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
        forums.forEach(f -> {
            forumMap.put(f.getId(), f);
        });
        Map<Integer, List<Publication>> publicationsByYear = publications.stream()
                .collect(Collectors.groupingBy(p -> Integer.parseInt(p.getCoverDate().substring(0,4)), TreeMap::new, Collectors.toList()));

        Map<Integer, Long> publicationsCountByYear = publications.stream()
                .collect(Collectors.groupingBy(p -> Integer.parseInt(p.getCoverDate().substring(0,4)), TreeMap::new, Collectors.counting()));


        model.addAttribute("publicationsByYear", publicationsByYear);
        model.addAttribute("publicationsCountByYear", publicationsCountByYear);

        List<IndividualReport> all = individualReportRepository.findAll();

        model.addAttribute("individualReports", all);

        model.addAttribute("forumMap", forumMap);
        model.addAttribute("group", group);
        model.addAttribute("publications", publications);
        return "admin/group-publications";
    }

    @GetMapping("{gid}/reports/view/{id}")
    public String viewIndividualReport(Model model, Authentication authentication, @PathVariable("gid") String gid, @PathVariable("id") String id) {

        long start = System.currentTimeMillis();

        Group group = groupRepository.findById(gid).orElse(null);
        if(group == null)
            return "redirect:/admin/groups";

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));

        Optional<IndividualReport> reportOpt = individualReportRepository.findById(id);

        if (reportOpt.isEmpty()) {
            return "redirect:/admin/groups";
        }
        IndividualReport report = reportOpt.get();
        model.addAttribute("report", report);


        model.addAttribute("group", group);
        Map<String, Map<Integer, Double>> researcherScores = new HashMap<>();
        for(Researcher researcher : researchers) {


            // Fetch all authors associated with the researcher
            List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
            if (authors.isEmpty()) {
                continue; // or some appropriate error handling
            }

            // Fetch all publications for these authors
            List<String> authorIds = authors.stream().map(Author::getId).toList();
            List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
            if (!"ANY".equals(report.getIndividualAffiliation().getName())) {
                publications = publications.stream().filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream().anyMatch(aff -> p.getAffiliations().contains(aff.getAfid()))).collect(Collectors.toList());
            }

            // Fetch data for each indicator
            List<Indicator> indicators = report.getIndicators();

            Map<Indicator, Double> indicatorScores = new HashMap<>();
            double totalScore = 0;

            for (Indicator indicator : indicators) {
                long l = System.currentTimeMillis();
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
                totalScore += indicatorScore;
                System.out.println("Computed Indicator in: " + (System.currentTimeMillis() - l) + " ms");
            }
            Map<Integer, Double> criterionScores = new HashMap<>();
            for (int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion criterion = report.getCriteria().get(i);
                double criterionScore = 0;
                System.out.println(i);
                for (Integer in : criterion.getIndicatorIndices()) {
                    System.out.println("Indicator index: " + in);
                    Indicator ind = report.getIndicators().get(in);
                    if (indicatorScores.containsKey(ind)) {
                        criterionScore += indicatorScores.get(ind);
                    }
                }
                criterionScores.put(i, criterionScore);
            }
            researcherScores.put(researcher.getId(), criterionScores);
        }

        //create a map<criteria, map<position, threshold>> for the report criteria
        Map<Integer, Map<Position, Double>> criteriaThresholds = new HashMap<>();
        for (int i = 0; i < report.getCriteria().size(); i++) {
            AbstractReport.Criterion criterion = report.getCriteria().get(i);
            Map<Position, Double> thresholds = new HashMap<>();
            for (AbstractReport.Threshold threshold : criterion.getThresholds()) {
                thresholds.put(threshold.getPosition(), threshold.getValue());
            }
            criteriaThresholds.put(i, thresholds);
        }


        model.addAttribute("researchers", researchers);
        model.addAttribute("researcherScores", researcherScores);
        model.addAttribute("criteriaThresholds", criteriaThresholds);

        System.out.println("Computed Report in: " + (System.currentTimeMillis() - start) + " ms");
        return "admin/group-individualReport-view";
    }


    public double calculatePublicationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch( a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        return scores.get("total").getAuthorScore();
    }

    public double calculateCitationScore(Indicator indicator, List<Author> authors, List<Publication> publications) {
        double total = 0;
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);

        long l = System.currentTimeMillis();
        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scopusCitationRepository.findAllByCitedIdIn(pubIds);
        System.out.println((System.currentTimeMillis() - l) + " ms Retrieved citation ids list");
        l = System.currentTimeMillis();
        List<String> citationIds = allCitations.stream().map(Citation::getCitingId).toList();
        List<Publication> allCitationsPub = scopusPublicationRepository.findAllByIdIn(citationIds);
        Map<String, List<Publication>> pubCitationsMap = allCitationsPub.stream().collect(Collectors.groupingBy(Publication::getId));
        System.out.println((System.currentTimeMillis() - l) + " ms Retrieved full publications citation list");
        Map<String, Map<String, Score>> scores = new HashMap<>();
        for (Publication pub : publications) {

//            List<Citation> allByCited = scopusCitationRepository.findAllByCited(pub);
            List<Publication> citations = new ArrayList<>();

            l = System.currentTimeMillis();
            for (Citation cit : allCitations) {
                if(cit.getCitedId().equals(pub.getId())) {
                    Publication citing = pubCitationsMap.get(cit.getCitingId()).get(0);
                    if (excludeSelf && authors.stream().anyMatch(a -> citing.getAuthors().contains(a.getId()))) {
                        continue;
                    }
                    citations.add(citing);
                }
            }
//            System.out.println((System.currentTimeMillis() - l) + " ms Computed Citation list (" + pub.getTitle() +") Citations in: ");
            l = System.currentTimeMillis();
            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(pub, citations, indicator);
            scores.put(pub.getTitle(), citScores);

//            System.out.println((System.currentTimeMillis() - l) + " ms Computed Publication (" + pub.getTitle() +") Citations in: ");
        }
        UserViewController.applyFinalSelector(indicator, scores);
        total = scores.values().stream().map(value -> {
            double t = 0.0;
            value.remove("total");
            for (Score score : value.values()) {
                t += score.getAuthorScore();
            }
            return t;
        }).reduce(0.0, Double::sum);

        return total;
    }

    @GetMapping("/{id}/publications/export")
    @ResponseBody
    public void exportIndicatorResults(@PathVariable("id") String id, Authentication authentication, HttpServletResponse response) throws IOException {
        Group group = groupRepository.findById(id).orElse(null);
        if (group == null)
            return;

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> authorIds = new ArrayList<>();
        for (Researcher researcher : researchers) {
            authorIds.addAll(researcher.getScopusId());
        }
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
        });

        List<Author> authors = scopusAuthorRepository.findByIdIn(authorKeys);
        Map<String, Author> authorMap = authors.stream().collect(Collectors.toMap(Author::getId, a -> a));
        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));

        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"group_publications.csv\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("DOI,Title,Authors,Affiliated Authors,Forum,Year,Volume,Page Range");

            for (Publication publication : publications) {
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String authorsNames = publication.getAuthors().stream()
                        .map(authorMap::get)
                        .filter(Objects::nonNull)
                        .map(Author::getName)
                        .collect(Collectors.joining(";"));
                String affiliatedAuthors = publication.getAuthors().stream()
                        .map(authorMap::get)
                        .filter(a -> authorIds.contains(a.getId()))
                        .map(Author::getName)
                        .collect(Collectors.joining(";"));
                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
                String year = publication.getCoverDate() != null ? publication.getCoverDate().split("-")[0] : "";
                String volume = publication.getVolume() != null ? publication.getVolume() : "";
                if(publication.getIssueIdentifier() != null && !publication.getIssueIdentifier().equals("null")){
                    volume += "(" + publication.getIssueIdentifier() + ")";
                }
                String pageRange = publication.getPageRange() != null ? publication.getPageRange() : "";

                writer.printf("%s,\"%s\",\"%s\",\"%s\",\"%s\",%s,%s,%s%n", doi, title, authorsNames, affiliatedAuthors, forumName, year, volume, pageRange);
            }
        }
    }

//    public void copyRow(Row sourceRow, Row destinationRow) {
//        // Copy row height and style if needed
//        destinationRow.setHeight(sourceRow.getHeight());
//
//        for (int i = sourceRow.getFirstCellNum(); i < sourceRow.getLastCellNum(); i++) {
//            Cell sourceCell = sourceRow.getCell(i);
//            Cell destCell = destinationRow.createCell(i);
//            if (sourceCell != null) {
//                // Copy cell style
//                destCell.setCellStyle(sourceCell.getCellStyle());
//
//                // Copy cell type and value
//                switch (sourceCell.getCellType()) {
//                    case STRING:
//                        destCell.setCellValue(sourceCell.getStringCellValue());
//                        break;
//                    case NUMERIC:
//                        destCell.setCellValue(sourceCell.getNumericCellValue());
//                        break;
//                    case BOOLEAN:
//                        destCell.setCellValue(sourceCell.getBooleanCellValue());
//                        break;
//                    case FORMULA:
//                        destCell.setCellFormula(sourceCell.getCellFormula());
//                        break;
//                    case BLANK:
//                        destCell.setBlank();
//                        break;
//                    case ERROR:
//                        destCell.setCellErrorValue(sourceCell.getErrorCellValue());
//                        break;
//                    default:
//                        break;
//                }
//            }
//        }
//    }


//    @GetMapping("/{id}/publications/exportCNFIS")
//    @ResponseBody
//    public void createCNFISReport(@PathVariable("id") String id, Authentication authentication, HttpServletResponse response,
//                                  @RequestParam(name = "start", defaultValue = "2021") String startYear,
//                                  @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
//        Group group = groupRepository.findById(id).orElse(null);
//        if (group == null)
//            return;
//
//        List<Researcher> researchers = group.getResearchers();
//        researchers.sort(Comparator.comparing(Researcher::getName));
//        List<String> authorIds = new ArrayList<>();
//        for (Researcher researcher : researchers) {
//            authorIds.addAll(researcher.getScopusId());
//        }
//        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
//        int start = Integer.parseInt(startYear);
//        int end = Integer.parseInt(endYear);
//        publications = publications.stream().filter( publication -> {
//            int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
//            return pubYear >= start && pubYear <= end;
//        }).toList();
//        List<CNFISReport> cnfisReports = new ArrayList<>();
//        Domain domain = domainRepository.findByName("ALL").orElse(null);
//        for (Publication publication : publications) {
//            CNFISReport cnfisReport = cnfiSScoringService.getReport(publication, domain);
//            cnfisReports.add(cnfisReport);
//        }
//
//        Set<String> authorKeys = new HashSet<>();
//        Set<String> forumKeys = new HashSet<>();
//        publications.forEach(p -> {
//            authorKeys.addAll(p.getAuthors());
//            forumKeys.add(p.getForum());
//        });
//
//        List<Author> authors = scopusAuthorRepository.findByIdIn(authorKeys);
//        Map<String, Author> authorMap = authors.stream().collect(Collectors.toMap(Author::getId, a -> a));
//        List<Forum> forums = scopusForumRepository.findBySourceIdIn(forumKeys);
//        Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getSourceId, f -> f));
//
//
//
//        // Load the template Excel file
//
//        try (FileInputStream resource = new FileInputStream("data/templates/Anexa5-Fisa_articole_brevete.xlsx");
//             Workbook workbook = new XSSFWorkbook(resource)) {
//            Sheet sheet = workbook.getSheetAt(0);
//
//            int rowNum = 16; // Start populating from row 15
//            int i = 0;
//            Row sample = sheet.getRow(15);
//            int sampleRowNum = 15;
//            for (Publication publication : publications) {
//                Row row;
//
//                row = copyRow(workbook, sheet, sampleRowNum, rowNum);
//
//
//                String year = publication.getCoverDate() != null ? publication.getCoverDate().split("-")[0] : "";
//                String title = publication.getTitle() != null ? publication.getTitle() : "";
//                String doi = publication.getDoi() != null ? publication.getDoi() : "";
////                String wosCode = publication.getWosId() != null ? publication.getWosId() : "";
//                String wosCode = "";
////                String brevetCode = publication.getBrevetCode() != null ? publication.getBrevetCode() : "";
//                String brevetCode = "";
//                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
//                String issnOnline = forumMap.getOrDefault(publication.getForum(), new Forum()).getEIssn();
//                if(issnOnline.contains("null"))
//                    issnOnline = "";
//                String issnPrint = forumMap.getOrDefault(publication.getForum(), new Forum()).getIssn();
//                if(issnPrint.contains("null"))
//                    issnPrint = "";
////                String isbn = publication.getIsbn() != null ? publication.getIsbn() : "";
//                String isbn = "";
//
////                String journalCategory = publication.getJournalCategory() != null ? publication.getJournalCategory() : "";
////                String articleCategory = publication.getArticleCategory() != null ? publication.getArticleCategory() : "";
////                String patentCategory = publication.getPatentCategory() != null ? publication.getPatentCategory() : "";
//                int totalAuthors = publication.getAuthors().size();
//                //TODO update this formula
//                List<Institution> uvt = institutionRepository.findByNameIgnoreCase("UVT");
//                List<String> univAffil = uvt.getFirst().getScopusAffiliations().stream().map(Affiliation::getAfid).toList();
//                long universityAuthors = publication.getAffiliations().stream().filter(univAffil::equals).count();
//
//
//                System.out.println("Row: " + rowNum + " - Title: " + title);
//                row.getCell(1).setCellValue(year);
//                row.getCell(2).setCellValue(title);
//                row.getCell(3).setCellValue(doi);
//                row.getCell(4).setCellValue(wosCode);
//                row.getCell(5).setCellValue(brevetCode);
//                row.getCell(6).setCellValue(forumName);
//                row.getCell(7).setCellValue(issnOnline);
//                row.getCell(8).setCellValue(issnPrint);
//                row.getCell(9).setCellValue(isbn);
//                CNFISReport cnfisReport = cnfisReports.get(i);
//                if (cnfisReport.isIsiRosu()){
//                    row.getCell(12).setCellValue(1);
//                } else if (cnfisReport.isIsiGalben()) {
//                    row.getCell(13).setCellValue(1);
//                } else if (cnfisReport.isIsiAlb()) {
//                    row.getCell(14).setCellValue(1);
//                } else if (cnfisReport.isIsiArtsHumanities()) {
//                    row.getCell(15).setCellValue(1);
//                } else if(cnfisReport.isIsiEmergingSourcesCitationIndex()){
//                    row.getCell(16).setCellValue(1);
//                } else if (cnfisReport.isErihPlus()) {
//                    row.getCell(17).setCellValue(1);
//                } else if (cnfisReport.isIsiProceedings()) {
//                    row.getCell(18).setCellValue(1);
//                } else if (cnfisReport.isIeeeProceedings()) {
//                    row.getCell(19).setCellValue(1);
//                }
//                row.getCell(23).setCellValue(totalAuthors);
//                row.getCell(24).setCellValue(universityAuthors);
//                i++;
//            }
//
//            workbook.setForceFormulaRecalculation(true);
//
//            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
//            response.setHeader("Content-Disposition", "attachment; filename=\"Anexa5-Fisa_articole_brevete.xlsx\"");
//            workbook.write(response.getOutputStream());
//        }
//    }

    // File: src/main/java/ro/uvt/pokedex/core/view/AdminGroupController.java
    @GetMapping("/{id}/publications/exportCNFIS2025")
    @ResponseBody
    public void createCNFISReport2025(@PathVariable("id") String id,
                                      HttpServletResponse response,
                                      @RequestParam(name = "start", defaultValue = "2021") String startYear,
                                      @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
        Group group = groupRepository.findById(id).orElse(null);
        if (group == null)
            return;

        List<Researcher> researchers = group.getResearchers();
        researchers.sort(Comparator.comparing(Researcher::getName));
        List<String> authorIds = new ArrayList<>();
        for (Researcher researcher : researchers) {
            authorIds.addAll(researcher.getScopusId());
        }
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
        int start = Integer.parseInt(startYear);
        int end = Integer.parseInt(endYear);
        publications = publications.stream().filter(publication -> {
            int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
            return pubYear >= start && pubYear <= end;
        }).toList();

        List<CNFISReport2025> cnfisReports = new ArrayList<>();
        Domain domain = domainRepository.findByName("ALL").orElse(null);
        for (Publication publication : publications) {
            publication = woSExtractor.findPublicationWosId(publication);
            scopusPublicationRepository.save(publication);
            CNFISReport2025 cnfisReport = cnfiSScoringService2025.getReport(publication, domain);
            cnfisReports.add(cnfisReport);
        }

        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));

        exportService.exportCNFISReport2025(publications, cnfisReports, forumMap, authorIds, response, true);
    }

    @GetMapping("/{id}/publications/exportAllReports")
    @ResponseBody
    public void exportAllReports(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        Group group = groupRepository.findById(id).orElse(null);
        if (group == null) {
            return;
        }
        List<Researcher> researchers = group.getResearchers();
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=group_reports.zip");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (Researcher researcher : researchers) {
                // Build individual publications list for the researcher.
                List<String> authorIds = researcher.getScopusId();
                List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
                // Apply any filtering and processing needed.
                // For demonstration, assume start/end years are constant.
                publications = publications.stream().filter(publication -> {
                    int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
                    return pubYear >= 2021 && pubYear <= 2024;
                }).toList();

                List<CNFISReport2025> cnfisReports = new ArrayList<>();
                Domain domain = domainRepository.findByName("ALL").orElse(null);
                for (Publication publication : publications) {
                    publication = woSExtractor.findPublicationWosId(publication);
                    scopusPublicationRepository.save(publication);
                    CNFISReport2025 cnfisReport = cnfiSScoringService2025.getReport(publication, domain);
                    cnfisReports.add(cnfisReport);
                }
                Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
                List<Forum> forums = scopusForumRepository.findByIdIn(forumKeys);
                Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));

                byte[] reportBytes = exportService.generateCNFISReportWorkbook(publications, cnfisReports, forumMap, authorIds, false);
                // Use a unique filename per individual report.
                String entryName = researcher.getLastName() + "_" + researcher.getFirstName().charAt(0) + "_AB.xlsx";
                zos.putNextEntry(new ZipEntry(entryName));
                try (ByteArrayInputStream bis = new ByteArrayInputStream(reportBytes)) {
                    bis.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }





    @PostMapping("/update")
    public String updateGroup(@ModelAttribute Group group, RedirectAttributes redirectAttributes) {
        groupRepository.save(group);
        redirectAttributes.addFlashAttribute("successMessage", "Group updated successfully.");
        return "redirect:/admin/groups";
    }

    @GetMapping("/delete/{id}")
    public String deleteGroup(@PathVariable String id, RedirectAttributes redirectAttributes) {
        groupRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "Group deleted successfully.");
        return "redirect:/admin/groups";
    }

    @PostMapping("/import")
    public String importGroups(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a CSV file to upload.");
            return "redirect:/admin/groups";
        }

        try {
            groupService.importGroupsFromCsv(file);
            redirectAttributes.addFlashAttribute("successMessage", "Groups imported successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "An error occurred while importing the groups: " + e.getMessage());
            e.printStackTrace();
        }

        return "redirect:/admin/groups";
    }
}

