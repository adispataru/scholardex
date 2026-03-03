package ro.uvt.pokedex.core.view;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.*;
import ro.uvt.pokedex.core.model.scopus.*;
import ro.uvt.pokedex.core.model.tasks.ScopusCitationsUpdate;
import ro.uvt.pokedex.core.model.tasks.ScopusPublicationUpdate;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusCitationUpdateRepository;
import ro.uvt.pokedex.core.repository.tasks.ScopusPublicationUpdateRepository;
import ro.uvt.pokedex.core.service.*;
import ro.uvt.pokedex.core.service.reporting.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserViewController {

    private final UserService userService;
    private final ResearcherService researcherService;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusCitationRepository scopusCitationRepository;
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final IndicatorRepository criterionRepository;
    private final ScientificProductionService scientificProductionService;
    private final ActivityReportingService activityReportingService;
    private final ScopusForumRepository scopusVenueRepository;
    private final IndividualReportRepository individualReportRepository;
    private final RankingRepository rankingRepository;
    private final DomainRepository domainRepository;
    private final CNFISScoringService2025 cnfiSScoringService2025;
    private final InstitutionRepository institutionRepository;
    private final WoSExtractor woSExtractor;
    // In both AdminGroupController and UserViewController
    private final CNFISReportExportService exportService;
    private final CacheService cacheService;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ScopusPublicationUpdateRepository scopusUpdateTaskRepository;
    private final ScopusCitationUpdateRepository scopusCitationsTaskRepository;


    @GetMapping()
    public String showDashboard(Model model) {

        return "user/dashboard"; // Returns the users.html template
    }

    @GetMapping("/profile")
    public String showProfilePage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();
        if (researcherId == null){
            model.addAttribute("researchProfile", null);
        }else{
            Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
            researcherById.ifPresent(researcher -> model.addAttribute("researchProfile", researcher));
        }
        model.addAttribute("newProfile", new Researcher());
        return "user/profile";
    }

    @GetMapping("/publications")
    public String showPublicationsPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();

