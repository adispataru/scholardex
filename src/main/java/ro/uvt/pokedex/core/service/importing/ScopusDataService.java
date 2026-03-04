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
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class ScopusDataService {

    private static final Logger logger = LoggerFactory.getLogger(ScopusDataService.class);
    private static final int DEFAULT_ERROR_SAMPLE_SIZE = 20;

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
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            logger.info("Processing starting at {} of {} publications from JSON file.", count, dataSize);
            List<Publication> publications = new ArrayList<>();
            for (int i = (int) count; i < dataSize; i++) {
                result.markProcessed();
                try {
                    Publication publication = createPublicationFromJson(rootNode, i);
                    publication.setApproved(true);
                    handleAffiliations(publication, rootNode, i);
                    handleAuthors(publication, rootNode, i);
                    handleVenue(publication, rootNode, i);
                    handleFunding(publication, rootNode, i);
                    publications.add(publication);
                    result.markImported();
                } catch (IntegrationException ex) {
                    result.markSkipped("index=" + i + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
                } catch (RuntimeException ex) {
                    result.markSkipped("index=" + i + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
                }
                if(dataSize >= 10 && i % (dataSize / 10) == 0)
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
                        result.markUpdated();
                        logger.info("Publication with EID {} already exists, skipping.", publication.getEid());
                    }
                }
            }
            logger.info("Scopus publication import finished: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    result.getErrorsSample());
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
        }
    }

    @Async("taskExecutor")
    public void importScopusDataCitations(String jsonFilePath) {
        ObjectMapper mapper = new ObjectMapper();
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            logger.info("Processing citations from {} publications from JSON file.", dataSize);

            Map<String, List<JsonNode>> citations = extractCitationsFromJson(rootNode, dataSize);
            processCitations(citations, result);
            logger.info("Scopus citation import finished: processed={}, imported={}, skipped={}, errors={}, sample={}",
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    result.getErrorsSample());
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
        }
    }

    private Map<String, List<JsonNode>> extractCitationsFromJson(JsonNode rootNode, int dataSize) {
        Map<String, List<JsonNode>> citations = new HashMap<>();
        for (int i = 0; i < dataSize; i++) {
            String id = readOptionalIndexedText(rootNode, "eid", i);
            if (id.isBlank()) {
                continue;
            }
            JsonNode citingArticles = rootNode.path("citing articles").path(String.valueOf(i));
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

    private void processCitations(Map<String, List<JsonNode>> citations, ImportProcessingResult result) {
        citations.forEach((key, citationNodes) -> {
            Optional<Publication> citedPublicationOpt = publicationRepository.findByEid(key);
            if (citedPublicationOpt.isPresent()) {
                Publication citedPublication = citedPublicationOpt.get();
                for (JsonNode citationNode : citationNodes) {
                    result.markProcessed();
                    try {
                        processSingleCitation(citedPublication, citationNode);
                        result.markImported();
                    } catch (IntegrationException ex) {
                        result.markSkipped("citedEid=" + key + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
                    } catch (RuntimeException ex) {
                        result.markSkipped("citedEid=" + key + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
                    }
                }
            }
        });
    }

    private void processSingleCitation(Publication cited, JsonNode rootNode) {
        if(rootNode.get("eid") == null) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Citation payload missing required field: eid"
            );
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
        String ctx = "scopus-import-index-" + i;
        publication.setEid(readRequiredIndexedText(rootNode, "eid", i, ctx));
        publication.setImportId(i);
        publication.setDoi(readOptionalIndexedText(rootNode, "doi", i));
        publication.setPii(readOptionalIndexedText(rootNode, "pii", i));
        publication.setPubmedId(readOptionalIndexedText(rootNode, "pubmed_id", i));
        publication.setTitle(readRequiredIndexedText(rootNode, "title", i, ctx));
        publication.setSubtype(readOptionalIndexedText(rootNode, "subtype", i));
        publication.setScopusSubtype(publication.getSubtype());
        publication.setSubtypeDescription(readOptionalIndexedText(rootNode, "subtypeDescription", i));
        publication.setScopusSubtypeDescription(publication.getSubtypeDescription());
        publication.setCreator(readOptionalIndexedText(rootNode, "creator", i));
        publication.setAuthorCount(readIndexedInt(rootNode, "author_count", i));
        publication.setDescription(readOptionalIndexedText(rootNode, "description", i));
        publication.setCitedbyCount(readIndexedInt(rootNode, "citedby_count", i));
        publication.setOpenAccess(readIndexedInt(rootNode, "openaccess", i) == 1);
        publication.setFreetoread(readOptionalIndexedText(rootNode, "freetoread", i));
        publication.setFreetoreadLabel(readOptionalIndexedText(rootNode, "freetoreadLabel", i));
        publication.setArticleNumber(readOptionalIndexedText(rootNode, "article_number", i));
        publication.setPageRange(readOptionalIndexedText(rootNode, "pageRange", i));
        publication.setCoverDate(readOptionalIndexedText(rootNode, "coverDate", i));
        publication.setCoverDisplayDate(readOptionalIndexedText(rootNode, "coverDisplayDate", i));
        publication.setVolume(readOptionalIndexedText(rootNode, "volume", i));
        publication.setIssueIdentifier(readOptionalIndexedText(rootNode, "issueIdentifier", i));
        return publication;
    }

    public Publication createPublicationFromJson(JsonNode rootNode) {
        Publication publication = new Publication();
        String ctx = "scopus-runtime-item";
        publication.setEid(readRequiredText(rootNode, "eid", ctx));
//        publication.setImportId(i);
        publication.setDoi(readOptionalText(rootNode, "doi"));
        publication.setTitle(readRequiredText(rootNode, "title", ctx));
        publication.setSubtype(readOptionalText(rootNode, "subtype"));
        publication.setScopusSubtype(publication.getSubtype());
        publication.setCreator(readOptionalText(rootNode, "creator"));
        publication.setAuthorCount(readInt(rootNode, "author_count"));
        publication.setDescription(readOptionalText(rootNode, "description"));
        publication.setCitedbyCount(readInt(rootNode, "citedby_count"));
        publication.setOpenAccess(readInt(rootNode, "openaccess") == 1);
        publication.setArticleNumber(readOptionalText(rootNode, "article_number"));
        publication.setPageRange(readOptionalText(rootNode, "pageRange"));
        publication.setCoverDate(readOptionalText(rootNode, "coverDate"));
        publication.setVolume(readOptionalText(rootNode, "volume"));
        publication.setIssueIdentifier(readOptionalText(rootNode, "issueIdentifier"));
        return publication;
    }

    private void handleAffiliations(Publication publication, JsonNode rootNode, int i) {
        List<String> affiliations = new ArrayList<>();
        String[] afids = splitSemicolon(readOptionalIndexedText(rootNode, "afid", i));
        String[] affilnames = splitSemicolon(readOptionalIndexedText(rootNode, "affilname", i));
        String[] affilCities = splitSemicolon(readOptionalIndexedText(rootNode, "affiliation_city", i));
        String[] affilCountries = splitSemicolon(readOptionalIndexedText(rootNode, "affiliation_country", i));
        for (int j = 0; j < afids.length; j++) {
            Affiliation affiliation = new Affiliation();
            affiliation.setAfid(afids[j]);
            affiliation.setName(getValueSafely(affilnames, j));
            affiliation.setCity(getValueSafely(affilCities, j));
            affiliation.setCountry(getValueSafely(affilCountries, j));
            saveAffiliationIfNotExist(affiliation);
            affiliations.add(affiliation.getAfid());
        }
        publication.setAffiliations(affiliations);
    }

    public void handleAffiliations(Publication publication, JsonNode rootNode) {
        List<String> affiliations = new ArrayList<>();
        String[] afids = splitSemicolon(readOptionalText(rootNode, "afid"));
        String[] affilnames = splitSemicolon(readOptionalText(rootNode, "affilname"));
        String[] affilCities = splitSemicolon(readOptionalText(rootNode, "affiliation_city"));
        String[] affilCountries = splitSemicolon(readOptionalText(rootNode, "affiliation_country"));
        for (int j = 0; j < afids.length; j++) {
            Affiliation affiliation = new Affiliation();
            affiliation.setAfid(afids[j]);
            affiliation.setName(getValueSafely(affilnames, j));
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
        String[] authorIds = splitSemicolon(readOptionalIndexedText(rootNode, "author_ids", i));
        String[] authorNames = splitSemicolon(readOptionalIndexedText(rootNode, "author_names", i));
        String[] authorAfids = splitSemicolon(readOptionalIndexedText(rootNode, "author_afids", i));
        int N = Math.min(authorIds.length, Math.min(authorNames.length, authorAfids.length));
        for (int j = 0; j < N; j++) {
            Author author = createAuthor(getValueSafely(authorIds, j), getValueSafely(authorNames, j), getValueSafely(authorAfids, j));
            saveAuthorIfNotExist(author);
            authors.add(author.getId());
        }
        publication.setAuthors(authors);
    }
    public void handleAuthors(Publication publication, JsonNode rootNode) {
        List<String> authors = new ArrayList<>();
        String[] authorIds = splitSemicolon(readOptionalText(rootNode, "author_ids"));
        String[] authorNames = splitSemicolon(readOptionalText(rootNode, "author_names"));
        String[] authorAfids = splitSemicolon(readOptionalText(rootNode, "author_afids"));
        int N = Math.min(authorIds.length, Math.min(authorNames.length, authorAfids.length));
        for (int j = 0; j < N; j++) {
            Author author = createAuthor(getValueSafely(authorIds, j), getValueSafely(authorNames, j), getValueSafely(authorAfids, j));
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
        String sourceId = readRequiredIndexedText(rootNode, "source_id", i, "forum-source-id-" + i);
        forum.setPublicationName(readOptionalIndexedText(rootNode, "publicationName", i));
        forum.setIssn(formatIssn(readOptionalIndexedText(rootNode, "issn", i)));
        forum.setScopusId(sourceId);
        forum.setId(sourceId);
        forum.setEIssn(formatIssn(readOptionalIndexedText(rootNode, "eIssn", i)));
        if(rootNode.get("isbn") != null) {
            forum.setIsbn(readOptionalIndexedText(rootNode, "isbn", i));
        }
        forum.setAggregationType(readOptionalIndexedText(rootNode, "aggregationType", i));
        saveForumIfNotExist(forum);
        publication.setForum(forum.getId());
    }

    public void handleVenue(Publication publication, JsonNode rootNode) {
        Forum forum = new Forum();
        String sourceId = readRequiredText(rootNode, "source_id", "forum-source-id");
        forum.setPublicationName(readOptionalText(rootNode, "publicationName"));
        forum.setIssn(formatIssn(readOptionalText(rootNode, "issn")));
        forum.setScopusId(sourceId);
        forum.setId(sourceId);
        forum.setEIssn(formatIssn(readOptionalText(rootNode, "eIssn")));
        if(rootNode.get("isbn") != null) {
            forum.setIsbn(readOptionalText(rootNode, "isbn"));
        }
        forum.setAggregationType(readOptionalText(rootNode, "aggregationType"));
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
        funding.setAcronym(readOptionalIndexedText(rootNode, "fund_acr", i));
        funding.setNumber(readOptionalIndexedText(rootNode, "fund_no", i));
        funding.setSponsor(readOptionalIndexedText(rootNode, "fund_sponsor", i));
        if (funding.getAcronym() != null && !funding.getAcronym().isBlank()) {
            funding = saveFundingIfNotExist(funding);
            publication.setFundingId(funding.getId());
        }
    }

    public void handleFunding(Publication publication, JsonNode rootNode) {
        Funding funding = new Funding();
        funding.setAcronym(readOptionalText(rootNode, "fund_acr"));
        funding.setNumber(readOptionalText(rootNode, "fund_no"));
        funding.setSponsor(readOptionalText(rootNode, "fund_sponsor"));
        if (funding.getAcronym() != null && !funding.getAcronym().isBlank()) {
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

    private String readRequiredIndexedText(JsonNode node, String field, int index, String contextId) {
        JsonNode fieldNode = node.path(field).path(String.valueOf(index));
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Missing required field '" + field + "' at index " + index + " (" + contextId + ")"
            );
        }
        String value = fieldNode.asText("").trim();
        if (value.isBlank()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Blank required field '" + field + "' at index " + index + " (" + contextId + ")"
            );
        }
        return value;
    }

    private String readRequiredText(JsonNode node, String field, String contextId) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Missing required field '" + field + "' (" + contextId + ")"
            );
        }
        String value = fieldNode.asText("").trim();
        if (value.isBlank()) {
            throw new IntegrationException(
                    IntegrationErrorCode.VALIDATION_ERROR,
                    false,
                    "Blank required field '" + field + "' (" + contextId + ")"
            );
        }
        return value;
    }

    private String readOptionalIndexedText(JsonNode node, String field, int index) {
        JsonNode fieldNode = node.path(field).path(String.valueOf(index));
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return "";
        }
        return normalizeOptionalValue(fieldNode.asText(""));
    }

    private String readOptionalText(JsonNode node, String field) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return "";
        }
        return normalizeOptionalValue(fieldNode.asText(""));
    }

    private int readIndexedInt(JsonNode node, String field, int index) {
        JsonNode fieldNode = node.path(field).path(String.valueOf(index));
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return 0;
        }
        if (fieldNode.canConvertToInt()) {
            return fieldNode.asInt();
        }
        try {
            return Integer.parseInt(fieldNode.asText("").trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private int readInt(JsonNode node, String field) {
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return 0;
        }
        if (fieldNode.canConvertToInt()) {
            return fieldNode.asInt();
        }
        try {
            return Integer.parseInt(fieldNode.asText("").trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private String[] splitSemicolon(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return value.split(";");
    }

    private String normalizeOptionalValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.equalsIgnoreCase("null") || normalized.equalsIgnoreCase("n/a")) {
            return "";
        }
        return normalized;
    }
}
