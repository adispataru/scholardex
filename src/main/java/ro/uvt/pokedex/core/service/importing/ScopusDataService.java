package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.*;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.*;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalBuildOptions;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class ScopusDataService {

    private static final Logger logger = LoggerFactory.getLogger(ScopusDataService.class);
    private static final int DEFAULT_ERROR_SAMPLE_SIZE = 20;
    private static final String PAYLOAD_FORMAT_JSON_OBJECT = "json-object";
    private static final String SOURCE_SCOPUS_JSON_BOOTSTRAP = "SCOPUS_JSON_BOOTSTRAP";
    private static final int INGEST_HEARTBEAT = 5_000;
    private static final int CITATION_INGEST_BATCH_SIZE = 1_000;
    private static final CanonicalBuildOptions BOOTSTRAP_FULL_RESCAN_OPTIONS =
            new CanonicalBuildOptions(null, null, true, null, false, false, false, false, true);

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
    @Autowired
    private ScopusImportEventRepository importEventRepository;
    @Autowired
    private ScopusImportEventIngestionService importEventIngestionService;
    @Autowired
    private ScopusCanonicalMaterializationService canonicalMaterializationService;

    @Async("taskExecutor")
    public void loadScopusDataIfEmpty(String scopusDataFile) {
        loadScopusDataIfEmptySync(scopusDataFile);
    }

    public boolean loadScopusDataIfEmptySync(String scopusDataFile) {
        if (importEventRepository.count() == 0) {
            importScopusDataSync(scopusDataFile, 0, false);
            importScopusDataCitationsSync(scopusDataFile);
            canonicalMaterializationService.rebuildFactsAndViews("bootstrap-empty-load", null, BOOTSTRAP_FULL_RESCAN_OPTIONS);
            return true;
        }
        return false;
    }

    @Async("taskExecutor")
    public void loadAdditionalScopusData(String scopusDataFile) {
        loadAdditionalScopusDataSync(scopusDataFile);
    }

    public void loadAdditionalScopusDataSync(String scopusDataFile) {
        importScopusDataSync(scopusDataFile, 0, true);
        importScopusDataCitationsSync(scopusDataFile);
        canonicalMaterializationService.rebuildFactsAndViews("bootstrap-additional-load", null, BOOTSTRAP_FULL_RESCAN_OPTIONS);
    }

    @Async("taskExecutor")
    public void importScopusData(String jsonFilePath, long count, boolean checkExisting) {
        importScopusDataSync(jsonFilePath, count, checkExisting);
    }

    public ImportProcessingResult importScopusDataSync(String jsonFilePath, long count, boolean checkExisting) {
        ObjectMapper mapper = new ObjectMapper();
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            String batchId = "bootstrap-publications-" + new File(jsonFilePath).getName() + "-" + System.currentTimeMillis();
            logger.info("Processing starting at {} of {} publications from JSON file.", count, dataSize);
            long startedAtNanos = System.nanoTime();
            for (int i = (int) count; i < dataSize; i++) {
                result.markProcessed();
                try {
                    String eid = readRequiredIndexedText(rootNode, "eid", i, "scopus-import-index-" + i);
                    Map<String, Object> payload = extractIndexedPublicationPayload(rootNode, i);
                    ScopusImportEventIngestionService.EventIngestionOutcome outcome = importEventIngestionService.ingest(
                            ScopusImportEntityType.PUBLICATION,
                            SOURCE_SCOPUS_JSON_BOOTSTRAP,
                            eid,
                            batchId,
                            "bootstrap-publication-" + i,
                            PAYLOAD_FORMAT_JSON_OBJECT,
                            payload,
                            false
                    );
                    applyIngestionOutcome(result, outcome, "publication index=" + i + ", eid=" + eid);
                } catch (IntegrationException ex) {
                    result.markSkipped("index=" + i + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
                } catch (RuntimeException ex) {
                    result.markSkipped("index=" + i + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
                }
                if(dataSize >= 10 && i % (dataSize / 10) == 0)
                    logger.info("Processed {}% publications.", (i* 100.0)/dataSize );
                if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                    double rate = elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs;
                    logger.info("Scopus publication ingest progress: processed={} imported={} skipped={} errors={} elapsedMs={} ratePerSec={}",
                            result.getProcessedCount(),
                            result.getImportedCount(),
                            result.getSkippedCount(),
                            result.getErrorCount(),
                            elapsedMs,
                            String.format(Locale.ROOT, "%.2f", rate));
                }
            }
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            logger.info("Scopus publication import finished: processed={}, imported={}, updated={}, skipped={}, errors={}, sample={}",
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    result.getErrorsSample());
            logger.info("Scopus publication ingest timings: elapsedMs={}, ratePerSec={}",
                    elapsedMs,
                    String.format(Locale.ROOT, "%.2f", elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs));
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
            result.markError("scopus-publication-import-io-error=" + e.getMessage());
        }
        return result;
    }

    @Async("taskExecutor")
    public void importScopusDataCitations(String jsonFilePath) {
        importScopusDataCitationsSync(jsonFilePath);
    }

    public ImportProcessingResult importScopusDataCitationsSync(String jsonFilePath) {
        ObjectMapper mapper = new ObjectMapper();
        ImportProcessingResult result = new ImportProcessingResult(DEFAULT_ERROR_SAMPLE_SIZE);
        try {
            JsonNode rootNode = mapper.readTree(new File(jsonFilePath));
            int dataSize = rootNode.get("eid").size();
            String batchId = "bootstrap-citations-" + new File(jsonFilePath).getName() + "-" + System.currentTimeMillis();
            logger.info("Processing citations from {} publications from JSON file.", dataSize);
            long startedAtNanos = System.nanoTime();

            Map<String, List<JsonNode>> citations = extractCitationsFromJson(rootNode, dataSize);
            processCitations(citations, batchId, result, startedAtNanos);
            logger.info("Scopus citation import finished: processed={}, imported={}, skipped={}, errors={}, sample={}",
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    result.getErrorsSample());
            long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            logger.info("Scopus citation ingest timings: elapsedMs={}, ratePerSec={}",
                    elapsedMs,
                    String.format(Locale.ROOT, "%.2f", elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs));
        } catch (IOException e) {
            logger.error("Error reading the JSON file: ", e);
            result.markError("scopus-citation-import-io-error=" + e.getMessage());
        }
        return result;
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

    private void processCitations(Map<String, List<JsonNode>> citations, String batchId, ImportProcessingResult result, long startedAtNanos) {
        List<ScopusImportEventIngestionService.BatchIngestionItem> pendingPublicationEvents = new ArrayList<>(CITATION_INGEST_BATCH_SIZE);
        List<ScopusImportEventIngestionService.BatchIngestionItem> pendingCitationEvents = new ArrayList<>(CITATION_INGEST_BATCH_SIZE);
        long cumulativePublicationSerializeMs = 0L;
        long cumulativePublicationDbMs = 0L;
        long cumulativeCitationSerializeMs = 0L;
        long cumulativeCitationDbMs = 0L;
        long cumulativeTouchMs = 0L;
        int sequence = 0;

        for (Map.Entry<String, List<JsonNode>> entry : citations.entrySet()) {
            String citedEid = entry.getKey();
            List<JsonNode> citationNodes = entry.getValue();
            if (citationNodes == null) {
                continue;
            }
            for (JsonNode citationNode : citationNodes) {
                result.markProcessed();
                sequence++;
                try {
                    String citingEid = readRequiredText(citationNode, "eid", "citation-citing-eid");
                    pendingPublicationEvents.add(new ScopusImportEventIngestionService.BatchIngestionItem(
                            citingEid,
                            "bootstrap-citation-publication-" + citedEid + "-" + sequence,
                            citationNode
                    ));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("citedEid", citedEid);
                    payload.put("citingEid", citingEid);
                    pendingCitationEvents.add(new ScopusImportEventIngestionService.BatchIngestionItem(
                            citedEid + "->" + citingEid,
                            "bootstrap-citation-" + citedEid + "-" + sequence,
                            payload
                    ));
                } catch (IntegrationException ex) {
                    result.markSkipped("citedEid=" + citedEid + ", code=" + ex.getErrorCode() + ", msg=" + ex.getMessage());
                } catch (RuntimeException ex) {
                    result.markSkipped("citedEid=" + citedEid + ", code=" + IntegrationErrorCode.PERSISTENCE_ERROR + ", msg=" + ex.getMessage());
                }

                if (pendingCitationEvents.size() >= CITATION_INGEST_BATCH_SIZE) {
                    CitationBatchOutcome batchOutcome = flushCitationBatch(pendingPublicationEvents, pendingCitationEvents, batchId);
                    applyBatchOutcome(result, batchOutcome.citationOutcome());
                    cumulativePublicationSerializeMs += batchOutcome.publicationOutcome().serializeMs();
                    cumulativePublicationDbMs += batchOutcome.publicationOutcome().dbInsertEventMs();
                    cumulativeCitationSerializeMs += batchOutcome.citationOutcome().serializeMs();
                    cumulativeCitationDbMs += batchOutcome.citationOutcome().dbInsertEventMs();
                    cumulativeTouchMs += batchOutcome.publicationOutcome().touchQueueUpsertMs() + batchOutcome.citationOutcome().touchQueueUpsertMs();
                    pendingPublicationEvents.clear();
                    pendingCitationEvents.clear();
                }

                if (result.getProcessedCount() % INGEST_HEARTBEAT == 0) {
                    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                    double rate = elapsedMs == 0 ? 0.0 : (result.getProcessedCount() * 1000.0) / elapsedMs;
                    logger.info("Scopus citation ingest progress: processed={} imported={} skipped={} errors={} elapsedMs={} ratePerSec={} timingsMs[publicationSerialize={}, citationSerialize={}, publicationEventInsert={}, citationEventInsert={}, touchQueueUpsert={}, total={}]",
                            result.getProcessedCount(),
                            result.getImportedCount(),
                            result.getSkippedCount(),
                            result.getErrorCount(),
                            elapsedMs,
                            String.format(Locale.ROOT, "%.2f", rate),
                            cumulativePublicationSerializeMs,
                            cumulativeCitationSerializeMs,
                            cumulativePublicationDbMs,
                            cumulativeCitationDbMs,
                            cumulativeTouchMs,
                            cumulativePublicationSerializeMs + cumulativeCitationSerializeMs + cumulativePublicationDbMs + cumulativeCitationDbMs + cumulativeTouchMs);
                }
            }
        }
        if (!pendingCitationEvents.isEmpty()) {
            CitationBatchOutcome batchOutcome = flushCitationBatch(pendingPublicationEvents, pendingCitationEvents, batchId);
            applyBatchOutcome(result, batchOutcome.citationOutcome());
        }
    }

    private CitationBatchOutcome flushCitationBatch(
            List<ScopusImportEventIngestionService.BatchIngestionItem> pendingPublicationEvents,
            List<ScopusImportEventIngestionService.BatchIngestionItem> pendingCitationEvents,
            String batchId
    ) {
        ScopusImportEventIngestionService.BatchIngestionOutcome publicationOutcome = importEventIngestionService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                SOURCE_SCOPUS_JSON_BOOTSTRAP,
                batchId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                pendingPublicationEvents,
                false
        );
        ScopusImportEventIngestionService.BatchIngestionOutcome citationOutcome = importEventIngestionService.ingestBatch(
                ScopusImportEntityType.CITATION,
                SOURCE_SCOPUS_JSON_BOOTSTRAP,
                batchId,
                PAYLOAD_FORMAT_JSON_OBJECT,
                pendingCitationEvents,
                false
        );
        return new CitationBatchOutcome(publicationOutcome, citationOutcome);
    }

    private void applyBatchOutcome(
            ImportProcessingResult result,
            ScopusImportEventIngestionService.BatchIngestionOutcome batchOutcome
    ) {
        for (int i = 0; i < batchOutcome.imported(); i++) {
            result.markImported();
        }
        for (int i = 0; i < batchOutcome.skipped(); i++) {
            result.markSkipped("citation-batch-duplicate");
        }
        for (int i = 0; i < batchOutcome.errors(); i++) {
            result.markError("citation-batch-error");
        }
    }

    private record CitationBatchOutcome(
            ScopusImportEventIngestionService.BatchIngestionOutcome publicationOutcome,
            ScopusImportEventIngestionService.BatchIngestionOutcome citationOutcome
    ) {
    }

    private void applyIngestionOutcome(ImportProcessingResult result,
                                       ScopusImportEventIngestionService.EventIngestionOutcome outcome,
                                       String context) {
        if (outcome.error()) {
            result.markError(context + ", msg=" + outcome.message());
            return;
        }
        if (outcome.imported()) {
            result.markImported();
            return;
        }
        result.markSkipped(context + ", reason=duplicate");
    }

    private Map<String, Object> extractIndexedPublicationPayload(JsonNode rootNode, int i) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", readOptionalIndexedText(rootNode, "eid", i));
        payload.put("doi", readOptionalIndexedText(rootNode, "doi", i));
        payload.put("pii", readOptionalIndexedText(rootNode, "pii", i));
        payload.put("pubmed_id", readOptionalIndexedText(rootNode, "pubmed_id", i));
        payload.put("title", readOptionalIndexedText(rootNode, "title", i));
        payload.put("subtype", readOptionalIndexedText(rootNode, "subtype", i));
        payload.put("subtypeDescription", readOptionalIndexedText(rootNode, "subtypeDescription", i));
        payload.put("creator", readOptionalIndexedText(rootNode, "creator", i));
        payload.put("author_count", readIndexedInt(rootNode, "author_count", i));
        payload.put("description", readOptionalIndexedText(rootNode, "description", i));
        payload.put("citedby_count", readIndexedInt(rootNode, "citedby_count", i));
        payload.put("openaccess", readIndexedInt(rootNode, "openaccess", i));
        payload.put("freetoread", readOptionalIndexedText(rootNode, "freetoread", i));
        payload.put("freetoreadLabel", readOptionalIndexedText(rootNode, "freetoreadLabel", i));
        payload.put("article_number", readOptionalIndexedText(rootNode, "article_number", i));
        payload.put("pageRange", readOptionalIndexedText(rootNode, "pageRange", i));
        payload.put("coverDate", readOptionalIndexedText(rootNode, "coverDate", i));
        payload.put("coverDisplayDate", readOptionalIndexedText(rootNode, "coverDisplayDate", i));
        payload.put("volume", readOptionalIndexedText(rootNode, "volume", i));
        payload.put("issueIdentifier", readOptionalIndexedText(rootNode, "issueIdentifier", i));
        payload.put("afid", readOptionalIndexedText(rootNode, "afid", i));
        payload.put("affilname", readOptionalIndexedText(rootNode, "affilname", i));
        payload.put("affiliation_city", readOptionalIndexedText(rootNode, "affiliation_city", i));
        payload.put("affiliation_country", readOptionalIndexedText(rootNode, "affiliation_country", i));
        payload.put("author_ids", readOptionalIndexedText(rootNode, "author_ids", i));
        payload.put("author_names", readOptionalIndexedText(rootNode, "author_names", i));
        payload.put("author_afids", readOptionalIndexedText(rootNode, "author_afids", i));
        payload.put("source_id", readOptionalIndexedText(rootNode, "source_id", i));
        payload.put("publicationName", readOptionalIndexedText(rootNode, "publicationName", i));
        payload.put("issn", readOptionalIndexedText(rootNode, "issn", i));
        payload.put("eIssn", readOptionalIndexedText(rootNode, "eIssn", i));
        payload.put("isbn", readOptionalIndexedText(rootNode, "isbn", i));
        payload.put("aggregationType", readOptionalIndexedText(rootNode, "aggregationType", i));
        payload.put("fund_acr", readOptionalIndexedText(rootNode, "fund_acr", i));
        payload.put("fund_no", readOptionalIndexedText(rootNode, "fund_no", i));
        payload.put("fund_sponsor", readOptionalIndexedText(rootNode, "fund_sponsor", i));
        return payload;
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
