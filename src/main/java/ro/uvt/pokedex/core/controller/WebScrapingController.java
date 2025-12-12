package ro.uvt.pokedex.core.controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.service.GoogleScholarScrapingService;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.ScopusService;

import java.util.Optional;

@RestController
@RequestMapping("/api/scrape")
public class WebScrapingController {

    private final GoogleScholarScrapingService googleScholarScrapingService;
    private final ScopusService scopusService;
    private final ResearcherService researcherService;

    @Autowired
    public WebScrapingController(GoogleScholarScrapingService googleScholarScrapingService, ScopusService scopusService, ResearcherService researcherService) {
        this.googleScholarScrapingService = googleScholarScrapingService;
        this.scopusService = scopusService;
        this.researcherService = researcherService;
    }

    @PostMapping("/papers")
    public ResponseEntity<String> scrapeWebForResearcher(@RequestParam("researcherId") String researcherId) {
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        if (!researcherOpt.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        Researcher researcher = researcherOpt.get();
//        googleScholarScrapingService.getNewPublications(researcher);
        return ResponseEntity.accepted().body("Scraping task submitted successfully for researcher ID: " + researcherId);
    }

    @PostMapping("/citations")
    public ResponseEntity<String> scrapeWebForResearcherCitations(@RequestParam("researcherId") String researcherId) {
        Optional<Researcher> researcherOpt = researcherService.findResearcherById(researcherId);
        if (!researcherOpt.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        Researcher researcher = researcherOpt.get();
//        googleScholarScrapingService.getCitingWorks(researcher);
        return ResponseEntity.accepted().body("Scraping task submitted successfully for researcher ID: " + researcherId);
    }


}

