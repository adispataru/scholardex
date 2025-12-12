package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.*;
import ro.uvt.pokedex.core.repository.scopus.*;
import ro.uvt.pokedex.core.service.CacheService;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class ScopusDataService {

    private static final Logger logger = LoggerFactory.getLogger(ScopusDataService.class);

    @Autowired
    private ScopusPublicationRepository publicationRepository;

    @Autowired
    private ScopusCitationRepository citationRepository;

    @Autowired
    private ScopusAffiliationRepository affiliationRepository;

    @Autowired
    private ScopusAuthorRepository authorRepository;

    @Autowired
    private ScopusForumRepository venueRepository;

    @Autowired
    private ScopusFundingRepository fundingRepository;
    @Autowired
    private CacheService cacheService;

    @Async("taskExecutor")
    public void loadScopusDataIfEmpty(String scopusDataFile) {
        if (publicationRepository.count() == 0) {
            importScopusData(scopusDataFile, 0, false);
        }
        if (citationRepository.count() == 0) {
            importScopusDataCitations(scopusDataFile);
        }
    }

    @Async("taskExecutor")
    public void loadAdditionalScopusData(String scopusDataFile) {

        importScopusData(scopusDataFile, 0, true);
        importScopusDataCitations(scopusDataFile);

    }

    @Async("taskExecutor")
    public void importScopusData(String jsonFilePath, long count, boolean checkExisting) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            logger.info("Processing starting at {} of {} publications from JSON file.", count, dataSize);
            List<Publication> publications = new ArrayList<>();
            for (int i = (int) count; i < dataSize; i++) {
                Publication publication = createPublicationFromJson(rootNode, i);
                publication.setApproved(true);
                handleAffiliations(publication, rootNode, i);
                handleAuthors(publication, rootNode, i);
                handleVenue(publication, rootNode, i);
                handleFunding(publication, rootNode, i);
                publications.add(publication);
                if(i % (dataSize / 10) == 0)
                    logger.info("Processed {}% publications.", (i* 100.0)/dataSize );
            }
            logger.info("Processed all publications. Saving to database.");
            cacheService.saveAllAffiliations();
            logger.info("Saved all affiliations.");
//            cacheService.saveAllAuthors();
//            logger.info("Saved all authors.");
//            cacheService.saveAllForums();
//            logger.info("Saved all forums.");
//            for(Publication p : publications){
//                if(p.getDoi() != null && !p.getDoi().isEmpty())
//                    p.setId(p.getDoi());
//                else if(p.getEid() != null && !p.getEid().isEmpty())
//                    p.setId(p.getEid());
//            }
            if(!checkExisting) {
                publicationRepository.saveAll(publications);
            }else{
                for (Publication publication : publications) {
                    Optional<Publication> existingPublication = publicationRepository.findByEid(publication.getEid());
                    if (existingPublication.isEmpty()) {
                        publicationRepository.insert(publication);
                    } else {
                        logger.info("Publication with EID {} already exists, skipping.", publication.getEid());
                    }
                }
            }
            logger.info("Successfully loaded and saved {} publications.", dataSize);
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
        }
    }

    @Async("taskExecutor")
    public void importScopusDataCitations(String jsonFilePath) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            logger.info("Processing citations from {} publications from JSON file.", dataSize);

            Map<String, List<JsonNode>> citations = extractCitationsFromJson(rootNode, dataSize);
            processCitations(citations);
            logger.info("Successfully processed citations.");
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
        }
    }

    private Map<String, List<JsonNode>> extractCitationsFromJson(JsonNode rootNode, int dataSize) {
        Map<String, List<JsonNode>> citations = new HashMap<>();
        for (int i = 0; i < dataSize; i++) {
            String id = rootNode.get("eid").get(String.valueOf(i)).asText();
            JsonNode citingArticles = rootNode.get("citing articles").get(String.valueOf(i));
            if (citingArticles != null && citingArticles.getNodeType() != JsonNodeType.NUMBER) {
                citations.putIfAbsent(id, new ArrayList<>());
                for (JsonNode article : citingArticles) {
                    citations.get(id).add(article);
                }
            } else {
                logger.debug("No citations for {}", id);
            }
        }
        return citations;
    }

    private void processCitations(Map<String, List<JsonNode>> citations) {
        citations.forEach((key, citationNodes) -> {
            Optional<Publication> citedPublicationOpt = publicationRepository.findByEid(key);
            if (citedPublicationOpt.isPresent()) {
                Publication citedPublication = citedPublicationOpt.get();
                for (JsonNode citationNode : citationNodes) {
                    processSingleCitation(citedPublication, citationNode);
                }
            }
        });
    }

    private void processSingleCitation(Publication cited, JsonNode rootNode) {
        if(rootNode.get("eid") == null) {
            logger.warn("Citation has no eid: {}, {}", rootNode, rootNode.get("eid"));
            return;
        }
        String citingEid = rootNode.get("eid").asText();
        Publication citing = publicationRepository.findByEid(citingEid).orElseGet(() -> createAndSaveCitingPublication(rootNode));

        Citation citation = new Citation();
        citation.setCitedId(cited.getId());
        citation.setCitingId(citing.getId());

        if (citationRepository.findByCitedIdAndCitingId(cited.getId(), citing.getId()).isEmpty()) {
            citationRepository.insert(citation);
        }

//        logger.info("Saved citation for {}", cited.getTitle());
    }

    private Publication createAndSaveCitingPublication(JsonNode rootNode) {
        Publication publication = createPublicationFromJson(rootNode);
//        publication.setEid(rootNode.get("eid").asText());
//        setupPublication(rootNode, publication);
        handleAffiliations(publication, rootNode);
        handleAuthors(publication, rootNode);
        handleVenue(publication, rootNode);
        handleFunding(publication, rootNode);

//        if(publication.getDoi() != null && !publication.getDoi().isEmpty())
//            publication.setId(publication.getDoi());
//        else if(publication.getEid() != null && !publication.getEid().isEmpty())
//            publication.setId(publication.getEid());
//        else{
//            logger.error("Citing publication has no DOI or EID: {}", publication.getTitle());
//        }
        publicationRepository.insert(publication);
        return publication;
    }

    private void setupPublication(JsonNode rootNode, Publication publication) {
        publication.setDoi(rootNode.get("doi").asText());
        publication.setTitle(rootNode.get("title").asText());
        publication.setSubtype(rootNode.get("subtype").asText());
        publication.setScopusSubtype(rootNode.get("subtype").asText());
        publication.setCreator(rootNode.get("creator").asText());
        publication.setAuthorCount(rootNode.get("author_count").asInt());
        publication.setDescription(rootNode.get("description").asText());
        publication.setCitedbyCount(rootNode.get("citedby_count").asInt());
        publication.setOpenAccess(rootNode.get("openaccess").asInt() == 1);
        publication.setArticleNumber(rootNode.get("article_number").asText());
        publication.setPageRange(rootNode.get("pageRange").asText());
    }

    private Publication createPublicationFromJson(JsonNode rootNode, int i) {
        Publication publication = new Publication();
        publication.setEid(rootNode.get("eid").get(String.valueOf(i)).asText());
        publication.setImportId(i);
        publication.setDoi(rootNode.get("doi").get(String.valueOf(i)).asText());
        publication.setPii(rootNode.get("pii").get(String.valueOf(i)).asText());
        publication.setPubmedId(rootNode.get("pubmed_id").get(String.valueOf(i)).asText());
        publication.setTitle(rootNode.get("title").get(String.valueOf(i)).asText());
        publication.setSubtype(rootNode.get("subtype").get(String.valueOf(i)).asText());
        publication.setScopusSubtype(publication.getSubtype());
        publication.setSubtypeDescription(rootNode.get("subtypeDescription").get(String.valueOf(i)).asText());
        publication.setScopusSubtypeDescription(publication.getSubtypeDescription());
        publication.setCreator(rootNode.get("creator").get(String.valueOf(i)).asText());
        publication.setAuthorCount(rootNode.get("author_count").get(String.valueOf(i)).asInt());
        publication.setDescription(rootNode.get("description").get(String.valueOf(i)).asText());
        publication.setCitedbyCount(rootNode.get("citedby_count").get(String.valueOf(i)).asInt());
        publication.setOpenAccess(rootNode.get("openaccess").get(String.valueOf(i)).asInt() == 1);
        publication.setFreetoread(rootNode.get("freetoread").get(String.valueOf(i)).asText());
        publication.setFreetoreadLabel(rootNode.get("freetoreadLabel").get(String.valueOf(i)).asText());
        publication.setArticleNumber(rootNode.get("article_number").get(String.valueOf(i)).asText());
        publication.setPageRange(rootNode.get("pageRange").get(String.valueOf(i)).asText());
        publication.setCoverDate(rootNode.get("coverDate").get(String.valueOf(i)).asText());
        publication.setCoverDisplayDate(rootNode.get("coverDisplayDate").get(String.valueOf(i)).asText());
        publication.setVolume(rootNode.get("volume").get(String.valueOf(i)).asText());
        publication.setIssueIdentifier(rootNode.get("issueIdentifier").get(String.valueOf(i)).asText());
        return publication;
    }

    public Publication createPublicationFromJson(JsonNode rootNode) {
        Publication publication = new Publication();
        publication.setEid(rootNode.get("eid").asText());
//        publication.setImportId(i);
        publication.setDoi(rootNode.get("doi").asText());
        publication.setTitle(rootNode.get("title").asText());
        publication.setSubtype(rootNode.get("subtype").asText());
        publication.setScopusSubtype(publication.getSubtype());
        publication.setCreator(rootNode.get("creator").asText());
        publication.setAuthorCount(rootNode.get("author_count").asInt());
        publication.setDescription(rootNode.get("description").asText());
        publication.setCitedbyCount(rootNode.get("citedby_count").asInt());
        publication.setOpenAccess(rootNode.get("openaccess").asInt() == 1);
        publication.setArticleNumber(rootNode.get("article_number").asText());
        publication.setPageRange(rootNode.get("pageRange").asText());
        publication.setCoverDate(rootNode.get("coverDate").asText());
        publication.setVolume(rootNode.get("volume").asText());
        publication.setIssueIdentifier(rootNode.get("issueIdentifier").asText());
        return publication;
    }

    private void handleAffiliations(Publication publication, JsonNode rootNode, int i) {
        List<String> affiliations = new ArrayList<>();
        String[] afids = rootNode.get("afid").get(String.valueOf(i)).asText().split(";");
        String[] affilnames = rootNode.get("affilname").get(String.valueOf(i)).asText().split(";");
        String[] affilCities = rootNode.get("affiliation_city").get(String.valueOf(i)).asText().split(";");
        String[] affilCountries = rootNode.get("affiliation_country").get(String.valueOf(i)).asText().split(";");
        for (int j = 0; j < afids.length; j++) {
            Affiliation affiliation = new Affiliation();
            affiliation.setAfid(afids[j]);
            affiliation.setName(affilnames[j]);
            affiliation.setCity(getValueSafely(affilCities, j));
            affiliation.setCountry(getValueSafely(affilCountries, j));
            saveAffiliationIfNotExist(affiliation);
            affiliations.add(affiliation.getAfid());
        }
        publication.setAffiliations(affiliations);
    }

    public void handleAffiliations(Publication publication, JsonNode rootNode) {
        List<String> affiliations = new ArrayList<>();
        String[] afids = rootNode.get("afid").asText().split(";");
        String[] affilnames = rootNode.get("affilname").asText().split(";");
        String[] affilCities = rootNode.get("affiliation_city").asText().split(";");
        String[] affilCountries = rootNode.get("affiliation_country").asText().split(";");
        for (int j = 0; j < afids.length; j++) {
            Affiliation affiliation = new Affiliation();
            affiliation.setAfid(afids[j]);
            affiliation.setName(affilnames[j]);
            affiliation.setCity(getValueSafely(affilCities, j));
            affiliation.setCountry(getValueSafely(affilCountries, j));
            saveAffiliationIfNotExist(affiliation);
            affiliations.add(affiliation.getAfid());
        }
        publication.setAffiliations(affiliations);
    }

    private String getValueSafely(String[] array, int index) {
        return index < array.length ? array[index] : "";
    }

    private void saveAffiliationIfNotExist(Affiliation affiliation) {
        if (cacheService.getAffiliation(affiliation.getAfid()) == null) {
            affiliationRepository.save(affiliation);
            cacheService.putAffiliation(affiliation.getAfid(), affiliation);
            logger.info("Saved affiliation: {}", affiliation.getName());
        }
    }

    private void saveAuthorIfNotExist(Author author) {
        if (cacheService.getAuthor(author.getId()) == null) {
            authorRepository.insert(author);
            cacheService.putAuthor(author.getId(), author);
            logger.info("Saved author: {}", author.getName());
        } else {
            updateExistingAuthorAffiliations(cacheService.getAuthor(author.getId()), author.getAffiliations());
        }
    }

    private void saveForumIfNotExist(Forum forum) {
        if (cacheService.getForum(forum.getId()) == null) {
            venueRepository.save(forum);
            cacheService.putForum(forum.getId(), forum);
            logger.info("Saved forum: {}", forum.getPublicationName());
        }
    }

    private void handleAuthors(Publication publication, JsonNode rootNode, int i) {
        List<String> authors = new ArrayList<>();
        String[] authorIds = rootNode.get("author_ids").get(String.valueOf(i)).asText().split(";");
        String[] authorNames = rootNode.get("author_names").get(String.valueOf(i)).asText().split(";");
        String[] authorAfids = rootNode.get("author_afids").get(String.valueOf(i)).asText().split(";");
        int N = Math.min(authorIds.length, Math.min(authorNames.length, authorAfids.length));
        for (int j = 0; j < N; j++) {
            Author author = createAuthor(authorIds[j], authorNames[j], authorAfids[j]);
            saveAuthorIfNotExist(author);
            authors.add(author.getId());
        }
        publication.setAuthors(authors);
    }
    public void handleAuthors(Publication publication, JsonNode rootNode) {
        List<String> authors = new ArrayList<>();
        String[] authorIds = rootNode.get("author_ids").asText().split(";");
        String[] authorNames = rootNode.get("author_names").asText().split(";");
        String[] authorAfids = rootNode.get("author_afids").asText().split(";");
        int N = Math.min(authorIds.length, Math.min(authorNames.length, authorAfids.length));
        for (int j = 0; j < N; j++) {
            Author author = createAuthor(authorIds[j], authorNames[j], authorAfids[j]);
            saveAuthorIfNotExist(author);
            authors.add(author.getId());
        }
        publication.setAuthors(authors);
    }

    private Author createAuthor(String id, String name, String afids) {
        Author author = new Author();
        author.setId(id);
        author.setName(name);
        author.setAffiliations(createAffiliationsFromIds(afids));
        return author;
    }

    private List<Affiliation> createAffiliationsFromIds(String afids) {
        List<Affiliation> affiliations = new ArrayList<>();
        for (String afid : afids.split("-")) {
            affiliationRepository.findById(afid).ifPresent(affiliations::add);
        }
        return affiliations;
    }

