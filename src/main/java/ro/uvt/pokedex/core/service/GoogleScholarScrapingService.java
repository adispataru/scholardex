package ro.uvt.pokedex.core.service;

import org.springframework.stereotype.Service;

@Service
public class GoogleScholarScrapingService {

    private static final long PAPER_INFO_DELAY = 10;
    private static final long PAPER_CITATION_DELAY = 10;
    private final String userAgent = "Safari/537.36"; // Chrome user agent

    private static final int MAX_RETRIES = 3; // Maximum number of retries on error 429
    private static final long INITIAL_DELAY = 10; // Initial delay in seconds
    private static final double BACKOFF_FACTOR = 2.0; // Exponential backoff factor
    private static final String CROSSREF_API_URL = "https://api.crossref.org/works/";
    private static final String CROSSREF_API_SEARCH_URL = "https://api.crossref.org/works?query.title=";

    private final ResearcherService researcherService;

    public GoogleScholarScrapingService(ResearcherService researcherService) {
        this.researcherService = researcherService;
    }
}
