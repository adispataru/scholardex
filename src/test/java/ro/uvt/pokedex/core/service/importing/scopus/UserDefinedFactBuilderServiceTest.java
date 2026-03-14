package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDefinedFactBuilderServiceTest {

    @Mock
    private ScopusImportEventRepository importEventRepository;
    @Mock
    private UserDefinedPublicationFactRepository publicationFactRepository;
    @Mock
    private UserDefinedForumFactRepository forumFactRepository;

    private UserDefinedFactBuilderService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new UserDefinedFactBuilderService(
                importEventRepository,
                publicationFactRepository,
                forumFactRepository,
                objectMapper
        );
    }

    @Test
    void buildFactsFromImportEventsMaterializesUserDefinedPublicationAndForumFacts() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setId("ev-1");
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("USER_DEFINED");
        event.setSourceRecordId("USER_DEFINED:PUBLICATION:abc");
        event.setBatchId("batch-1");
        event.setCorrelationId("corr-1");
        event.setPayloadHash("hash-1");
        event.setPayload(objectMapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("eid", "USER_DEFINED:EID:abc"),
                java.util.Map.entry("source_id", "USER_DEFINED:FORUM:def"),
                java.util.Map.entry("title", "Wizard paper"),
                java.util.Map.entry("author_ids", "sauth_1"),
                java.util.Map.entry("author_afids", "saff_1"),
                java.util.Map.entry("afid", "saff_1"),
                java.util.Map.entry("approved", 0),
                java.util.Map.entry("wizardSubmitterEmail", "wizard@example.com"),
                java.util.Map.entry("wizardSubmittedAt", "2026-03-14T10:15:30Z"),
                java.util.Map.entry("publicationName", "Forum X"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "87654321"),
                java.util.Map.entry("aggregationType", "Journal")
        )));
        when(importEventRepository.findAll()).thenReturn(List.of(event));
        when(publicationFactRepository.findBySourceRecordId("USER_DEFINED:PUBLICATION:abc")).thenReturn(Optional.empty());
        when(forumFactRepository.findBySourceRecordId("USER_DEFINED:FORUM:def")).thenReturn(Optional.empty());

        ImportProcessingResult result = service.buildFactsFromImportEvents(null);

        assertEquals(1, result.getProcessedCount());
        assertEquals(2, result.getImportedCount());

        ArgumentCaptor<UserDefinedPublicationFact> publicationCaptor = ArgumentCaptor.forClass(UserDefinedPublicationFact.class);
        verify(publicationFactRepository).save(publicationCaptor.capture());
        assertEquals("USER_DEFINED", publicationCaptor.getValue().getSource());
        assertEquals("USER_DEFINED:PUBLICATION:abc", publicationCaptor.getValue().getSourceRecordId());
        assertEquals("PENDING_OPERATOR_REVIEW", publicationCaptor.getValue().getReviewState());
        assertEquals("wizard@example.com", publicationCaptor.getValue().getWizardSubmitterEmail());

        ArgumentCaptor<UserDefinedForumFact> forumCaptor = ArgumentCaptor.forClass(UserDefinedForumFact.class);
        verify(forumFactRepository).save(forumCaptor.capture());
        assertEquals("USER_DEFINED:FORUM:def", forumCaptor.getValue().getSourceRecordId());
        assertEquals("1234-5678", forumCaptor.getValue().getIssn());
        assertEquals("8765-4321", forumCaptor.getValue().getEIssn());
    }

    @Test
    void buildFactsFromImportEventsIgnoresNonUserDefinedSources() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("SCOPUS");
        event.setSourceRecordId("2-s2.0-1");
        event.setPayload(objectMapper.writeValueAsString(java.util.Map.of("eid", "2-s2.0-1")));
        when(importEventRepository.findAll()).thenReturn(List.of(event));

        ImportProcessingResult result = service.buildFactsFromImportEvents(null);

        assertEquals(0, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
        assertTrue(result.getErrorsSample().isEmpty());
        verify(publicationFactRepository, never()).save(any());
        verify(forumFactRepository, never()).save(any());
    }
}