//    private void saveAuthorIfNotExist(Author author) {
//        Optional<Author> existingAuthor = authorRepository.findById(author.getId());
//        if (existingAuthor.isEmpty()) {
//            authorRepository.insert(author);
//            logger.info("Saved author: {}", author.getName());
//        } else {
//            updateExistingAuthorAffiliations(existingAuthor.get(), author.getAffiliations());
//        }
//    }

    private void updateExistingAuthorAffiliations(Author existing, List<Affiliation> newAffiliations) {
        for (Affiliation affiliation : newAffiliations) {
            if (!existing.getAffiliations().contains(affiliation)) {
                existing.getAffiliations().add(affiliation);
            }
        }
        authorRepository.save(existing);
    }

    private void handleVenue(Publication publication, JsonNode rootNode, int i) {
        Forum forum = new Forum();
        forum.setApproved(true);
        forum.setPublicationName(rootNode.get("publicationName").get(String.valueOf(i)).asText());
        forum.setIssn(formatIssn(rootNode.get("issn").get(String.valueOf(i)).asText()));
        forum.setScopusId(rootNode.get("source_id").get(String.valueOf(i)).asText());
        forum.setId(rootNode.get("source_id").get(String.valueOf(i)).asText());
        forum.setEIssn(formatIssn(rootNode.get("eIssn").get(String.valueOf(i)).asText()));
        if(rootNode.get("isbn") != null) {
            forum.setIsbn(rootNode.get("isbn").get(String.valueOf(i)).asText());
        }
        forum.setAggregationType(rootNode.get("aggregationType").get(String.valueOf(i)).asText());
        saveForumIfNotExist(forum);
        publication.setForum(forum.getId());
    }

    public void handleVenue(Publication publication, JsonNode rootNode) {
        Forum forum = new Forum();
        forum.setPublicationName(rootNode.get("publicationName").asText());
        forum.setIssn(formatIssn(rootNode.get("issn").asText()));
        forum.setScopusId(rootNode.get("source_id").asText());
        forum.setId(rootNode.get("source_id").asText());
        forum.setEIssn(formatIssn(rootNode.get("eIssn").asText()));
        if(rootNode.get("isbn") != null) {
            forum.setIsbn(rootNode.get("isbn").asText());
        }
        forum.setAggregationType(rootNode.get("aggregationType").asText());
        saveForumIfNotExist(forum);
        publication.setForum(forum.getId());
    }

    private String formatIssn(String issn) {
        return (issn != null && !issn.isEmpty() && !issn.contains("-")) ? issn.substring(0, 4) + "-" + issn.substring(4) : issn;
    }

