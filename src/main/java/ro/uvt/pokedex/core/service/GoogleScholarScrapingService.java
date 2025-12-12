package ro.uvt.pokedex.core.service;

import org.springframework.beans.factory.annotation.Autowired;
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


    @Autowired
    public GoogleScholarScrapingService(ResearcherService researcherService) {

        this.researcherService = researcherService;
    }

//    @Async("taskExecutor")
//    public void getNewPublications(Researcher researcher) {
//        Map<String, String> paperLinks = getPaperLinks(researcher.getScholarId());
//
//        for (Map.Entry<String, String> entry : paperLinks.entrySet()) {
//            String title = entry.getKey();
//            String paperUrl = entry.getValue();
//
//            Optional<Publication> dbPub = publicationRepository.findByScrapeURL(paperUrl);
//            if(dbPub.isPresent()){
//                System.out.println("Publication " + title + " already exists");
//
//                updateAuthorList(dbPub.get());
//                continue;
//            }
//
//            System.out.println("Retrieving information for " + title);
//            Publication publication = retrievePublication(paperUrl, title);
//
//            if (publication != null) {
//                    publicationRepository.save(publication);
//                System.out.println("Saving publication " + title);
//            }
//            try {
//                TimeUnit.SECONDS.sleep(PAPER_INFO_DELAY); // Wait before getting another paper
//            } catch (InterruptedException ie) {
//                Thread.currentThread().interrupt();
//            }
//        }
//    }
//
//    private void updateAuthorList(Publication publication) {
//        List<String> authorNames = publication.getAuthorList();
//        List<Researcher> authorIds = publication.getResearcherAuthorList();
//        for (String authorName : authorNames) {
//            Optional<Researcher> matchedAuthorIdOrName = researcherService.matchAuthorToResearcher(authorName);
//            matchedAuthorIdOrName.ifPresent(author -> {
//                    if(!authorIds.contains(author)){
//                        authorIds.add(author);
//                    }
//            });
//        }
//        publication.setResearcherAuthorList(authorIds);
//        publicationRepository.save(publication);
//
//    }
//
//    public Publication retrievePublication(String paperUrl, String title) {
//        Map<String, String> paperInfo = retrievePaperInformation(paperUrl);
//
//        if (paperInfo.isEmpty()) {
//            return null; // If data couldn't be retrieved
//        }
//        paperInfo.put("title", title);
//        paperInfo.put("paperUrl", paperUrl);
//
//        return mapToPublication(paperInfo); // Map data to a Publication
//    }
//
//    public Map<String, String> retrievePaperInformation(String paperUrl) {
//        Map<String, String> paperInfo = new HashMap<>();
//        int attempt = 0;
//        long delay = INITIAL_DELAY;
//        boolean retrieved = false;
//
//        while (attempt < MAX_RETRIES && !retrieved) {
//            try {
//                Document document = Jsoup.connect(paperUrl).userAgent(userAgent).get();
//                retrieved = true;
//
//                Element link = document.selectFirst("a.gsc_oci_title_link");
//                if (link != null) {
//                    paperInfo.put("link", link.absUrl("href"));
//                }
//
//                Elements rows = document.select("div.gs_scl");
//                for (int i = 0; i < rows.size() - 1; i++) {
//                    Element row = rows.get(i);
//                    String field = row.selectFirst("div.gsc_oci_field").text();
//                    String value;
//                    if(field.equals("Total referințe bibliografice")){
//                        value = row.selectFirst("div.gsc_oci_value").selectFirst("a").absUrl("href");
//                    }else {
//                        value = row.selectFirst("div.gsc_oci_value").text();
//                    }
//                    paperInfo.put(field, value);
//                }
//                Element citationLink = null;
//
//                if (paperInfo.get("Total referințe bibliografice") != null) {
//                    paperInfo.put("citationHref", paperInfo.get("Total referințe bibliografice"));
//                }
//            } catch (HttpStatusException e) {
//                if (e.getStatusCode() == 429) {
//                    System.out.println("Rate limit detected. Retrying in " + delay + " seconds...");
//                    try {
//                        TimeUnit.SECONDS.sleep(delay); // Wait before retrying
//                    } catch (InterruptedException ie) {
//                        Thread.currentThread().interrupt();
//                    }
//                    delay *= BACKOFF_FACTOR;
//                    attempt++; // Retry with exponential backoff
//                } else {
//                    throw new RuntimeException("HTTP error fetching URL: " + e.getMessage(), e);
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//
//        return paperInfo;
//    }
//
//    private void waitForUserConfirmation() {
//        Scanner scanner = new Scanner(System.in);
//        System.out.println("After resolving the CAPTCHA, press Enter to continue...");
//        scanner.nextLine(); // Wait for user confirmation
//    }
//
//    public Map<String, String> getPaperLinks(String scholarId) {
//        String url = "https://scholar.google.com/citations?user=" + scholarId + "&pagesize=1000";
//        Map<String, String> papers = new HashMap<>();
//        int attempt = 0;
//        long delay = INITIAL_DELAY;
//        boolean retrieved = false;
//        while (attempt < MAX_RETRIES && !retrieved) {
//            try {
//                Document document = Jsoup.connect(url).userAgent(userAgent).get();
//                Elements rows = document.select("tr.gsc_a_tr");
//                retrieved = true;
//                for (Element row : rows) {
//                    Element paper = row.selectFirst("a.gsc_a_at");
//                    papers.put(paper.text(), paper.absUrl("href"));
//                }
//            } catch (HttpStatusException e) {
//                if (e.getStatusCode() == 429) { // CAPTCHA or rate limit detected
//                    System.out.println("Rate limit detected. Please resolve the CAPTCHA.");
//                    System.out.println("Visit this URL in your browser: " + e.getUrl());
//
//                    System.out.println("Rate limit detected. Retrying in " + delay + " seconds...");
//                    try {
//                        TimeUnit.SECONDS.sleep(delay); // Wait before retrying
//                    } catch (InterruptedException ie) {
//                        Thread.currentThread().interrupt();
//                    }
//                    delay *= BACKOFF_FACTOR;
//                    // Manual intervention: wait for user to confirm CAPTCHA is resolved
//                    waitForUserConfirmation();
//
//                    attempt++; // Increment attempt after user confirmation
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//        return papers;
//    }
//
//    public Publication mapToPublication(Map<String, String> paperInfo) {
//        Publication publication = new Publication();
//        publication.setTitle(paperInfo.get("title"));
//        publication.setScrapeURL(paperInfo.get("paperUrl"));
//        publication.setLink(paperInfo.get("link"));
//
//
//
//        retrievePublicationDOIandIdentifiers(publication.getLink(), publication, paperInfo);
//        List<String> authorList = publication.getAuthorList();
//
//        List<Researcher> authorIds = new ArrayList<>();
//        if(authorList.size() == 0){
//            try {
//                publication.setYear(Integer.parseInt(paperInfo.getOrDefault("Data publicării", "0000").substring(0, 4)));
//            }catch (Exception e){
//                System.err.println(e.getMessage());
//                publication.setYear(0);
//            }
//
//            String[] authors = paperInfo.get("Autori").split("\\s+,");
//            authorList.addAll(Arrays.asList(authors));
//            publication.setAuthorList(authorList);
//        }
//        for (String authorName : authorList) {
//            Optional<Researcher> matchedAuthorIdOrName = researcherService.matchAuthorToResearcher(authorName);
//            matchedAuthorIdOrName.ifPresent(authorIds::add);
//        }
//        publication.setResearcherAuthorList(authorIds);
//
//        if(publication.getVenue()!=null){
////            Optional<Venue> dbVenue = venueRepository.findByIssnOrIsbn(publication.getVenue().getIssn(), publication.getVenue().getIsbn());
//            Optional<Venue> dbVenue = venueRepository.findByTitle(publication.getVenue().getTitle());
//
//            if(dbVenue.isPresent()){
//                Venue venue = dbVenue.get();
//                venue.getIssn().putAll(publication.getVenue().getIssn());
//                venue.getIsbn().putAll(publication.getVenue().getIsbn());
//                Venue saved = venueRepository.save(venue);
//                publication.setVenue(saved);
//            }else{
//                Venue saved = venueRepository.save(publication.getVenue());
//                publication.setVenue(saved);
//            }
//        }
//        publication.setCitationsURL(paperInfo.get("citationHref"));
//
//        return publication;
//    }
//
//    private Publication retrievePublicationDOIandIdentifiers(String link, Publication publication, Map<String, String> paperInfo) {
//        if (link == null)
//            return publication;
//
//
//            // Regex patterns compiled once
//            Pattern doiPattern = Pattern.compile("\\b10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+\\b");
//            Pattern issnPattern = Pattern.compile("\\b\\d{4}-\\d{3}[\\dX]\\b");
//            Pattern isbnPattern = Pattern.compile("\\b97[89][- ]?(\\d{1,5}[- ]?)?(\\d{1,7}[- ]?)?(\\d{1,6}[- ]?)?(\\d{1,3}[- ]?)?[\\dX]\\b");
//
//            Matcher matcher = doiPattern.matcher(link);
//            Document doc = null;
//            if (matcher.find()) {
//                publication.setDoi(matcher.group(0)); // Uses the whole matched text
//            }else {
//                try {
//                doc = Jsoup.connect(link).get();
//                if (link.contains("ieeexplore")) {
//                    //javascript generated contents
//                    Elements scriptElements = doc.getElementsByTag("script");
//
//                    for (Element element : scriptElements) {
//                        String scriptText = element.data();
//                        // Apply the same patterns to each script content
//                        if(findAndSet(scriptText, doiPattern, publication::setDoi))
//                            break;
//                    }
//                }else {
//                    String text = doc.text(); // Extracts all text from the document
//                    // Attempt to find each identifier in the text
//                    findAndSet(text, doiPattern, publication::setDoi);
//                }
//                } catch (IOException e) {
//                    System.err.println("Error fetching the page: " + e.getMessage());
//                }
//
//            }
//
//            String doi = publication.getDoi();
//            if (doi != null) {
//                JSONObject crossRefData = fetchCrossRefDataByDOI(doi);
//
//                if (crossRefData != null) {
//                    String crossRefTitle = crossRefData.optString("title").replace("\"", "").replace("]", "").replace("[", "");
//                    if (publication.getTitle().equalsIgnoreCase(crossRefTitle)) {
//                        setVenueFromCrossRefData(crossRefData, publication);
//                    } else {
//                        JSONObject searchResult = searchCrossRefByTitle(publication.getTitle());
//                        if (searchResult != null) {
//                            setVenueFromCrossRefData(searchResult, publication);
//                        }
//                    }
//                }else if (doc != null){
//                        String text = doc.text();
//                        Venue venue = publication.getVenue();
//                        String[] titleAndAcronym = processTitle(venue.getTitle());
//                        venue.setTitle(titleAndAcronym[0]);
//                        venue.setAcronym(titleAndAcronym[1]);
//                        String year = titleAndAcronym[2];
//                        findAndSet(text, issnPattern, v -> venue.getIssn().put(year, v));
//                        findAndSet(text, isbnPattern, v -> venue.getIsbn().put(year, v));
//
//                        if (paperInfo.containsKey("Conferință")) {
//                            publication.setPublicationType(PublicationType.CONFERENCE_PROCEEDINGS);
//                            venue.setTitle(paperInfo.get("Conferință"));
//                        } else if (paperInfo.containsKey("Jurnal")) {
//                            publication.setPublicationType(PublicationType.JOURNAL);
//                            venue.setTitle(paperInfo.get("Jurnal"));
//                        } else if (paperInfo.containsKey("Carte")) {
//                            publication.setPublicationType(PublicationType.BOOK);
//                            venue.setTitle(paperInfo.get("Carte"));
//                        } else if (paperInfo.containsKey("Sursă")) {
//                            publication.setPublicationType(PublicationType.UNCATEGORIZED);
//                            venue.setTitle(paperInfo.get("Sursă"));
//                        }
//
//                }
//            }else{
//                JSONObject searchResult = searchCrossRefByTitle(publication.getTitle());
//                if (searchResult != null) {
//                    setVenueFromCrossRefData(searchResult, publication);
//                }
//            }
//
//
//
//        return publication;
//    }
//
//    private void setVenueFromCrossRefData(JSONObject crossRefData, Publication publication) {
//        String title = crossRefData.optJSONArray("title").optString(0);
//        String venueTitle = crossRefData.optJSONArray("container-title").optString(0);
//        String publisher = crossRefData.optString("publisher");
//        JSONArray issnArray = crossRefData.optJSONArray("ISSN");
//        JSONArray isbnArray = crossRefData.optJSONArray("ISBN");
//        JSONObject issued = crossRefData.optJSONObject("issued");
//        String type = crossRefData.optString("type");
//        String year = issued != null ? issued.optJSONArray("date-parts").optJSONArray(0).optString(0) : null;
//
//        publication.setTitle(title);
//        if(year != null) {
//            publication.setYear(Integer.parseInt(year));
//        }
//        if(publication.getDoi() == null)
//            publication.setDoi(crossRefData.optString("DOI"));
//
//
//        List<String> authors = new ArrayList<>();
//        JSONArray authorArray = crossRefData.optJSONArray("author");
//        if (authorArray != null) {
//            for (int i = 0; i < authorArray.length(); i++) {
//                JSONObject authorObj = authorArray.optJSONObject(i);
//                if (authorObj != null) {
//                    String authorName = authorObj.optString("given") + " " + authorObj.optString("family");
//                    authors.add(authorName);
//                }
//            }
//        }
//        publication.setAuthorList(authors);
//
//        Venue venue = new Venue();
//        venue.setTitle(venueTitle);
//        venue.setPublisher(publisher);
//        if ("journal-article".equals(type))
//            publication.setPublicationType(PublicationType.JOURNAL);
//        else if ("proceedings-article".equals(type))
//            publication.setPublicationType(PublicationType.CONFERENCE_PROCEEDINGS);
//        else if ("book-chapter".equals(type))
//            publication.setPublicationType(PublicationType.BOOK_CHAPTER);
//        else
//            publication.setPublicationType(PublicationType.UNCATEGORIZED);
//        if (issnArray != null) {
//            for (int i = 0; i < issnArray.length(); i++) {
//                venue.getIssn().put(year, issnArray.optString(i));
//            }
//        }
//
//        if (isbnArray != null) {
//            for (int i = 0; i < isbnArray.length(); i++) {
//                venue.getIsbn().put(year, isbnArray.optString(i));
//            }
//        }
//
//        publication.setVenue(venue);
//    }
//
//    private JSONObject fetchCrossRefDataByDOI(String doi) {
//        RestTemplate restTemplate = new RestTemplate();
//        String url = CROSSREF_API_URL + doi;
//        try {
//            String response = restTemplate.getForObject(url, String.class);
//            JSONObject jsonResponse = new JSONObject(response);
//            return jsonResponse.optJSONObject("message");
//        }catch (HttpClientErrorException ex){
//            System.out.println("Cannot fetch article with doi: " + doi);
//        }
//        return null;
//    }
//
//    private JSONObject searchCrossRefByTitle(String title) {
//        RestTemplate restTemplate = new RestTemplate();
//        String url = CROSSREF_API_SEARCH_URL + title;
//        try {
//            String response = restTemplate.getForObject(url, String.class);
//            JSONObject jsonResponse = new JSONObject(response);
//            JSONArray items = jsonResponse.optJSONObject("message").optJSONArray("items");
//            return items != null && items.length() > 0 ? items.getJSONObject(0) : null;
//        } catch (HttpServerErrorException e){
//            System.err.println("Cannot find paper: " + title);
//        }
//        return null;
//    }
//
//
//
//    private boolean findAndSet(String text, Pattern pattern, Consumer<String> setter) {
//        Matcher matcher = pattern.matcher(text);
//        if (matcher.find()) {
//            setter.accept(matcher.group(0)); // Uses the whole matched text
//            return true;
//        }
//        return false;
//    }
//
//    public static String[] processTitle(String title) {
//        // Regex pattern to remove the year and the edition number with its suffix
//        String cleanRegex = "^\\d{4}\\s+\\w*\\s+\\d{0,2}\\w*(?:th|st|nd|rd)?\\s+";
//        Pattern pattern = Pattern.compile(cleanRegex);
//        Matcher matcher = pattern.matcher(title);
//
//        String edition = "";
//        if (matcher.find()) {
//            edition = matcher.group(0);
//        }
//
//        title = title.replaceAll(cleanRegex, "");
//
//
//        // Regex pattern to extract and remove text within parentheses (acronym)
//        String acronymRegex = "\\s*\\(([^)]+)\\)\\s*";
//        pattern = Pattern.compile(acronymRegex);
//        matcher = pattern.matcher(title);
//        String acronym = "";
//        if (matcher.find()) {
//            acronym = matcher.group(1); // Captures the text within the first pair of parentheses
//            // Remove the acronym (and surrounding parentheses and any extra spaces) from the title
//            title = title.replaceAll(acronymRegex, "").trim();
//        }
//
//        return new String[] {title, acronym, edition};
//    }
//
//    public void getCitingWorks(Researcher researcher) {
//        List<Publication> pubs = publicationService.findPublicationsByResearcher(researcher);
////        String authToken = obtainAuthToken();  // Obtain the auth token
//
//        for(Publication p : pubs) {
//            String citURl = p.getCitationsURL();
//
//            try {
//                if(citURl!=null) {
//                    List<Map<String, String>> citingWorks = getCitingWorks(citURl);
//                    System.out.println("Retrieved " + citingWorks.size() + " works for " + p.getTitle());
//
////                    List<Map<String, String>> citingWorks = getCitingWorksCrossRef(p.getDoi());
//                    citingWorks.forEach(map -> {
//                        Optional<Publication> dbPub = publicationRepository.findByLink(map.get("link"));
//                        if (dbPub.isEmpty()) {
//                            System.out.println("Indexing " + map.get("title"));
//                            Publication publication = mapToPublication(map);
//
//                            if (publication != null) {
//                                dbPub = publicationRepository.findByTitle(publication.getTitle());
//                                Citation citation = new Citation();
//                                citation.setCited(p);
//                                if (dbPub.isPresent()) {
//                                    System.out.println("Publication already in database");
//                                    citation.setCiting(dbPub.get());
//                                } else {
//                                    publication = publicationRepository.save(publication);
//                                    citation.setCiting(publication);
//                                }
//                                List<Citation> allByCitedAndCiting = citationRepository.findAllByCitedAndCiting(citation.getCited(), citation.getCiting());
//                                if (allByCitedAndCiting.size() > 0) {
//                                    System.out.println("Citation already indexed");
//                                } else {
//                                    citationRepository.save(citation);
//                                }
//                            }
//                        } else {
//                            System.out.println("Citing work already exists in db: " + map.get("title"));
//                            Citation citation = new Citation();
//                            citation.setCited(p);
//                            citation.setCiting(dbPub.get());
//                            List<Citation> allByCitedAndCiting = citationRepository.findAllByCitedAndCiting(citation.getCited(), citation.getCiting());
//                            if (allByCitedAndCiting.size() > 0) {
//                                System.out.println("Citation already indexed");
//                            } else {
//                                citationRepository.save(citation);
//                            }
//                        }
//                    });
//
//                    TimeUnit.SECONDS.sleep(PAPER_CITATION_DELAY);
//                }else {
//                    System.out.println("Paper without citations " + p.getTitle());
//                }
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//
//        }
//    }
//
//    private List<Map<String, String>> getCitingWorks(String citeUrl) throws IOException, InterruptedException {
//        int page = 0;
//        boolean hasNextPage = true;
//        List<Map<String, String>> result = new ArrayList<>();
//
//        while (hasNextPage) {
//            String url = citeUrl + (page * 10);
//            Document doc = Jsoup.connect(url).get();
//
//            Elements citingWorks = doc.select(".gs_r");
//
//            // Skip the first occurrence
//            boolean firstSkipped = false;
//
//            for (Element citingWork : citingWorks) {
//                if (!firstSkipped) {
//                    firstSkipped = true;
//                    continue;
//                }
//
//                Map<String, String> paperInfo = new HashMap<>();
//                String title = citingWork.select(".gs_rt a").text();
//                String link = citingWork.select(".gs_rt a").attr("href");
//                String authorsAndPublication = citingWork.select(".gs_a").text();
//                String citationCount = citingWork.select(".gs_fl a[href*='cites']").text();
//
//                // Split authors and publication
//                if("".equals(authorsAndPublication))
//                    continue;
//                String[] parts = authorsAndPublication.split(" - ");
//                String[] publisherYear = parts[1].split(",");
//                String publisher = publisherYear[0];
//                String year = publisherYear.length > 1 ? publisherYear[1] : null;
//                if(year == null)
//                    year = publisherYear[0];
//                String editor = parts[parts.length-1];
//                String authors = parts[0];
//                paperInfo.put("link", link.trim());
//                paperInfo.put("title", title.trim());
//                if(year != null) {
//                    paperInfo.put("Data publicării", year.trim());
//                }
//                paperInfo.put("Autori", authors.trim());
//                paperInfo.put("Sursă", publisher.trim());
//                result.add(paperInfo);
//
//                System.out.println("Title: " + title);
//                System.out.println("Link: " + link);
//                System.out.println("Authors: " + authors);
//                System.out.println("Editor: " + editor);
//                System.out.println("Year: " + year);
//                System.out.println("Publisher: " + publisher);
//                System.out.println("Citation Count: " + citationCount);
//                System.out.println("-----");
//            }
//            Elements nextPage = doc.select("#gs_n a.gs_ico_nav_next");
//            if (nextPage.isEmpty()) {
//                hasNextPage = false;
//            } else {
//                page++;
//                TimeUnit.SECONDS.sleep(PAPER_CITATION_DELAY);
//            }
//        }
//        return result;
//    }
//
//    public List<Map<String, String>> getCitingWorksCrossRef(String doi) {
//        int rows = 20; // Number of results per page (CrossRef's default is 20)
//        String cursor = "*"; // Start cursor for pagination
//        boolean hasNextPage = true;
//        RestTemplate restTemplate = new RestTemplate();
//        String url = "https://api.crossref.org/works/" + doi;
//        List<Map<String, String>> result = new ArrayList<>();
//        try {
//            ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
//            String responseBody = responseEntity.getBody();
//            JSONObject response = new JSONObject(responseBody);
//
//            JSONArray citingWorks = response.getJSONObject("message").getJSONArray("items");
//
//            for (int i = 0; i < citingWorks.length(); i++) {
//                JSONObject citingWork = citingWorks.getJSONObject(i);
//
//                Map<String, String> paperInfo = new HashMap<>();
//                String title = citingWork.getJSONArray("title").getString(0);
//                String link = citingWork.has("URL") ? citingWork.getString("URL") : "";
//                String authors = citingWork.has("author") ? citingWork.getJSONArray("author").toString() : "";
//                String publisher = citingWork.has("publisher") ? citingWork.getString("publisher") : "";
//                String year = citingWork.has("created") ? citingWork.getJSONObject("created").getJSONArray("date-parts").getJSONArray(0).getString(0) : "";
//
//                paperInfo.put("link", link.trim());
//                paperInfo.put("title", title.trim());
//                if (!year.isEmpty()) {
//                    paperInfo.put("Data publicării", year.trim());
//                }
//                paperInfo.put("Autori", authors.trim());
//                paperInfo.put("Sursă", publisher.trim());
//                result.add(paperInfo);
//
//                System.out.println("Title: " + title);
//                System.out.println("Link: " + link);
//                System.out.println("Authors: " + authors);
//                System.out.println("Publisher: " + publisher);
//                System.out.println("Year: " + year);
//                System.out.println("-----");
//            }
//        } catch (HttpClientErrorException.NotFound e) {
//            System.err.println("Error: Citations not found for DOI " + doi);
//        } catch (HttpClientErrorException e) {
//            System.err.println("HTTP error occurred: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
//        } catch (Exception e) {
//            System.err.println("An unexpected error occurred: " + e.getMessage());
//        }
//
//        return result;
//    }
}
