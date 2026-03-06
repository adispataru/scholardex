package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusFundingFact;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
        when(publicationFactRepository.findByEid("2-s2.0-p1")).thenReturn(Optional.empty());
        when(citationFactRepository.findByCitedEidAndCitingEid("2-s2.0-p1", "2-s2.0-c1")).thenReturn(Optional.empty());
        when(forumFactRepository.findBySourceId("f1")).thenReturn(Optional.empty());
        when(authorFactRepository.findByAuthorId("a1")).thenReturn(Optional.empty());
        when(authorFactRepository.findByAuthorId("a2")).thenReturn(Optional.empty());
        when(affiliationFactRepository.findByAfid("af1")).thenReturn(Optional.empty());
        when(affiliationFactRepository.findByAfid("af2")).thenReturn(Optional.empty());
        when(fundingFactRepository.findByFundingKey("pnrr|123|uefiscdi")).thenReturn(Optional.empty());

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(2, result.getProcessedCount());
        assertTrue(result.getImportedCount() >= 2);
        verify(publicationFactRepository).save(any(ScopusPublicationFact.class));
        verify(citationFactRepository).save(any(ScopusCitationFact.class));
        verify(authorFactRepository, atLeastOnce()).save(any(ScopusAuthorFact.class));
        verify(affiliationFactRepository, atLeastOnce()).save(any(ScopusAffiliationFact.class));
        verify(fundingFactRepository, atLeastOnce()).save(any(ScopusFundingFact.class));
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
        when(publicationFactRepository.findByEid("2-s2.0-p1")).thenReturn(Optional.of(new ScopusPublicationFact()));
        when(forumFactRepository.findBySourceId("f1")).thenReturn(Optional.of(new ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact()));
        when(authorFactRepository.findByAuthorId("a1")).thenReturn(Optional.of(new ScopusAuthorFact()));
        when(affiliationFactRepository.findByAfid("af1")).thenReturn(Optional.of(new ScopusAffiliationFact()));

        ImportProcessingResult result = service.buildFactsFromImportEvents();

        assertEquals(1, result.getProcessedCount());
        assertTrue(result.getUpdatedCount() >= 1);
        verify(publicationFactRepository).save(any(ScopusPublicationFact.class));
    }
}