//        List<Publication> publications = publicationService.findPublicationsByResearcherIdOrderByYearDesc(researcherId);
        Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
        researcherById.ifPresent(r -> {
            List<Author> byId = scopusAuthorRepository.findByIdIn(r.getScopusId());
            List<ro.uvt.pokedex.core.model.scopus.Publication> publications = new ArrayList<>();
            byId.forEach(a -> {
                 publications.addAll(scopusPublicationRepository.findAllByAuthorsContaining(a.getId()));
            });

            int hIndex = computeHIndex(publications);
            model.addAttribute("publications", publications);
            model.addAttribute("hIndex", hIndex);

            Set<String> authorKeys = new HashSet<>();
            Set<String> forumKeys = new HashSet<>();
            AtomicInteger numCitations = new AtomicInteger();
            publications.forEach(p -> {
                authorKeys.addAll(p.getAuthors());
                forumKeys.add(p.getForum());
                numCitations.addAndGet(p.getCitedbyCount());
            });
            List<Author> byIdIn = scopusAuthorRepository.findByIdIn(authorKeys);
            Map<String, Author> authorMap = new HashMap<>();
            byIdIn.forEach(a -> {
                authorMap.put(a.getId(), a);
            });
            model.addAttribute("authorMap", authorMap);
            Map<String, Forum> forumMap = new HashMap<>();
            List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
            forums.forEach(f -> {
                forumMap.put(f.getId(), f);
            });
            model.addAttribute("forumMap", forumMap);
            model.addAttribute("numCitations", numCitations.get());

        });

        //adjust number of authors shown on page.

        model.addAttribute("user", currentUser);
        return "user/publications";
    }

    @GetMapping("/publications/scopus_tasks")
    public String showScopusTasksPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        String researcherId = currentUser.getResearcherId();
        Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
        researcherById.ifPresent(r -> model.addAttribute("researcher", r));
        List<ScopusPublicationUpdate> tasks = scopusUpdateTaskRepository.findByInitiator(currentUser.getEmail());
        List<ScopusCitationsUpdate> citationsTasks = scopusCitationsTaskRepository.findByInitiator(currentUser.getEmail());

        model.addAttribute("tasks", tasks);
        model.addAttribute("citationsTasks", citationsTasks);
        model.addAttribute("user", currentUser);
        return "user/tasks";
    }


    @PostMapping("/tasks/scopus/update")
    public ResponseEntity<ScopusPublicationUpdate> createScopusUpdateTask(@ModelAttribute ScopusPublicationUpdate task,
                                                 Authentication authentication,
                                                 RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        // Ensure initiator is set so tasks can be queried by user later
        task.setInitiator(currentUser.getEmail());
        task.setStatus(Status.PENDING);
        task.setInitiatedDate(java.time.LocalDate.now().toString());
        scopusUpdateTaskRepository.save(task);

        redirectAttributes.addFlashAttribute("successMessage", "Scopus update task created.");
        return new ResponseEntity<>(task, HttpStatus.CREATED);
    }

    @PostMapping("/tasks/scopus/updateCitations")
    public ResponseEntity<ScopusCitationsUpdate> createScopusCitationsUpdateTask(@ModelAttribute ScopusCitationsUpdate task,
                                                                          Authentication authentication,
                                                                          RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        // Ensure initiator is set so tasks can be queried by user later
        task.setInitiator(currentUser.getEmail());
        task.setStatus(Status.PENDING);
        task.setInitiatedDate(java.time.LocalDate.now().toString());
        scopusCitationsTaskRepository.save(task);

        redirectAttributes.addFlashAttribute("successMessage", "Scopus update task created.");
        return new ResponseEntity<>(task, HttpStatus.CREATED);
    }


    @GetMapping("/publications/citations")
    public String showPublicationCitationsPage(Model model, Authentication authentication, @RequestParam("id") String eid) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        Optional<ro.uvt.pokedex.core.model.scopus.Publication> byId = scopusPublicationRepository.findById(eid);
        byId.ifPresent(pub -> {
            model.addAttribute("publication", pub);
            List<Citation> allByCited = scopusCitationRepository.findAllByCitedId(pub.getId());
            List<String> citations = new ArrayList<>();
            allByCited.forEach(c -> citations.add(c.getCitingId()));
            List<Publication> citationsPub = scopusPublicationRepository.findAllByIdIn(citations);
            model.addAttribute("citations", citationsPub);
            model.addAttribute("forum", scopusVenueRepository.findById(pub.getForum()).get());
            Set<String> authorKeys = new HashSet<>(pub.getAuthors());
            Set<String> forumKeys = new HashSet<>();
            citationsPub.forEach(p -> {
//                authorKeys.addAll(p.getAuthors());
                forumKeys.add(p.getForum());
            });
            List<Author> byIdIn = scopusAuthorRepository.findByIdIn(authorKeys);
            List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
            Map<String, Author> authorMap = new HashMap<>();
            byIdIn.forEach(a -> {
                authorMap.put(a.getId(), a);
            });
            model.addAttribute("authorMapping", authorMap);
            Map<String, Forum> forumMap = new HashMap<>();
            forums.forEach(f -> {
                forumMap.put(f.getId(), f);
            });
            model.addAttribute("forumMap", forumMap);

        });

        //adjust number of authors shown on page.

        model.addAttribute("user", currentUser);
        return "user/citations";
    }


    @GetMapping("/publications/edit/{eid}")
    public String showEditPublicationForm(@PathVariable("eid") String eid, Model model) {
        Optional<Publication> publicationOpt = scopusPublicationRepository.findById(eid);
        if (publicationOpt.isPresent()) {
            model.addAttribute("publication", publicationOpt.get());
            return "user/publications-edit";
        } else {
            return "redirect:/user/publications"; // or an error page
        }
    }

    @PostMapping("/publications/save/{eid}")
    public String savePublication(@ModelAttribute Publication publication, RedirectAttributes redirectAttributes, @PathVariable("eid") String eid) {
        Optional<Publication> byId = scopusPublicationRepository.findById(eid);
        byId.ifPresent( pub -> {
                    pub.setSubtypeDescription(publication.getSubtypeDescription());
                    pub.setSubtype(publication.getSubtype());
                    scopusPublicationRepository.save(pub);
                });
        redirectAttributes.addFlashAttribute("successMessage", "Publication updated successfully.");
        return "redirect:/user/publications";
    }


    @GetMapping("/indicators")
    public String showPubCriteriaPage(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        List<Indicator> all = criterionRepository.findAll();

        model.addAttribute("indicators", all);
        //adjust number of authors shown on page.

        model.addAttribute("user", currentUser);
        return "user/indicators";
    }


    @GetMapping("/indicators/apply/{id}")
    public String showCriteriaResultsPage(Model model, Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // or your login route
        }

        String researcherId = currentUser.getResearcherId();
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        Optional<Indicator> indicatorOpt = criterionRepository.findById(id);

        if (indicatorOpt.isPresent() && researcherOpt.isPresent()) {
            Indicator indicator = indicatorOpt.get();
            Researcher researcher = researcherOpt.get();
            model.addAttribute("indicator", indicator);

            if(indicator.getOutputType().toString().contains("ACTIVIT")){
                List<ActivityInstance> activities = activityInstanceRepository.findAllByResearcherId(researcherId);
                activities = activities.stream().filter(act -> act.getActivity().getName().equals(indicator.getActivity().getName())).toList();
                return handleActivities(model, indicator, activities);
            }else {
                // Fetch all authors associated with the researcher
                List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
                List<String> authorIds = authors.stream().map(Author::getId).toList();
                if (!authors.isEmpty()) {
                    // Fetch all publications for these authors
                    List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

                    if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
                        return handlePublications(model, indicator, authors, publications);
                    } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
                        return handleCitations(model, indicator, authors, publications);
                    }
                }
            }
        }

        model.addAttribute("user", currentUser);
        return "user/indicators";
    }

    private String handlePublications(Model model, Indicator indicator, List<Author> authors, List<Publication> publications) {
        List<Publication> filteredPublications = publications;
        if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_MAIN_AUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().anyMatch( a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        } else if (indicator.getOutputType().equals(Indicator.Type.PUBLICATIONS_COAUTHOR)) {
            filteredPublications = publications.stream().filter(p -> authors.stream().noneMatch(a -> a.getId().equals(p.getAuthors().get(0)))).collect(Collectors.toList());
        }
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(filteredPublications, indicator);
        model.addAttribute("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        model.addAttribute("scores", scores);
        filteredPublications = filteredPublications.stream().filter(p -> scores.containsKey(p.getTitle()) && scores.get(p.getTitle()).getAuthorScore() > 0.0).collect(Collectors.toList());
        model.addAttribute("publications", filteredPublications);
        Set<String> forumKeys = new HashSet<>();
        filteredPublications.forEach(p -> {
            forumKeys.add(p.getForum());
        });
        List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> {
            forumMap.put(f.getId(), f);
        });

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });
        model.addAttribute("forumMap", forumMap);
        model.addAttribute("allQuarters", quarterHistogram.keySet());
        model.addAttribute("allValues", quarterHistogram.values());
        return "user/indicators-apply-publications";
    }

    private String handleActivities(Model model, Indicator indicator, List<ActivityInstance> activities) {

        Map<String, Score> scores = activityReportingService.calculateActivityScores(activities, indicator);
        model.addAttribute("total", String.format("%.2f", scores.get("total").getAuthorScore()));
        scores.remove("total");
        model.addAttribute("scores", scores);
        model.addAttribute("activities", activities);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            quarterHistogram.putIfAbsent(v.getQuarter(), 0);
            quarterHistogram.put(v.getQuarter(), quarterHistogram.get(v.getQuarter()) + 1);
        });

        model.addAttribute("allQuarters", quarterHistogram.keySet());
        model.addAttribute("allValues", quarterHistogram.values());
        return "user/indicators-apply-activities";
    }

    private String handleCitations(Model model, Indicator indicator, List<Author> authors, List<Publication> publications) {
        double total = 0;
        AtomicInteger totalCit = new AtomicInteger();
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        Map<String, Map<String, Score>> scores = new HashMap<>();
        Map<String, Publication> citationsMap = new HashMap<>();
        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scopusCitationRepository.findAllByCitedIdIn(pubIds);
        List<String> allCitationsIds = allCitations.stream().map(Citation::getCitingId).collect(Collectors.toList());
//        Map<String, List<String>> citationsByCited = allCitations.stream()
//                .collect(Collectors.groupingBy(Citation::getCitedId, Collectors.mapping(Citation::getCitingId, Collectors.toList())));
        List<Publication> allByEidIn = scopusPublicationRepository.findAllByIdIn(allCitationsIds);
        Map<String, List<Publication>> citationsMapRetrieved = allByEidIn.stream().collect(Collectors.groupingBy(Publication::getId));

        Set<String> forumKeys = new HashSet<>();

        for (Publication pub : publications) {
            forumKeys.add(pub.getForum());
            List<Publication> citations = new ArrayList<>();
            for (Citation cit : allCitations) {
                if(cit.getCitedId().equals(pub.getId())) {
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
        total = scores.values().stream().mapToDouble(s -> s.get("total").getAuthorScore()).sum();

        List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> {
            forumMap.put(f.getId(), f);
        });
        model.addAttribute("forumMap", forumMap);

        Map<String, Integer> quarterHistogram = new HashMap<>();
        scores.forEach((k, v) -> {
            v.forEach((kk, vv) -> {
                quarterHistogram.putIfAbsent(vv.getQuarter(), 0);
                quarterHistogram.put(vv.getQuarter(), quarterHistogram.get(vv.getQuarter()) + 1);
            });

        });
        quarterHistogram.remove(null);
        model.addAttribute("allQuarters", quarterHistogram.keySet());
        model.addAttribute("allValues", quarterHistogram.values());

        model.addAttribute("total", String.format("%.2f", total));
        model.addAttribute("totalCit", totalCit.get());
        scores.remove("total");
        model.addAttribute("scores", scores);
        model.addAttribute("publications", publications);
        model.addAttribute("citationMap", citationsMap);
        return "user/indicators-apply-citations";
    }

    public static void applyFinalSelector(Indicator indicator, Map<String, Map<String, Score>> scores) {
        if(indicator.getSelector() != null && indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
            // Select top 10 based on author score
            Map<String, Score> topScores = new HashMap<>();
            scores.forEach((k, v) -> {
                topScores.putAll(v);
            });
            List<String> top10 = topScores.entrySet().stream()
                    .filter(x -> !x.getKey().equals("total"))
                    .sorted(Map.Entry.<String, Score>comparingByValue(Comparator.comparingDouble(Score::getAuthorScore)).reversed())
                    .limit(10)
                    .map(Map.Entry::getKey)
                    .toList();
            boolean[] used = new boolean[top10.size()];
            for(String key : scores.keySet()) {

                Iterator<String> titleIterator = scores.get(key).keySet().iterator();

                while (titleIterator.hasNext()) {
                    String title = titleIterator.next();
                    if(title.equals("total")) {
                        continue; // Skip the total entry
                    }
                    if (!top10.contains(title) || used[top10.indexOf(title)]) {
                        titleIterator.remove();
                    }
                    if(top10.contains(title)) {
                        used[top10.indexOf(title)] = true;
                    }
                }
                double totalA = 0.0;
                double totalF = 0.0;
                scores.get(key).remove("total");
                for(Score score : scores.get(key).values()) {
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

    @GetMapping("indicators/export/{id}")
    @ResponseBody
    public void exportIndicatorResults(@PathVariable("id") String id, Authentication authentication, HttpServletResponse response) throws IOException {
        // Check if user is authenticated
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.sendRedirect("/login"); // or your login route
            return;
        }

        String researcherId = currentUser.getResearcherId();
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        Optional<Indicator> indicatorOpt = criterionRepository.findById(id);

        if (!indicatorOpt.isPresent() || !researcherOpt.isPresent()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Indicator indicator = indicatorOpt.get();
        Researcher researcher = researcherOpt.get();

        // Fetch all authors associated with the researcher
        List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
        if (authors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        // Fetch all publications for these authors
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

        // Query forums related to publications
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));

        // Create Excel workbook
        Workbook workbook = new XSSFWorkbook();

        if (indicator.getOutputType().toString().contains("PUBLICATIONS")) {
            handlePublications(workbook, indicator, authors, publications, forumMap);
        } else if (indicator.getOutputType().equals(Indicator.Type.CITATIONS) || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
            handleCitations(workbook, indicator, authors, publications, forumMap);
        }

        // Set content type and headers
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"indicator_results.xlsx\"");

        // Write the workbook to the response output stream
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    private void handlePublications(Workbook workbook, Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Forum> forumMap) {
        Map<String, Score> scores = scientificProductionService.calculateScientificProductionScore(publications, indicator);

        Sheet sheet = workbook.getSheet("Publications");
        if(sheet == null) {
            sheet = workbook.createSheet("Publications");
            // Create header row for each publication's sheet
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
            if(scores.get(publication.getTitle()) == null) {
                continue; // Skip if no score is available
            }
            Row dataRow = sheet.createRow(++rowNum);
            dataRow.createCell(0).setCellValue(publication.getTitle());
            String authorDetails = String.join(", ", getAuthorNames(publication.getAuthors(), cacheService.getAuthorCache()));
            dataRow.createCell(1).setCellValue(authorDetails);
            dataRow.createCell(2).setCellValue(forumMap.get(publication.getForum()).getPublicationName());
            dataRow.createCell(3).setCellValue(publication.getVolume());
            dataRow.createCell(4).setCellValue(publication.getCoverDate().substring(0, 4));
            dataRow.createCell(5).setCellValue("No");
            dataRow.createCell(6).setCellValue(scores.get(publication.getTitle()).getCategory());
            dataRow.createCell(7).setCellValue(scores.get(publication.getTitle()).getScore());
            dataRow.createCell(8).setCellValue(scores.get(publication.getTitle()).getAuthorScore());
        }
    }

    private void handleCitations(Workbook workbook, Indicator indicator, List<Author> authors, List<Publication> publications, Map<String, Forum> forumMap) {
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);

        Sheet sheet = workbook.getSheet("Citations");
        if(sheet == null) {
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
            String title = publication.getTitle();
            title = title.replace(":", "");

            // Create header row for each publication's sheet
            sheet.createRow(++rowIdx);

            // Retrieve citations for this publication
            List<Citation> citations = scopusCitationRepository.findAllByCitedId(publication.getId());
            List<String> citingIds = citations.stream().map(Citation::getCitingId).collect(Collectors.toList());
            List<Publication> citingPublications = scopusPublicationRepository.findAllByIdIn(citingIds);

            // Query forums related to publications
            Set<String> forumKeys = citingPublications.stream().map(Publication::getForum).collect(Collectors.toSet());
            Set<String> authorIds = citingPublications.stream().map(Publication::getAuthors).flatMap(Collection::stream).collect(Collectors.toSet());
            List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
            List<Author> allAuthors = scopusAuthorRepository.findByIdIn(authorIds);
            Map<String, Forum> forumMap2 = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));
            forumMap.putAll(forumMap2);


            for (Publication citingPublication : citingPublications) {
                if (excludeSelf && authors.stream().anyMatch(a -> citingPublication.getAuthors().contains(a.getId()))) {
                    continue; // Skip self-citations
                }
                if(forumMap.get(citingPublication.getForum()) == null) {
                    continue;
                }

                // Calculate the score for each citation
                Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(publication, Collections.singletonList(citingPublication), indicator);
                Score citationScore = citScores.get(citingPublication.getTitle());
                if(citationScore == null) {
                    continue;
                }

                String authorDetails = String.join(", ", getAuthorNames(citingPublication.getAuthors(), cacheService.getAuthorCache()));

                Row row = sheet.createRow(++rowIdx);
                row.createCell(0).setCellValue(publication.getTitle());
                row.createCell(1).setCellValue(citingPublication.getTitle());
                row.createCell(2).setCellValue(authorDetails);
                row.createCell(3).setCellValue(forumMap.get(citingPublication.getForum()).getPublicationName());
                row.createCell(4).setCellValue(citingPublication.getVolume());
                row.createCell(5).setCellValue(citingPublication.getCoverDate().substring(0, 4));
                row.createCell(6).setCellValue("No");

                row.createCell(7).setCellValue(citationScore.getCategory());
                row.createCell(8).setCellValue(citationScore.getScore());
                row.createCell(9).setCellValue(citationScore.getAuthorScore());

            }
        }
    }

    private String[] getAuthorNames(List<String> authorIds, Map<String, Author> authorMap) {
        String[] result = new String[authorIds.size()];
        for(int i = 0; i < authorIds.size(); i++){

                if(authorMap.containsKey(authorIds.get(i))){
                    result[i] = authorMap.get(authorIds.get(i)).getName();
                }



        }
        return result;
    }

    @GetMapping("/export/cnfis")
    @ResponseBody
    public void exportCnfisResults(Authentication authentication, HttpServletResponse response) throws IOException {
        // Check if user is authenticated
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.sendRedirect("/login"); // or your login route
            return;
        }

        String researcherId = currentUser.getResearcherId();
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);

        if (!researcherOpt.isPresent()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Researcher researcher = researcherOpt.get();

        // Fetch all authors associated with the researcher
        List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
        if (authors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        List<String> authorIds = authors.stream().map(Author::getId).toList();
        // Fetch all publications for these authors
        List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);

        // Query forums related to publications
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
        Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));

        // Load the template Excel file
        ClassPathResource resource = new ClassPathResource("/data/templates/Anexa5-Fisa_articole_brevete.xlsx");
        try (Workbook workbook = new XSSFWorkbook(resource.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            int rowNum = 15; // Start populating from row 15
            for (Publication publication : publications) {
                Row row = sheet.createRow(rowNum++);

                String year = publication.getCoverDate() != null ? publication.getCoverDate().split("-")[0] : "";
                String title = publication.getTitle() != null ? publication.getTitle() : "";
                String doi = publication.getDoi() != null ? publication.getDoi() : "";
                String wosCode = "";
                String brevetCode = "";
                String forumName = forumMap.getOrDefault(publication.getForum(), new Forum()).getPublicationName();
                String issnOnline = forumMap.getOrDefault(publication.getForum(), new Forum()).getEIssn();
                String issnPrint = forumMap.getOrDefault(publication.getForum(), new Forum()).getIssn();
                String isbn = "";
                int totalAuthors = publication.getAuthors().size();
                long universityAuthors = publication.getAuthors().stream().filter(authorIds::contains).count();

                row.createCell(0).setCellValue(year);
                row.createCell(1).setCellValue(title);
                row.createCell(2).setCellValue(doi);
                row.createCell(3).setCellValue(wosCode);
                row.createCell(4).setCellValue(brevetCode);
                row.createCell(5).setCellValue(forumName);
                row.createCell(6).setCellValue(issnOnline);
                row.createCell(7).setCellValue(issnPrint);
                row.createCell(8).setCellValue(isbn);
                row.createCell(13).setCellValue(totalAuthors);
                row.createCell(14).setCellValue(universityAuthors);
            }

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"Anexa5-Fisa_articole_brevete.xlsx\"");
            workbook.write(response.getOutputStream());
        }
    }


    @GetMapping("/individualReports")
    public String viewReports(Model model, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        List<IndividualReport> all = individualReportRepository.findAll();

        model.addAttribute("individualReports", all);
        model.addAttribute("user", currentUser);
        return "user/individualReports";
    }


    @GetMapping("/individualReports/view/{id}")
    public String viewIndividualReport(Model model, Authentication authentication, @PathVariable("id") String id) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login";
        }

        long start = System.currentTimeMillis();
        Optional<IndividualReport> reportOpt = individualReportRepository.findById(id);

        if (reportOpt.isPresent()) {
            IndividualReport report = reportOpt.get();
            model.addAttribute("report", report);

            // Fetch the researcher once
            Researcher researcher = researcherService.findResearcherById(currentUser.getResearcherId()).orElse(null);
            if (researcher == null) {
                return "redirect:/error";  // or some appropriate error handling
            }

            // Fetch all authors associated with the researcher
            List<Author> authors = scopusAuthorRepository.findByIdIn(researcher.getScopusId());
            if (authors.isEmpty()) {
                return "redirect:/error";  // or some appropriate error handling
            }

            // Fetch all publications for these authors
            List<String> authorIds = authors.stream().map(Author::getId).toList();
            List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
            if(!"ANY".equals(report.getIndividualAffiliation().getName())) {
                publications = publications.stream().filter(p -> report.getIndividualAffiliation().getScopusAffiliations().stream().anyMatch(aff -> p.getAffiliations().contains(aff.getAfid()))).collect(Collectors.toList());
            }

            // Fetch data for each indicator
            List<Indicator> indicators = report.getIndicators();

            Map<Indicator, Double> indicatorScores = new HashMap<>();
            double totalScore = 0;

            for (Indicator indicator : indicators) {
                long l = System.currentTimeMillis();
                double indicatorScore = 0;
                if(indicator.getOutputType().toString().contains("ACTIVIT")){
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
            for(int i = 0; i < report.getCriteria().size(); i++) {
                AbstractReport.Criterion criterion = report.getCriteria().get(i);
                double criterionScore = 0;
                System.out.println(i);
                for(Integer in : criterion.getIndicatorIndices()) {
                    System.out.println("Indicator index: " + in);
                    Indicator ind = report.getIndicators().get(in);
                    if(indicatorScores.containsKey(ind)) {
                        criterionScore += indicatorScores.get(ind);
                    }
                }
                criterionScores.put(i, criterionScore);
            }

            model.addAttribute("indicatorScores", indicatorScores);
            model.addAttribute("criterionScores", criterionScores);
            model.addAttribute("totalScore", totalScore);
        }
        System.out.println("Computed Report in: " + (System.currentTimeMillis() - start) + " ms");

        model.addAttribute("user", currentUser);
        return "user/individualReport-view";
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
        applyFinalSelector(indicator, scores);
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
    @GetMapping("/rankings/{id}")
    public String showRankingPage(Model model, @PathVariable  String id) {
        Optional<Forum> byId = scopusVenueRepository.findById(id);
        if(byId.isPresent()) {
            Forum forum = byId.get();
            if(forum.getAggregationType().equals("Journal")) {
                String generatedId = WoSRanking.getGeneratedId(forum.getIssn(), forum.getEIssn());
                if(generatedId != null){
                    Optional<WoSRanking> journals = rankingRepository.findById(generatedId);
                    if (journals.isPresent()) {
                        WoSRanking ranking = journals.get();
                        model.addAttribute("journal", ranking);
                        return "admin/rankings-view";
                    }
                }
            }
        }
        return "user/ranking-not-found";
    }

    @PostMapping("/profile/save")
    public String saveResearchProfile(@ModelAttribute Researcher researcher,
                                      Authentication authentication,
                                      RedirectAttributes redirectAttributes) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            return "redirect:/login"; // Redirect to login if user is not authenticated
        }

        // Save the Researcher in the database
        Researcher savedResearcher = researcherService.saveResearcher(researcher);

        // Link the Researcher with the User account
        currentUser.setResearcherId(savedResearcher.getId());
        userService.updateUser(currentUser.getEmail(), currentUser);

        // Add a success message to RedirectAttributes
        redirectAttributes.addFlashAttribute("successMessage", "Research profile updated successfully.");

        // Redirect back to the profile page
        return "redirect:/user/profile";
    }

    private int computeHIndex(List<Publication> publications) {
        int n = publications.size();
        int[] citationCounts = new int[n + 1];

        // Count citations for each publication
        for (Publication pub : publications) {
            int citedByCount = pub.getCitedbyCount();
            if (citedByCount > n) {
                citationCounts[n]++;
            } else {
                citationCounts[citedByCount]++;
            }
        }

        // Compute the h-index
        int totalPapers = 0;
        for (int i = n; i >= 0; i--) {
            totalPapers += citationCounts[i];
            if (totalPapers >= i) {
                return i;
            }
        }

        return 0;
    }
    // File: src/main/java/ro/uvt/pokedex/core/view/UserViewController.java
    @GetMapping("/publications/exportCNFIS2025")
    @ResponseBody
    public void createCNFISReport2025(Authentication authentication,
                                      HttpServletResponse response,
                                      @RequestParam(name = "start", defaultValue = "2021") String startYear,
                                      @RequestParam(name = "end", defaultValue = "2024") String endYear) throws IOException {
        // Similar logic: retrieve the required entity (e.g., user group or similar) and build the publications list and reports.
        if (authentication == null || !(authentication.getPrincipal() instanceof User currentUser)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }else {
            String researcherId = currentUser.getResearcherId();

            Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
            if (researcherById.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            Researcher researcher = researcherById.get();
            List<String> authorIds = new ArrayList<>(researcher.getScopusId())/* logic to compute author ids */;
            List<Publication> publications = scopusPublicationRepository.findAllByAuthorsIn(authorIds);
            int start = Integer.parseInt(startYear);
            int end = Integer.parseInt(endYear);
            publications = publications.stream().filter(publication -> {
                int pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
                return pubYear >= start && pubYear <= end;
            }).toList();

            List<CNFISReport2025> cnfisReports = new ArrayList<>();
            // Assume the domain is retrieved elsewhere if needed.
            Domain domain = domainRepository.findByName("ALL").orElse(null);
            for (Publication publication : publications) {
                publication = woSExtractor.findPublicationWosId(publication);
                scopusPublicationRepository.save(publication);
                CNFISReport2025 cnfisReport = cnfiSScoringService2025.getReport(publication, domain);
                cnfisReports.add(cnfisReport);
            }

            Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
            List<Forum> forums = scopusVenueRepository.findByIdIn(forumKeys);
            Map<String, Forum> forumMap = forums.stream().collect(Collectors.toMap(Forum::getId, f -> f));



            exportService.exportCNFISReport2025(publications, cnfisReports, forumMap, authorIds, response, false);
        }
    }

}
