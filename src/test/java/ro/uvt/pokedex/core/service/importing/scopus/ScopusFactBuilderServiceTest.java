package ro.uvt.pokedex.core.service.importing.scopus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusFundingFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusFactBuilderServiceTest {

    @Mock private ScopusImportEventRepository importEventRepository;
    @Mock private ScopusPublicationFactRepository publicationFactRepository;
    @Mock private ScopusCitationFactRepository citationFactRepository;
    @Mock private ScopusForumFactRepository forumFactRepository;
    @Mock private ScopusAuthorFactRepository authorFactRepository;
    @Mock private ScopusAffiliationFactRepository affiliationFactRepository;
    @Mock private ScopusFundingFactRepository fundingFactRepository;

    private ScopusFactBuilderService service;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ScopusFactBuilderService(
                importEventRepository,
                publicationFactRepository,
                citationFactRepository,
                forumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                fundingFactRepository,
                mapper
        );
        serviceLogger = (Logger) LoggerFactory.getLogger(ScopusFactBuilderService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    void buildFactsFromImportEventsBuildsPublicationAndCitationFacts() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setId("ev1");
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        publicationEvent.setSourceRecordId("2-s2.0-p1");
        publicationEvent.setBatchId("b1");
        publicationEvent.setCorrelationId("c1");
        publicationEvent.setPayloadHash("payload-hash-p1");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-p1"),
                java.util.Map.entry("title", "Paper 1"),
                java.util.Map.entry("subtype", "ar"),
                java.util.Map.entry("subtypeDescription", "Article"),
                java.util.Map.entry("creator", "Creator 1"),
                java.util.Map.entry("author_ids", "a1;a2"),
                java.util.Map.entry("author_names", "Alice;Bob"),
                java.util.Map.entry("author_afids", "af1-af2;af2"),
                java.util.Map.entry("afid", "af1;af2"),
                java.util.Map.entry("affilname", "Aff 1;Aff 2"),
                java.util.Map.entry("affiliation_city", "City1;City2"),
                java.util.Map.entry("affiliation_country", "RO;RO"),
                java.util.Map.entry("correspondingAuthors", "a1"),
                java.util.Map.entry("source_id", "f1"),
                java.util.Map.entry("publicationName", "Forum 1"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "87654321"),
                java.util.Map.entry("aggregationType", "Journal"),
                java.util.Map.entry("doi", "10.1000/example"),
                java.util.Map.entry("author_count", "2"),
                java.util.Map.entry("volume", "42"),
                java.util.Map.entry("issueIdentifier", "7"),
                java.util.Map.entry("fund_acr", "PNRR"),
                java.util.Map.entry("fund_no", "123"),
                java.util.Map.entry("fund_sponsor", "UEFISCDI"),
                java.util.Map.entry("coverDate", "2025-01-01"),
                java.util.Map.entry("coverDisplayDate", "January 2025"),
                java.util.Map.entry("description", "Abstract"),
                java.util.Map.entry("openaccess", "true"),
                java.util.Map.entry("freetoread", "all"),
                java.util.Map.entry("freetoreadLabel", "Open"),
                java.util.Map.entry("article_number", "A-7"),
                java.util.Map.entry("pageRange", "12-19"),
                java.util.Map.entry("approved", "1"),
                java.util.Map.entry("citedby_count", 10)
        )));

        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setId("ev2");
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citationEvent.setBatchId("b1");
        citationEvent.setCorrelationId("c2");
        citationEvent.setPayloadHash("payload-hash-c1");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "2-s2.0-p1",
                "citingEid", "2-s2.0-c1"
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(citationEvent, publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(2, result.getProcessedCount());
        assertTrue(result.getImportedCount() >= 2);
        ArgumentCaptor<java.util.Collection<ScopusPublicationFact>> publicationCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(publicationFactRepository, atLeastOnce()).saveAll(publicationCaptor.capture());
        ScopusPublicationFact savedPub = publicationCaptor.getValue().iterator().next();
        assertEquals("2-s2.0-p1", savedPub.getEid());
        assertEquals("10.1000/example", savedPub.getDoi());
        assertEquals("Paper 1", savedPub.getTitle());
        assertEquals("ar", savedPub.getSubtype());
        assertEquals("Article", savedPub.getSubtypeDescription());
        assertEquals("ar", savedPub.getScopusSubtype());
        assertEquals("Article", savedPub.getScopusSubtypeDescription());
        assertEquals("Creator 1", savedPub.getCreator());
        assertEquals(2, savedPub.getAuthorCount());
        assertEquals(10, savedPub.getCitedByCount());
        assertEquals("2025-01-01", savedPub.getCoverDate());
        assertEquals("January 2025", savedPub.getCoverDisplayDate());
        assertEquals("Abstract", savedPub.getDescription());
        assertEquals("42", savedPub.getVolume());
        assertEquals("7", savedPub.getIssueIdentifier());
        assertEquals(Boolean.TRUE, savedPub.getOpenAccess());
        assertEquals("all", savedPub.getFreetoread());
        assertEquals("Open", savedPub.getFreetoreadLabel());
        assertEquals("pnrr|123|uefiscdi", savedPub.getFundingId());
        assertEquals("A-7", savedPub.getArticleNumber());
        assertEquals("12-19", savedPub.getPageRange());
        assertEquals(Boolean.TRUE, savedPub.getApproved());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", savedPub.getSource());
        assertEquals("2-s2.0-p1", savedPub.getSourceRecordId());
        assertEquals("b1", savedPub.getSourceBatchId());
        assertEquals("c1", savedPub.getSourceCorrelationId());
        assertEquals("f1", savedPub.getForumId());
        assertEquals(List.of("a1", "a2"), savedPub.getAuthors());
        assertEquals(List.of("af1-af2", "af2"), savedPub.getAuthorAffiliationSourceIds());
        assertEquals(List.of("a1"), savedPub.getCorrespondingAuthors());
        assertEquals(List.of("af1", "af2"), savedPub.getAffiliations());
        assertNotNull(savedPub.getLastPayloadHash());
        assertNotNull(savedPub.getLastMaterializedAt());
        assertNotNull(savedPub.getCreatedAt());
        assertNotNull(savedPub.getUpdatedAt());

        ArgumentCaptor<java.util.Collection<ScopusForumFact>> forumCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(forumFactRepository, atLeastOnce()).saveAll(forumCaptor.capture());
        ScopusForumFact savedForum = forumCaptor.getValue().iterator().next();
        assertEquals("f1", savedForum.getSourceId());
        assertEquals("Forum 1", savedForum.getPublicationName());
        assertEquals("1234-5678", savedForum.getIssn());
        assertEquals("8765-4321", savedForum.getEIssn());
        assertEquals("Journal", savedForum.getAggregationType());
        // H66B M6: a venue seen only in a publication (no authoritative source) is provenance-tagged.
        assertEquals("SCOPUS_OBSERVED_VENUE", savedForum.getSource());
        assertEquals("b1", savedForum.getSourceBatchId());
        assertEquals("c1", savedForum.getSourceCorrelationId());
        assertNotNull(savedForum.getLastPayloadHash());
        assertNotNull(savedForum.getLastMaterializedAt());
        assertNotNull(savedForum.getCreatedAt());
        assertNotNull(savedForum.getUpdatedAt());

        ArgumentCaptor<java.util.Collection<ScopusFundingFact>> fundingCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(fundingFactRepository, atLeastOnce()).saveAll(fundingCaptor.capture());
        ScopusFundingFact savedFunding = fundingCaptor.getValue().iterator().next();
        assertEquals("PNRR", savedFunding.getAcronym());
        assertEquals("123", savedFunding.getNumber());
        assertEquals("UEFISCDI", savedFunding.getSponsor());
        assertEquals("pnrr|123|uefiscdi", savedFunding.getFundingKey());
        assertEquals("b1", savedFunding.getSourceBatchId());
        assertEquals("c1", savedFunding.getSourceCorrelationId());
        assertNotNull(savedFunding.getLastPayloadHash());
        assertNotNull(savedFunding.getLastMaterializedAt());
        assertNotNull(savedFunding.getCreatedAt());
        assertNotNull(savedFunding.getUpdatedAt());

        ArgumentCaptor<java.util.Collection<ScopusCitationFact>> citationCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(citationFactRepository, atLeastOnce()).saveAll(citationCaptor.capture());
        ScopusCitationFact savedCitation = citationCaptor.getValue().iterator().next();
        assertEquals("2-s2.0-p1", savedCitation.getCitedEid());
        assertEquals("2-s2.0-c1", savedCitation.getCitingEid());
        assertEquals("SCOPUS_JSON_BOOTSTRAP", savedCitation.getSource());
        assertEquals("2-s2.0-p1->2-s2.0-c1", savedCitation.getSourceRecordId());
        assertEquals("b1", savedCitation.getSourceBatchId());
        assertEquals("c2", savedCitation.getSourceCorrelationId());
        assertNotNull(savedCitation.getLastPayloadHash());
        assertNotNull(savedCitation.getLastMaterializedAt());
        assertNotNull(savedCitation.getCreatedAt());
        assertNotNull(savedCitation.getUpdatedAt());

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository, atLeastOnce()).saveAll(authorCaptor.capture());
        ScopusAuthorFact savedAuthor = authorCaptor.getValue().stream()
                .filter(a -> "a1".equals(a.getAuthorId())).findFirst().orElseThrow();
        assertEquals("Alice", savedAuthor.getName());
        assertEquals(List.of("af1", "af2"), savedAuthor.getAffiliationIds());
        assertEquals(List.of(), savedAuthor.getAlternativeNames());
        assertEquals("b1", savedAuthor.getSourceBatchId());
        assertEquals("c1", savedAuthor.getSourceCorrelationId());
        assertNotNull(savedAuthor.getLastPayloadHash());
        assertNotNull(savedAuthor.getLastMaterializedAt());
        assertNotNull(savedAuthor.getCreatedAt());
        assertNotNull(savedAuthor.getUpdatedAt());

        ArgumentCaptor<java.util.Collection<ScopusAffiliationFact>> affiliationCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(affiliationFactRepository, atLeastOnce()).saveAll(affiliationCaptor.capture());
        ScopusAffiliationFact savedAffiliation = affiliationCaptor.getValue().stream()
                .filter(a -> "af1".equals(a.getAfid())).findFirst().orElseThrow();
        assertEquals("Aff 1", savedAffiliation.getName());
        assertEquals("City1", savedAffiliation.getCity());
        assertEquals("RO", savedAffiliation.getCountry());
        assertEquals("b1", savedAffiliation.getSourceBatchId());
        assertEquals("c1", savedAffiliation.getSourceCorrelationId());
        assertNotNull(savedAffiliation.getLastPayloadHash());
        assertNotNull(savedAffiliation.getLastMaterializedAt());
        assertNotNull(savedAffiliation.getCreatedAt());
        assertNotNull(savedAffiliation.getUpdatedAt());
    }

    @Test
    void buildFactsFromImportEventsIsReplaySafeWithExistingFacts() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setId("ev1");
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        publicationEvent.setSourceRecordId("2-s2.0-p1");
        publicationEvent.setBatchId("b1");
        publicationEvent.setCorrelationId("c1");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-p1"),
                java.util.Map.entry("title", "Paper 1"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "af1"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1"),
                java.util.Map.entry("publicationName", "Forum 1"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "87654321"),
                java.util.Map.entry("aggregationType", "Journal"),
                java.util.Map.entry("coverDate", "2025-01-01"),
                java.util.Map.entry("citedby_count", 10)
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(publicationEvent));
        ScopusPublicationFact existingPublication = new ScopusPublicationFact();
        existingPublication.setEid("2-s2.0-p1");
        ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact existingForum = new ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact();
        existingForum.setSourceId("f1");
        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        ScopusAffiliationFact existingAffiliation = new ScopusAffiliationFact();
        existingAffiliation.setAfid("af1");
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of(existingPublication));
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of(existingForum));
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of(existingAffiliation));
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertTrue(result.getUpdatedCount() >= 1);
        verify(publicationFactRepository).saveAll(anyCollection());
    }

    @Test
    void buildFactsFromImportEventsPublicationLinksSeededForumWithoutMutatingIt() throws Exception {
        // H66 D4 (forums-first, resolve-and-link): a publication whose venue is already seeded by an
        // authoritative FORUM source (Source List / CiteScore) must NOT mutate that forum — strict link-only.
        // The publication's eIssn does NOT enrich the forum (the Source List is the identity authority); the
        // venue link is resolved at stage-3 canonicalization by source id. Replaces the old enrich contract.
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setId("ev1");
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        publicationEvent.setSourceRecordId("2-s2.0-p1");
        publicationEvent.setBatchId("b1");
        publicationEvent.setCorrelationId("c1");
        // The publication carries the venue's source_id and an eIssn, but deliberately OMITS
        // publicationName/issn/aggregationType/publisher/forumType/asjc — all seeded by CiteScore.
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-p1"),
                java.util.Map.entry("title", "Paper 1"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "af1"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1"),
                java.util.Map.entry("eIssn", "8765-4321"),
                java.util.Map.entry("coverDate", "2025-01-01"),
                java.util.Map.entry("citedby_count", 10)
        )));

        ScopusForumFact seededForum = new ScopusForumFact();
        seededForum.setSourceId("f1");
        seededForum.setPublicationName("Seeded Forum");
        seededForum.setIssn("1234-5678");
        seededForum.setAggregationType("Journal");
        seededForum.setPublisher("Seeded Publisher");
        seededForum.setForumType("journal");
        seededForum.setAsjc(java.util.List.of("1000"));

        when(importEventRepository.findAll()).thenReturn(List.of(publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of(seededForum));
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        // Link-only: the seeded forum is never re-saved or mutated by the publication.
        verify(forumFactRepository, org.mockito.Mockito.never()).saveAll(anyCollection());
        org.junit.jupiter.api.Assertions.assertNull(seededForum.getEIssn(),
                "publication must not enrich the authoritative forum's eIssn (strict link-only)");
        assertEquals("1234-5678", seededForum.getIssn());
        assertEquals("Seeded Forum", seededForum.getPublicationName());
        // The publication itself is still processed.
        verify(publicationFactRepository).saveAll(anyCollection());
    }

    @Test
    void buildFactsFromImportEventsLogsChunkAndSummary() {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("row-1");
        event.setPayload("{\"eid\":\"2-s2.0-p1\",\"source_id\":\"f1\",\"author_ids\":\"a1\",\"author_names\":\"A\",\"author_afids\":\"af1\",\"afid\":\"af1\"}");
        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder start: scope=all-events, totalEvents=1")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder publication chunk 1 complete")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder summary: processed=1")));
    }

    @Test
    void buildFactsFromImportEventsLogsProgressHeartbeat() {
        List<ScopusImportEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ScopusImportEvent event = new ScopusImportEvent();
            event.setEntityType(null);
            event.setSource("SCOPUS_JSON_BOOTSTRAP");
            event.setSourceRecordId("row-" + i);
            event.setPayload(null);
            events.add(event);
        }
        when(importEventRepository.findAll()).thenReturn(events);

        service.buildFactsFromImportEvents();

        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder progress: processed=10000")));
    }

    @Test
    void buildFactsFromImportEventsSplitsPublicationWorkIntoTwoChunks() throws Exception {
        List<ScopusImportEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 1_001; i++) {
            ScopusImportEvent event = new ScopusImportEvent();
            event.setEntityType(ScopusImportEntityType.PUBLICATION);
            event.setSource("SCOPUS_JSON_BOOTSTRAP");
            event.setSourceRecordId("pub-" + i);
            event.setPayloadHash("pub-hash-" + i);
            event.setPayload(mapper.writeValueAsString(java.util.Map.of(
                    "eid", "2-s2.0-p" + i,
                    "source_id", "f" + i,
                    "author_ids", "a" + i,
                    "author_names", "Author " + i,
                    "author_afids", "af" + i,
                    "afid", "af" + i
            )));
            events.add(event);
        }

        when(importEventRepository.findAll()).thenReturn(events);
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1_001, result.getProcessedCount());
        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder publication chunk 1 complete [batch=0 / totalBatches=2]: events=1000")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder publication chunk 2 complete [batch=1 / totalBatches=2]: events=1")));
    }

    @Test
    void buildFactsFromImportEventsSplitsCitationWorkIntoTwoChunks() throws Exception {
        List<ScopusImportEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 1_001; i++) {
            ScopusImportEvent event = new ScopusImportEvent();
            event.setEntityType(ScopusImportEntityType.CITATION);
            event.setSource("SCOPUS_JSON_BOOTSTRAP");
            event.setSourceRecordId("2-s2.0-p" + i + "->2-s2.0-c" + i);
            event.setPayloadHash("cit-hash-" + i);
            event.setPayload(mapper.writeValueAsString(java.util.Map.of(
                    "citedEid", "2-s2.0-p" + i,
                    "citingEid", "2-s2.0-c" + i
            )));
            events.add(event);
        }

        when(importEventRepository.findAll()).thenReturn(events);
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1_001, result.getProcessedCount());
        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder citation chunk 1 complete [batch=0 / totalBatches=2]: events=1000")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Scopus fact-builder citation chunk 2 complete [batch=1 / totalBatches=2]: events=1")));
    }

    @Test
    void buildFactsFromImportEventsLogsEventErrorAndContinues() {
        ScopusImportEvent badEvent = new ScopusImportEvent();
        badEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        badEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        badEvent.setSourceRecordId("bad-row");
        badEvent.setPayload("{not-json");

        ScopusImportEvent skippedEvent = new ScopusImportEvent();
        skippedEvent.setEntityType(null);
        skippedEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        skippedEvent.setSourceRecordId("skip-row");
        skippedEvent.setPayload(null);

        when(importEventRepository.findAll()).thenReturn(List.of(badEvent, skippedEvent));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getSkippedCount() >= 1);
        assertTrue(logAppender.list.stream().anyMatch(event ->
                event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("Scopus fact-builder event failed")
                        && event.getFormattedMessage().contains("sourceRecordId=bad-row")));
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("Scopus fact-builder summary: processed=2")));
    }

    @Test
    void buildFactsFromImportEventsSkipsUserDefinedPublicationEvents() throws Exception {
        ScopusImportEvent userDefinedEvent = new ScopusImportEvent();
        userDefinedEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        userDefinedEvent.setSource("USER_DEFINED");
        userDefinedEvent.setSourceRecordId("USER_DEFINED:PUBLICATION:abc");
        userDefinedEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "eid", "USER_DEFINED:EID:abc",
                "source_id", "USER_DEFINED:FORUM:abc"
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(userDefinedEvent));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
        verify(publicationFactRepository, never()).saveAll(anyCollection());
        verify(forumFactRepository, never()).saveAll(anyCollection());
    }

    @Test
    void buildFactsFromImportEventsSkipsUnsupportedEntityTypeWithSample() throws Exception {
        ScopusImportEvent unsupportedEvent = new ScopusImportEvent();
        unsupportedEvent.setEntityType(ScopusImportEntityType.AUTHOR);
        unsupportedEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        unsupportedEvent.setSourceRecordId("author-row-1");
        unsupportedEvent.setPayload(mapper.writeValueAsString(java.util.Map.of("author_id", "a1")));

        when(importEventRepository.findAll()).thenReturn(List.of(unsupportedEvent));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.getErrorsSample().isEmpty());
        verify(publicationFactRepository, never()).saveAll(anyCollection());
        verify(citationFactRepository, never()).saveAll(anyCollection());
    }

    @Test
    void buildFactsFromImportEventsImportsForumEventFromCsv() throws Exception {
        ScopusImportEvent forumEvent = new ScopusImportEvent();
        forumEvent.setEntityType(ScopusImportEntityType.FORUM);
        forumEvent.setSource("SCOPUS_PUBLISHER_CSV_UPLOAD");
        forumEvent.setSourceRecordId("forum-1");
        forumEvent.setPayload(mapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("source_id", "forum-1");
            put("publicationName", "Forum Name");
            put("issn", "1234-5678");
            put("aggregationType", "Book");
            put("publisher", "Springer");
            put("isbn", "978-3-16-148410-0");
        }}));

        when(importEventRepository.findAll()).thenReturn(List.of(forumEvent));
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<java.util.Collection<ScopusForumFact>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(forumFactRepository).saveAll(captor.capture());
        ScopusForumFact saved = captor.getValue().iterator().next();
        assertEquals("forum-1", saved.getSourceId());
        assertEquals("Springer", saved.getPublisher());
        assertEquals("978-3-16-148410-0", saved.getIsbn());
        assertEquals("Forum Name", saved.getPublicationName());
        assertEquals("Book", saved.getAggregationType());
    }

    @Test
    void buildFactsFromImportEventsSkipsPublicationPayloadWithoutEid() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        publicationEvent.setSourceRecordId("pub-missing-eid");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "title", "Missing EID",
                "source_id", "f1"
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.getErrorsSample().isEmpty());
        verify(publicationFactRepository, never()).saveAll(anyCollection());
    }

    @Test
    void buildFactsFromImportEventsSkipsCitationPayloadWithoutRecoverableEdge() throws Exception {
        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("citation-row-without-edge");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "",
                "citingEid", ""
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(citationEvent));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getErrorCount());
        assertTrue(result.getErrorsSample().isEmpty());
        verify(citationFactRepository, never()).saveAll(anyCollection());
    }

    @Test
    void citationCitingItemDoesNotBackfillDimensionsWhenPublicationExists() throws Exception {
        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "2-s2.0-p1",
                "citingEid", "2-s2.0-c1",
                "citingItem", java.util.Map.of(
                        "eid", "2-s2.0-c1",
                        "source_id", "f-c1",
                        "author_ids", "a-c1",
                        "author_names", "C Author",
                        "author_afids", "af-c1",
                        "afid", "af-c1",
                        "affilname", "Aff C1",
                        "affiliation_city", "CityC1",
                        "affiliation_country", "RO"
                )
        )));

        ScopusPublicationFact existingPublication = new ScopusPublicationFact();
        existingPublication.setEid("2-s2.0-c1");

        when(importEventRepository.findAll()).thenReturn(List.of(citationEvent));
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of());
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of(existingPublication));

        service.buildFactsFromImportEvents();

        verify(citationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(forumFactRepository, never()).saveAll(anyCollection());
        verify(authorFactRepository, never()).saveAll(anyCollection());
        verify(affiliationFactRepository, never()).saveAll(anyCollection());
        verify(fundingFactRepository, never()).saveAll(anyCollection());
    }

    @Test
    void citationCitingItemBackfillsDimensionsWhenPublicationMissing() throws Exception {
        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "2-s2.0-p1",
                "citingEid", "2-s2.0-c1",
                "citingItem", java.util.Map.of(
                        "eid", "2-s2.0-c1",
                        "source_id", "f-c1",
                        "author_ids", "a-c1",
                        "author_names", "C Author",
                        "author_afids", "af-c1",
                        "afid", "af-c1",
                        "affilname", "Aff C1",
                        "affiliation_city", "CityC1",
                        "affiliation_country", "RO"
                )
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(citationEvent));
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of());
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        verify(citationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(publicationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(forumFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(authorFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(affiliationFactRepository, atLeastOnce()).saveAll(anyCollection());
    }

    @Test
    void citationCitingItemBackfillsExpectedPublicationDimensionFields() throws Exception {
        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setId("ev-backfill");
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citationEvent.setBatchId("b-citation");
        citationEvent.setCorrelationId("corr-citation");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "2-s2.0-p1",
                "citingEid", "2-s2.0-c1",
                "citingItem", java.util.Map.ofEntries(
                        java.util.Map.entry("eid", "2-s2.0-c1"),
                        java.util.Map.entry("title", "Backfilled Paper"),
                        java.util.Map.entry("source_id", "f-c1"),
                        java.util.Map.entry("publicationName", "Forum C1"),
                        java.util.Map.entry("issn", "12345678"),
                        java.util.Map.entry("eIssn", "87654321"),
                        java.util.Map.entry("aggregationType", "Journal"),
                        java.util.Map.entry("author_ids", "a-c1"),
                        java.util.Map.entry("author_names", "C Author"),
                        java.util.Map.entry("author_afids", "afc1"),
                        java.util.Map.entry("afid", "afc1"),
                        java.util.Map.entry("affilname", "Aff C1"),
                        java.util.Map.entry("affiliation_city", "CityC1"),
                        java.util.Map.entry("affiliation_country", "RO"),
                        java.util.Map.entry("coverDate", "2025-01-01"),
                        java.util.Map.entry("citedby_count", 7)
                )
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(citationEvent));
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of());
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusPublicationFact>> publicationCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(publicationFactRepository, atLeastOnce()).saveAll(publicationCaptor.capture());
        ScopusPublicationFact backfilledPublication = publicationCaptor.getValue().iterator().next();
        assertEquals("2-s2.0-c1", backfilledPublication.getEid());
        assertEquals("Backfilled Paper", backfilledPublication.getTitle());
        assertEquals("f-c1", backfilledPublication.getForumId());
        assertEquals(List.of("a-c1"), backfilledPublication.getAuthors());
        assertEquals(List.of("afc1"), backfilledPublication.getAffiliations());
        assertEquals("2025-01-01", backfilledPublication.getCoverDate());
        assertEquals(7, backfilledPublication.getCitedByCount());
        assertEquals("b-citation", backfilledPublication.getSourceBatchId());
        assertEquals("corr-citation", backfilledPublication.getSourceCorrelationId());

        ArgumentCaptor<java.util.Collection<ScopusForumFact>> forumCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(forumFactRepository, atLeastOnce()).saveAll(forumCaptor.capture());
        ScopusForumFact backfilledForum = forumCaptor.getValue().iterator().next();
        assertEquals("f-c1", backfilledForum.getSourceId());
        assertEquals("Forum C1", backfilledForum.getPublicationName());
        assertEquals("1234-5678", backfilledForum.getIssn());
        assertEquals("8765-4321", backfilledForum.getEIssn());

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository, atLeastOnce()).saveAll(authorCaptor.capture());
        ScopusAuthorFact backfilledAuthor = authorCaptor.getValue().iterator().next();
        assertEquals("a-c1", backfilledAuthor.getAuthorId());
        assertEquals("C Author", backfilledAuthor.getName());
        assertEquals(List.of("afc1"), backfilledAuthor.getAffiliationIds());
    }

    @Test
    void buildFactsFromImportEventsWithBatchIdProcessesOnlyBatchEvents() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        publicationEvent.setSourceRecordId("2-s2.0-p1");
        publicationEvent.setBatchId("b-target");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "eid", "2-s2.0-p1",
                "source_id", "f1",
                "author_ids", "a1",
                "author_names", "Alice",
                "author_afids", "af1",
                "afid", "af1"
        )));

        when(importEventRepository.findByBatchId("b-target")).thenReturn(List.of(publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents("b-target");

        assertEquals(1, result.getProcessedCount());
        verify(importEventRepository).findByBatchId("b-target");
        verify(importEventRepository, never()).findAll();
    }

    @Test
    void buildFactsFromImportEventsRefreshesBatchLineageForUnchangedPublicationAndDimensions() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setId("ev-replay");
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_JSON_UPLOAD");
        publicationEvent.setSourceRecordId("2-s2.0-105014402872");
        publicationEvent.setBatchId("b-replay");
        publicationEvent.setCorrelationId("corr-replay");
        publicationEvent.setPayloadHash("pub-same-hash");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-105014402872"),
                java.util.Map.entry("title", "Replay Paper"),
                java.util.Map.entry("author_ids", "55637349100"),
                java.util.Map.entry("author_names", "Spataru, Adrian"),
                java.util.Map.entry("author_afids", "60024417"),
                java.util.Map.entry("afid", "60024417"),
                java.util.Map.entry("affilname", "UVT"),
                java.util.Map.entry("affiliation_city", "Timisoara"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "forum-1"),
                java.util.Map.entry("publicationName", "Forum 1"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "87654321"),
                java.util.Map.entry("aggregationType", "Journal"),
                java.util.Map.entry("fund_acr", "PNRR"),
                java.util.Map.entry("fund_no", "123"),
                java.util.Map.entry("fund_sponsor", "UEFISCDI")
        )));

        ScopusPublicationFact existingPublication = new ScopusPublicationFact();
        existingPublication.setEid("2-s2.0-105014402872");
        existingPublication.setTitle("Replay Paper");
        existingPublication.setLastPayloadHash("pub-same-hash");
        existingPublication.setSourceBatchId("b-old");
        existingPublication.setSourceCorrelationId("corr-old");
        existingPublication.setCreatedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        existingPublication.setUpdatedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        existingPublication.setLastMaterializedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));

        ScopusForumFact existingForum = new ScopusForumFact();
        existingForum.setSourceId("forum-1");
        existingForum.setPublicationName("Forum 1");
        existingForum.setIssn("1234-5678");
        existingForum.setEIssn("8765-4321");
        existingForum.setAggregationType("Journal");
        existingForum.setLastPayloadHash(hashKey("forum", "forum-1", "Forum 1", "1234-5678", "8765-4321", null, "Journal", null, null, ""));
        existingForum.setSourceBatchId("b-old");
        existingForum.setSourceCorrelationId("corr-old");
        existingForum.setCreatedAt(java.time.Instant.parse("2025-01-02T00:00:00Z"));
        existingForum.setUpdatedAt(java.time.Instant.parse("2025-01-02T00:00:00Z"));
        existingForum.setLastMaterializedAt(java.time.Instant.parse("2025-01-02T00:00:00Z"));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("55637349100");
        existingAuthor.setLastPayloadHash(hashKey("author", "55637349100", "Spataru, Adrian", "60024417"));
        existingAuthor.setSourceBatchId("b-old");
        existingAuthor.setSourceCorrelationId("corr-old");
        existingAuthor.setName("Spataru, Adrian");

        ScopusAffiliationFact existingAffiliation = new ScopusAffiliationFact();
        existingAffiliation.setAfid("60024417");
        existingAffiliation.setLastPayloadHash(hashKey("affiliation", "60024417", "UVT", "Timisoara", "RO"));
        existingAffiliation.setSourceBatchId("b-old");
        existingAffiliation.setSourceCorrelationId("corr-old");
        existingAffiliation.setName("UVT");
        existingAffiliation.setCity("Timisoara");
        existingAffiliation.setCountry("RO");

        ScopusFundingFact existingFunding = new ScopusFundingFact();
        existingFunding.setFundingKey("pnrr|123|uefiscdi");
        existingFunding.setAcronym("PNRR");
        existingFunding.setNumber("123");
        existingFunding.setSponsor("UEFISCDI");
        existingFunding.setLastPayloadHash(hashKey("funding", "PNRR", "123", "UEFISCDI"));
        existingFunding.setSourceBatchId("b-old");
        existingFunding.setSourceCorrelationId("corr-old");
        existingFunding.setCreatedAt(java.time.Instant.parse("2025-01-03T00:00:00Z"));
        existingFunding.setUpdatedAt(java.time.Instant.parse("2025-01-03T00:00:00Z"));
        existingFunding.setLastMaterializedAt(java.time.Instant.parse("2025-01-03T00:00:00Z"));

        when(importEventRepository.findByBatchId("b-replay")).thenReturn(List.of(publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of(existingPublication));
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of(existingForum));
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of(existingAffiliation));
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of(existingFunding));

        ImportProcessingResult result = service.buildFactsFromImportEvents("b-replay");

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());

        ArgumentCaptor<java.util.Collection<ScopusPublicationFact>> publicationCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<java.util.Collection<ScopusAffiliationFact>> affiliationCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<java.util.Collection<ScopusFundingFact>> fundingCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(publicationFactRepository).saveAll(publicationCaptor.capture());
        // H66 D4: a publication replay no longer touches its forum — forum lineage comes from FORUM sources,
        // not publications. The forum is link-only, so it is never re-saved by the publication path.
        verify(forumFactRepository, org.mockito.Mockito.never()).saveAll(anyCollection());
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        verify(affiliationFactRepository).saveAll(affiliationCaptor.capture());
        verify(fundingFactRepository).saveAll(fundingCaptor.capture());

        ScopusPublicationFact replayedPublication = publicationCaptor.getValue().iterator().next();
        ScopusAuthorFact replayedAuthor = authorCaptor.getValue().iterator().next();
        ScopusAffiliationFact replayedAffiliation = affiliationCaptor.getValue().iterator().next();
        ScopusFundingFact replayedFunding = fundingCaptor.getValue().iterator().next();

        assertEquals("b-replay", replayedPublication.getSourceBatchId());
        assertEquals("corr-replay", replayedPublication.getSourceCorrelationId());
        assertEquals("Replay Paper", replayedPublication.getTitle());
        assertEquals("pub-same-hash", replayedPublication.getLastPayloadHash());
        assertEquals(java.time.Instant.parse("2025-01-01T00:00:00Z"), replayedPublication.getLastMaterializedAt());
        assertEquals(java.time.Instant.parse("2025-01-01T00:00:00Z"), replayedPublication.getUpdatedAt());

        assertEquals("b-replay", replayedAuthor.getSourceBatchId());
        assertEquals("corr-replay", replayedAuthor.getSourceCorrelationId());
        assertEquals("Spataru, Adrian", replayedAuthor.getName());

        assertEquals("b-replay", replayedAffiliation.getSourceBatchId());
        assertEquals("corr-replay", replayedAffiliation.getSourceCorrelationId());
        assertEquals("UVT", replayedAffiliation.getName());
        assertEquals("Timisoara", replayedAffiliation.getCity());
        assertEquals("RO", replayedAffiliation.getCountry());

        assertEquals("b-replay", replayedFunding.getSourceBatchId());
        assertEquals("corr-replay", replayedFunding.getSourceCorrelationId());
        assertEquals("PNRR", replayedFunding.getAcronym());
        assertEquals("123", replayedFunding.getNumber());
        assertEquals("UEFISCDI", replayedFunding.getSponsor());
        assertEquals("pnrr|123|uefiscdi", replayedFunding.getFundingKey());
        assertEquals(hashKey("funding", "PNRR", "123", "UEFISCDI"), replayedFunding.getLastPayloadHash());
        assertEquals(java.time.Instant.parse("2025-01-03T00:00:00Z"), replayedFunding.getLastMaterializedAt());
        assertEquals(java.time.Instant.parse("2025-01-03T00:00:00Z"), replayedFunding.getUpdatedAt());
    }

    @Test
    void buildFactsFromImportEventsRefreshesBatchLineageForUnchangedCitation() throws Exception {
        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setId("ev-citation");
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_UPLOAD");
        citationEvent.setSourceRecordId("2-s2.0-105014402872->2-s2.0-105000527065");
        citationEvent.setBatchId("b-replay");
        citationEvent.setCorrelationId("corr-citation");
        citationEvent.setPayloadHash("citation-same-hash");
        citationEvent.setPayload(mapper.writeValueAsString(java.util.Map.of(
                "citedEid", "2-s2.0-105014402872",
                "citingEid", "2-s2.0-105000527065"
        )));

        ScopusCitationFact existingCitation = new ScopusCitationFact();
        existingCitation.setCitedEid("2-s2.0-105014402872");
        existingCitation.setCitingEid("2-s2.0-105000527065");
        existingCitation.setLastPayloadHash("citation-same-hash");
        existingCitation.setSourceBatchId("b-old");
        existingCitation.setSourceCorrelationId("corr-old");
        existingCitation.setCreatedAt(java.time.Instant.parse("2025-01-04T00:00:00Z"));
        existingCitation.setUpdatedAt(java.time.Instant.parse("2025-01-04T00:00:00Z"));
        existingCitation.setLastMaterializedAt(java.time.Instant.parse("2025-01-04T00:00:00Z"));

        when(importEventRepository.findByBatchId("b-replay")).thenReturn(List.of(citationEvent));
        when(citationFactRepository.findByCitedEidInAndCitingEidIn(anyCollection(), anyCollection())).thenReturn(List.of(existingCitation));

        ImportProcessingResult result = service.buildFactsFromImportEvents("b-replay");

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
        assertEquals(0, result.getUpdatedCount());

        ArgumentCaptor<java.util.Collection<ScopusCitationFact>> citationCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(citationFactRepository).saveAll(citationCaptor.capture());
        ScopusCitationFact replayedCitation = citationCaptor.getValue().iterator().next();
        assertEquals("b-replay", replayedCitation.getSourceBatchId());
        assertEquals("corr-citation", replayedCitation.getSourceCorrelationId());
        assertEquals("2-s2.0-105014402872", replayedCitation.getCitedEid());
        assertEquals("2-s2.0-105000527065", replayedCitation.getCitingEid());
        assertEquals("citation-same-hash", replayedCitation.getLastPayloadHash());
        assertEquals(java.time.Instant.parse("2025-01-04T00:00:00Z"), replayedCitation.getLastMaterializedAt());
        assertEquals(java.time.Instant.parse("2025-01-04T00:00:00Z"), replayedCitation.getUpdatedAt());
    }

    @Test
    void buildFactsFromImportEventsMergesUpdatedAuthorNameAndAffiliations() throws Exception {
        ScopusImportEvent publicationEvent = new ScopusImportEvent();
        publicationEvent.setId("ev-author-update");
        publicationEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        publicationEvent.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        publicationEvent.setSourceRecordId("2-s2.0-author-update");
        publicationEvent.setBatchId("b-author");
        publicationEvent.setCorrelationId("corr-author");
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-author-update"),
                java.util.Map.entry("title", "Author Update"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Spataru Adrian"),
                java.util.Map.entry("author_afids", "af2-af1"),
                java.util.Map.entry("afid", "af1;af2"),
                java.util.Map.entry("affilname", "Aff 1;Aff 2"),
                java.util.Map.entry("affiliation_city", "City1;City2"),
                java.util.Map.entry("affiliation_country", "RO;RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Spataru, Adrian");
        existingAuthor.setAlternativeNames(List.of("A. Spataru"));
        existingAuthor.setAffiliationIds(List.of("af1"));

        when(importEventRepository.findAll()).thenReturn(List.of(publicationEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        ScopusAuthorFact savedAuthor = authorCaptor.getValue().iterator().next();
        assertEquals("Spataru, Adrian", savedAuthor.getName());
        assertEquals(List.of("A. Spataru"), savedAuthor.getAlternativeNames());
        assertEquals(List.of("af1", "af2"), savedAuthor.getAffiliationIds());
        assertEquals("b-author", savedAuthor.getSourceBatchId());
        assertEquals("corr-author", savedAuthor.getSourceCorrelationId());
    }

    @Test
    void buildFactsFromImportEventsSkipsAmbiguousAffiliationUpdatesAndPreservesExistingFact() throws Exception {
        ScopusImportEvent goodEvent = new ScopusImportEvent();
        goodEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        goodEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        goodEvent.setSourceRecordId("2-s2.0-good");
        goodEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-good"),
                java.util.Map.entry("title", "Good Paper"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "60000434"),
                java.util.Map.entry("afid", "60000434"),
                java.util.Map.entry("affilname", "Universitatea de Vest din Timisoara"),
                java.util.Map.entry("affiliation_city", "Timisoara"),
                java.util.Map.entry("affiliation_country", "Romania"),
                java.util.Map.entry("source_id", "f1")
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(goodEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        reset(publicationFactRepository, forumFactRepository, authorFactRepository, affiliationFactRepository, fundingFactRepository, importEventRepository);

        ScopusImportEvent malformedEvent = new ScopusImportEvent();
        malformedEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        malformedEvent.setSource("SCOPUS_PYTHON_CITATIONS_PUBLICATION");
        malformedEvent.setSourceRecordId("2-s2.0-bad");
        malformedEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-bad"),
                java.util.Map.entry("title", "Bad Paper"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "60000434"),
                java.util.Map.entry("afid", "60031106;60000434"),
                java.util.Map.entry("affilname", "Universitatea de Vest din Timisoara;Univerza v Ljubljani;Extra Name"),
                java.util.Map.entry("affiliation_city", "Timisoara;Ljubljana"),
                java.util.Map.entry("affiliation_country", "Romania;Slovenia"),
                java.util.Map.entry("source_id", "f2")
        )));

        ScopusAffiliationFact existingAffiliation = new ScopusAffiliationFact();
        existingAffiliation.setAfid("60000434");
        existingAffiliation.setName("Universitatea de Vest din Timisoara");
        existingAffiliation.setCity("Timisoara");
        existingAffiliation.setCountry("Romania");

        when(importEventRepository.findAll()).thenReturn(List.of(malformedEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of(existingAffiliation));
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertTrue(result.getSkippedCount() >= 1);
        assertEquals("Universitatea de Vest din Timisoara", existingAffiliation.getName());
        assertEquals("Timisoara", existingAffiliation.getCity());
        assertEquals("Romania", existingAffiliation.getCountry());
        verify(affiliationFactRepository, never()).saveAll(anyCollection());
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("skipped ambiguous affiliation update") && m.contains("sourceRecordId=2-s2.0-bad")));
    }

    @Test
    void buildFactsFromImportEventsSkipsAmbiguousAuthorUpdatesAndPreservesExistingFact() throws Exception {
        ScopusImportEvent malformedEvent = new ScopusImportEvent();
        malformedEvent.setEntityType(ScopusImportEntityType.PUBLICATION);
        malformedEvent.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        malformedEvent.setSourceRecordId("2-s2.0-bad-author");
        malformedEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-bad-author"),
                java.util.Map.entry("title", "Bad Author Paper"),
                java.util.Map.entry("author_ids", "a1;a2"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "af1;af2"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Alice");
        existingAuthor.setAffiliationIds(List.of("af1"));

        when(importEventRepository.findAll()).thenReturn(List.of(malformedEvent));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertTrue(result.getSkippedCount() >= 1);
        assertEquals("Alice", existingAuthor.getName());
        assertEquals(List.of("af1"), existingAuthor.getAffiliationIds());
        verify(authorFactRepository, never()).saveAll(anyCollection());
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("skipped ambiguous author update") && m.contains("sourceRecordId=2-s2.0-bad-author")));
    }

    @Test
    void buildFactsFromImportEventsDecodesHtmlAffiliationNamesBeforeAlignmentCheck() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-85179172081");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-85179172081"),
                java.util.Map.entry("title", "ATLAS"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "60113665"),
                java.util.Map.entry("afid", "60113665;60017293"),
                java.util.Map.entry("affilname", "State Key Laboratory of Particle Detection &amp; Electronics;Horia Hulubei National Institute for R&amp;D in Physics and Nuclear Engineering"),
                java.util.Map.entry("affiliation_city", "Hefei;Magurele"),
                java.util.Map.entry("affiliation_country", "China;Romania"),
                java.util.Map.entry("source_id", "f1")
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAffiliationFact>> affiliationCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(affiliationFactRepository).saveAll(affiliationCaptor.capture());
        List<ScopusAffiliationFact> savedFacts = List.copyOf(affiliationCaptor.getValue());
        assertEquals(2, savedFacts.size());
        assertTrue(savedFacts.stream().anyMatch(f -> "60113665".equals(f.getAfid()) && "State Key Laboratory of Particle Detection & Electronics".equals(f.getName())));
        assertTrue(savedFacts.stream().anyMatch(f -> "60017293".equals(f.getAfid()) && "Horia Hulubei National Institute for R&D in Physics and Nuclear Engineering".equals(f.getName())));
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("skipped ambiguous affiliation update") && m.contains("sourceRecordId=2-s2.0-85179172081")));
    }

    @Test
    void buildFactsFromImportEventsPreservesTrailingEmptyAuthorAffiliationSlots() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-85179483813");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-85179483813"),
                java.util.Map.entry("title", "Crayfish"),
                java.util.Map.entry("author_ids", "56225564000;55274491800;55256903500;6506072411;57550278700;6506217375"),
                java.util.Map.entry("author_names", "Gašparič, Rok;Audo, Denis;Kawai, Tadashi;Kolar-Jurkovšek, Tea;Marinšek, Miha;Jurkovšek, Bogdan"),
                java.util.Map.entry("author_afids", "129811068-119936631;60001422;60107875;60029147;60029147;"),
                java.util.Map.entry("afid", "60107875;60029147;60001422;129811068;119936631"),
                java.util.Map.entry("affilname", "Hokkaido Research Organization;Geological Survey of Slovenia;Sorbonne Université;Institute for Palaeobiology and Evolution;Oertijdmuseum"),
                java.util.Map.entry("affiliation_city", "Sapporo;Ljubljana;Paris;Kamnik;Boxtel"),
                java.util.Map.entry("affiliation_country", "Japan;Slovenia;France;Slovenia;Netherlands"),
                java.util.Map.entry("source_id", "22543")
        )));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of());
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        List<ScopusAuthorFact> savedFacts = List.copyOf(authorCaptor.getValue());
        assertEquals(6, savedFacts.size());
        assertTrue(savedFacts.stream().anyMatch(f -> "6506217375".equals(f.getAuthorId()) && f.getAffiliationIds().isEmpty()));
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("skipped ambiguous author update") && m.contains("sourceRecordId=2-s2.0-85179483813")));
    }

    @Test
    void buildFactsFromImportEventsPreservesExistingAffiliationsWhenAuthorSlotIsExplicitlyEmpty() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-explicit-empty-slot");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-explicit-empty-slot"),
                java.util.Map.entry("title", "Explicit Empty Slot"),
                java.util.Map.entry("author_ids", "a1;a2"),
                java.util.Map.entry("author_names", "Alice;Bob"),
                java.util.Map.entry("author_afids", "af1;"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingBob = new ScopusAuthorFact();
        existingBob.setAuthorId("a2");
        existingBob.setName("Bob");
        existingBob.setAffiliationIds(List.of("af-existing"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingBob));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        List<ScopusAuthorFact> savedFacts = List.copyOf(authorCaptor.getValue());
        // H56: only the newly created a1 is written; Bob's explicitly-empty slot leaves his content
        // unchanged, so he is preserved in place without a re-write.
        assertTrue(savedFacts.stream().anyMatch(f -> "a1".equals(f.getAuthorId())));
        assertTrue(savedFacts.stream().noneMatch(f -> "a2".equals(f.getAuthorId())));
        assertEquals(List.of("af-existing"), existingBob.getAffiliationIds());
    }

    @Test
    void buildFactsFromImportEventsTreatsMissingAuthorAfidsAsEmptyAffiliationLists() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-no-author-afids");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-no-author-afids"),
                java.util.Map.entry("title", "Sparse Authors"),
                java.util.Map.entry("author_ids", "a1;a2"),
                java.util.Map.entry("author_names", "Alice;Bob"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAlice = new ScopusAuthorFact();
        existingAlice.setAuthorId("a1");
        existingAlice.setName("Alice");
        existingAlice.setAffiliationIds(List.of("af-existing-1", "af-existing-2"));

        ScopusAuthorFact existingBob = new ScopusAuthorFact();
        existingBob.setAuthorId("a2");
        existingBob.setName("Bob");
        existingBob.setAffiliationIds(List.of("af-existing-3"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAlice, existingBob));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        // H56: the merge preserves both authors' affiliations, so their content is unchanged and they
        // must NOT be re-written (previously they were re-saved on every replay).
        verify(authorFactRepository, never()).saveAll(anyCollection());
        assertEquals(List.of("af-existing-1", "af-existing-2"), existingAlice.getAffiliationIds());
        assertEquals(List.of("af-existing-3"), existingBob.getAffiliationIds());
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("skipped ambiguous author update") && m.contains("sourceRecordId=2-s2.0-no-author-afids")));
    }

    @Test
    void buildFactsFromImportEventsTreatsBlankAuthorAfidsAsEmptyAffiliationLists() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-blank-author-afids");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-blank-author-afids"),
                java.util.Map.entry("title", "Sparse Authors"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", ""),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Alice");
        existingAuthor.setAffiliationIds(List.of("af-existing"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        // H56: content unchanged (blank afids preserve affiliations, name identical) -> no re-write.
        verify(authorFactRepository, never()).saveAll(anyCollection());
        assertEquals(List.of("af-existing"), existingAuthor.getAffiliationIds());
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("skipped ambiguous author update") && m.contains("sourceRecordId=2-s2.0-blank-author-afids")));
    }

    @Test
    void buildFactsFromImportEventsUnionsAuthorAffiliationsAcrossEvents() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-author-union");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-author-union"),
                java.util.Map.entry("title", "Union Authors"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Alice"),
                java.util.Map.entry("author_afids", "af2-af3"),
                java.util.Map.entry("afid", "af2;af3"),
                java.util.Map.entry("affilname", "Aff Existing 2;Aff New"),
                java.util.Map.entry("affiliation_city", "City2;City3"),
                java.util.Map.entry("affiliation_country", "RO;RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Alice");
        existingAuthor.setAffiliationIds(List.of("af1", "af2"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        List<ScopusAuthorFact> savedFacts = List.copyOf(authorCaptor.getValue());
        assertEquals(1, savedFacts.size());
        assertEquals(List.of("af1", "af2", "af3"), savedFacts.getFirst().getAffiliationIds());
    }

    @Test
    void buildFactsFromImportEventsPreservesAlternativeAuthorNamesAcrossUpdates() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-author-name-merge");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-author-name-merge"),
                java.util.Map.entry("title", "Author Name Merge"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", "Spataru A."),
                java.util.Map.entry("author_afids", "af1"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Spataru, Adrian");
        existingAuthor.setAlternativeNames(List.of("Adrian Spataru"));
        existingAuthor.setAffiliationIds(List.of("af1"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        ArgumentCaptor<java.util.Collection<ScopusAuthorFact>> authorCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(authorFactRepository).saveAll(authorCaptor.capture());
        ScopusAuthorFact saved = List.copyOf(authorCaptor.getValue()).getFirst();
        assertEquals("Spataru A.", saved.getName());
        assertEquals(List.of("Spataru, Adrian", "Adrian Spataru"), saved.getAlternativeNames());
    }

    @Test
    void buildFactsFromImportEventsSkipsBlankAuthorNamesWithoutClearingStoredNameEvidence() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("2-s2.0-author-name-blank");
        event.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-author-name-blank"),
                java.util.Map.entry("title", "Author Name Blank"),
                java.util.Map.entry("author_ids", "a1"),
                java.util.Map.entry("author_names", ""),
                java.util.Map.entry("author_afids", "af1"),
                java.util.Map.entry("afid", "af1"),
                java.util.Map.entry("affilname", "Aff 1"),
                java.util.Map.entry("affiliation_city", "City1"),
                java.util.Map.entry("affiliation_country", "RO"),
                java.util.Map.entry("source_id", "f1")
        )));

        ScopusAuthorFact existingAuthor = new ScopusAuthorFact();
        existingAuthor.setAuthorId("a1");
        existingAuthor.setName("Spataru, Adrian");
        existingAuthor.setAlternativeNames(List.of("Spataru A."));
        existingAuthor.setAffiliationIds(List.of("af1"));

        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findByEidIn(anyCollection())).thenReturn(List.of());
        when(forumFactRepository.findBySourceIdIn(anyCollection())).thenReturn(List.of());
        when(authorFactRepository.findByAuthorIdIn(anyCollection())).thenReturn(List.of(existingAuthor));
        when(affiliationFactRepository.findByAfidIn(anyCollection())).thenReturn(List.of());
        when(fundingFactRepository.findByFundingKeyIn(anyCollection())).thenReturn(List.of());

        service.buildFactsFromImportEvents();

        verify(authorFactRepository, never()).saveAll(anyCollection());
        assertTrue(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("skipped ambiguous author update") && m.contains("sourceRecordId=2-s2.0-author-name-blank")));
    }

    @Test
    void normalizeIssnFormatsEightDigitStringWithDash() {
        assertEquals("1234-5678", normalizeIssn("12345678"));
    }

    @Test
    void normalizeIssnReturnsAlreadyFormattedIssn() {
        assertEquals("1234-5678", normalizeIssn("1234-5678"));
    }

    @Test
    void normalizeIssnReturnsEmptyForBlank() {
        assertEquals("", normalizeIssn("   "));
        assertEquals("", normalizeIssn(null));
    }

    @Test
    void boolValueReturnsFalseForNullNode() {
        assertEquals(Boolean.FALSE, boolValue(null, "field"));
    }

    @Test
    void boolValueReturnsFalseForMissingFieldAndNullFieldName() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("flag", true);
        assertEquals(Boolean.FALSE, boolValue(node, "missing"));
        assertEquals(Boolean.FALSE, boolValue(node, null));
    }

    @Test
    void boolValueReturnsTrueForStringOne() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("flag", "1");
        assertEquals(Boolean.TRUE, boolValue(node, "flag"));
    }

    @Test
    void boolValueReturnsTrueForNumericOne() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("flag", 1);
        assertEquals(Boolean.TRUE, boolValue(node, "flag"));
    }

    @Test
    void boolValueReturnsFalseForNumericZero() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("flag", 0);
        assertEquals(Boolean.FALSE, boolValue(node, "flag"));
    }

    @Test
    void boolValueReturnsTrueForYesString() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("flag", "yes");
        assertEquals(Boolean.TRUE, boolValue(node, "flag"));
    }

    @Test
    void boolValueReturnsFalseForFalseString() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("flag", "false");
        assertEquals(Boolean.FALSE, boolValue(node, "flag"));
    }

    @Test
    void samePayloadHashReturnsFalseWhenBothBlank() {
        assertEquals(false, samePayloadHash("", ""));
        assertEquals(false, samePayloadHash(null, "hash"));
        assertEquals(false, samePayloadHash("hash", null));
    }

    @Test
    void samePayloadHashReturnsTrueWhenBothEqual() {
        assertEquals(true, samePayloadHash("abc", "abc"));
    }

    @Test
    void decodeHtmlEntitiesReturnsDecodedString() {
        assertEquals("a & b", decodeHtmlEntities("a &amp; b"));
        assertEquals("", decodeHtmlEntities(null));
    }

    @Test
    void intValueReturnsZeroForNullNode() {
        assertEquals(0, (int) intValue(null, "field"));
    }

    @Test
    void intValueParsesStringInteger() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("count", "42");
        assertEquals(42, (int) intValue(node, "count"));
    }

    @Test
    void intValueReturnsZeroForInvalidString() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("count", "abc");
        assertEquals(0, (int) intValue(node, "count"));
    }

    @Test
    void hashKeyIsStableAndNonEmptyForBlankInputs() {
        String first = hashKey("", "", "");
        String second = hashKey("", "", "");
        assertEquals(first, second);
        assertTrue(!first.isBlank());
    }

    @Test
    void normalizeFundingKeyLowercasesAndPreservesEmptySegments() {
        assertEquals("pnrr|123|uefis-cdi", normalizeFundingKey(" PNRR ", "123", "UEFIS-CDI"));
        assertEquals("||", normalizeFundingKey(null, null, null));
    }

    @Test
    void textReturnsTrimmedValueAndEmptyForMissingOrNullField() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("title", "  Paper 1  ");
        node.putNull("subtitle");

        assertEquals("Paper 1", text(node, "title"));
        assertEquals("", text(node, "missing"));
        assertEquals("", text(node, "subtitle"));
        assertEquals("", text(null, "title"));
        assertEquals("", text(node, null));
    }

    @Test
    void arrayValueReturnsTrimmedEntryOrEmptyWhenIndexInvalid() {
        assertEquals("b", arrayValue(List.of(" a ", " b "), 1));
        assertEquals("", arrayValue(List.of("a"), -1));
        assertEquals("", arrayValue(List.of("a"), 2));
        assertEquals("", arrayValue(null, 0));
    }

    @Test
    void distinctNonBlankDeduplicatesAndPreservesEncounterOrder() {
        assertEquals(List.of("a", "b", "c"), distinctNonBlank(List.of(" a ", "", "b", "a", "  ", "c", "b")));
        assertEquals(List.of(), distinctNonBlank(null));
    }

    @Test
    void normalizeForNameMergeStripsAccentsPunctuationAndCollapsesWhitespace() {
        assertEquals("adrian spataru", normalizeForNameMerge("  Ádrián,  Spătaru! "));
        assertEquals("", normalizeForNameMerge("   "));
    }

    @Test
    void nanosToMillisUsesIntegerDivision() {
        assertEquals(1L, nanosToMillis(1_999_999L));
        assertEquals(2L, nanosToMillis(2_000_000L));
        assertEquals(0L, nanosToMillis(999_999L));
    }

    @Test
    void hashKeyDependsOnTrimmedOrderedValues() {
        assertEquals(hashKey(" a ", "b"), hashKey("a", "b"));
        assertTrue(!hashKey("a", "b").equals(hashKey("b", "a")));
        assertEquals(64, hashKey("a", "b").length());
        assertTrue(hashKey("a", "b").matches("[0-9a-f]{64}"));
    }

    @Test
    void sampleFormatsNullAndConcreteEvents() {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS_JSON_BOOTSTRAP");
        event.setSourceRecordId("row-1");

        assertEquals("null-event missing metadata", sample(null, "missing metadata"));
        assertEquals("PUBLICATION:SCOPUS_JSON_BOOTSTRAP:row-1 publication payload unchanged",
                sample(event, "publication payload unchanged"));
    }

    @Test
    void splitDashReturnsPartsOnDash() {
        List<String> parts = splitDash("60024417-119936631");
        assertEquals(List.of("60024417", "119936631"), parts);
    }

    @Test
    void splitDashReturnsSingleElementWhenNoDash() {
        List<String> parts = splitDash("60024417");
        assertEquals(List.of("60024417"), parts);
    }

    @Test
    void splitDashReturnsEmptyForBlankInput() {
        assertEquals(List.of(), splitDash("   "));
        assertEquals(List.of(), splitDash(null));
    }

    @Test
    void mapByKeySkipsBlankKeysAndKeepsLastValueForDuplicateKey() {
        ScopusAuthorFact first = new ScopusAuthorFact();
        first.setAuthorId("a1");
        first.setName("First");

        ScopusAuthorFact blank = new ScopusAuthorFact();
        blank.setAuthorId("  ");
        blank.setName("Blank");

        ScopusAuthorFact second = new ScopusAuthorFact();
        second.setAuthorId("a1");
        second.setName("Second");

        Map<String, ScopusAuthorFact> mapped = mapByKey(List.of(first, blank, second), ScopusAuthorFact::getAuthorId);
        assertEquals(1, mapped.size());
        assertEquals("Second", mapped.get("a1").getName());
    }

    @Test
    void isUserDefinedSourceMatchesKnownSourcesCaseInsensitively() {
        assertEquals(true, isUserDefinedSource("user_defined"));
        assertEquals(true, isUserDefinedSource(" user_publication_wizard "));
        assertEquals(false, isUserDefinedSource("SCOPUS_JSON_BOOTSTRAP"));
    }

    private String hashKey(String... values) {
        return ReflectionTestUtils.invokeMethod(service, "hashKey", (Object) values);
    }

    private String normalizeIssn(String value) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeIssn", value);
    }

    private String normalizeFundingKey(String acronym, String number, String sponsor) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeFundingKey", acronym, number, sponsor);
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return ReflectionTestUtils.invokeMethod(service, "text", node, field);
    }

    private Boolean boolValue(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return ReflectionTestUtils.invokeMethod(service, "boolValue", node, field);
    }

    private boolean samePayloadHash(String previous, String current) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "samePayloadHash", previous, current));
    }

    private String decodeHtmlEntities(String value) {
        return ReflectionTestUtils.invokeMethod(service, "decodeHtmlEntities", value);
    }

    private Integer intValue(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return ReflectionTestUtils.invokeMethod(service, "intValue", node, field);
    }

    private String arrayValue(List<String> values, int index) {
        return ReflectionTestUtils.invokeMethod(service, "arrayValue", values, index);
    }

    private List<String> distinctNonBlank(List<String> values) {
        return ReflectionTestUtils.invokeMethod(service, "distinctNonBlank", values);
    }

    private String normalizeForNameMerge(String value) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeForNameMerge", value);
    }

    private long nanosToMillis(long nanos) {
        return ReflectionTestUtils.invokeMethod(service, "nanosToMillis", nanos);
    }

    private String sample(ScopusImportEvent event, String message) {
        return ReflectionTestUtils.invokeMethod(service, "sample", event, message);
    }

    private <T> Map<String, T> mapByKey(List<T> values, java.util.function.Function<T, String> keyExtractor) {
        return ReflectionTestUtils.invokeMethod(service, "mapByKey", values, keyExtractor);
    }

    private boolean isUserDefinedSource(String source) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "isUserDefinedSource", source));
    }

    private List<String> splitDash(String value) {
        return ReflectionTestUtils.invokeMethod(service, "splitDash", value);
    }
}