//    private void saveForumIfNotExist(Forum forum) {
//        if (venueRepository.findById(forum.getSourceId()).isEmpty()) {
//            venueRepository.save(forum);
//            logger.info("Saved forum: {}", forum.getPublicationName());
//        }
//    }

    private void handleFunding(Publication publication, JsonNode rootNode, int i) {
        Funding funding = new Funding();
        funding.setAcronym(rootNode.get("fund_acr").get(String.valueOf(i)).asText());
        funding.setNumber(rootNode.get("fund_no").get(String.valueOf(i)).asText());
        funding.setSponsor(rootNode.get("fund_sponsor").get(String.valueOf(i)).asText());
        if (funding.getAcronym() != null) {
            funding = saveFundingIfNotExist(funding);
            publication.setFundingId(funding.getId());
        }
    }

    public void handleFunding(Publication publication, JsonNode rootNode) {
        Funding funding = new Funding();
        funding.setAcronym(rootNode.get("fund_acr").asText());
        funding.setNumber(rootNode.get("fund_no").asText());
        funding.setSponsor(rootNode.get("fund_sponsor").asText());
        if (funding.getAcronym() != null) {
            funding = saveFundingIfNotExist(funding);
            publication.setFundingId(funding.getId());
        }
    }


    private Funding saveFundingIfNotExist(Funding funding) {
        if (fundingRepository.findByAcronymAndNumberAndSponsor(funding.getAcronym(), funding.getNumber(), funding.getSponsor()).isEmpty()) {
            return fundingRepository.save(funding);
//            logger.info("Saved funding: {}/{}", funding.getAcronym(), funding.getNumber());
        }
        return funding;
    }
}

