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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
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
        publicationEvent.setPayload(mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "2-s2.0-p1"),
                java.util.Map.entry("title", "Paper 1"),
                java.util.Map.entry("author_ids", "a1;a2"),
                java.util.Map.entry("author_names", "Alice;Bob"),
                java.util.Map.entry("author_afids", "af1-af2;af2"),
                java.util.Map.entry("afid", "af1;af2"),
                java.util.Map.entry("affilname", "Aff 1;Aff 2"),
                java.util.Map.entry("affiliation_city", "City1;City2"),
                java.util.Map.entry("affiliation_country", "RO;RO"),
                java.util.Map.entry("source_id", "f1"),
                java.util.Map.entry("publicationName", "Forum 1"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "87654321"),
                java.util.Map.entry("aggregationType", "Journal"),
                java.util.Map.entry("fund_acr", "PNRR"),
                java.util.Map.entry("fund_no", "123"),
                java.util.Map.entry("fund_sponsor", "UEFISCDI"),
                java.util.Map.entry("coverDate", "2025-01-01"),
                java.util.Map.entry("citedby_count", 10)
        )));

        ScopusImportEvent citationEvent = new ScopusImportEvent();
        citationEvent.setId("ev2");
        citationEvent.setEntityType(ScopusImportEntityType.CITATION);
        citationEvent.setSource("SCOPUS_JSON_BOOTSTRAP");
        citationEvent.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citationEvent.setBatchId("b1");
        citationEvent.setCorrelationId("c2");
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
        verify(publicationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(citationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(authorFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(affiliationFactRepository, atLeastOnce()).saveAll(anyCollection());
        verify(fundingFactRepository, atLeastOnce()).saveAll(anyCollection());
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
}
