package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                java.util.Map.entry("wizardSubmitterResearcherId", "sr-42"),
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
        UserDefinedPublicationFact savedPub = publicationCaptor.getValue();
        assertEquals("USER_DEFINED", savedPub.getSource());
        assertEquals("USER_DEFINED:PUBLICATION:abc", savedPub.getSourceRecordId());
        assertEquals("USER_DEFINED:EID:abc", savedPub.getEid());
        assertEquals("Wizard paper", savedPub.getTitle());
        assertEquals("USER_DEFINED:FORUM:def", savedPub.getForumSourceRecordId());
        assertEquals("PENDING_OPERATOR_REVIEW", savedPub.getReviewState());
        assertEquals("wizard@example.com", savedPub.getWizardSubmitterEmail());
        assertEquals("sr-42", savedPub.getWizardSubmitterResearcherId());
        assertEquals(java.time.Instant.parse("2026-03-14T10:15:30Z"), savedPub.getWizardSubmittedAt());
        assertEquals("batch-1", savedPub.getSourceBatchId());
        assertEquals("corr-1", savedPub.getSourceCorrelationId());

        ArgumentCaptor<UserDefinedForumFact> forumCaptor = ArgumentCaptor.forClass(UserDefinedForumFact.class);
        verify(forumFactRepository).save(forumCaptor.capture());
        UserDefinedForumFact savedForum = forumCaptor.getValue();
        assertEquals("USER_DEFINED:FORUM:def", savedForum.getSourceRecordId());
        assertEquals("1234-5678", savedForum.getIssn());
        assertEquals("8765-4321", savedForum.getEIssn());
        assertEquals("Forum X", savedForum.getPublicationName());
        assertEquals("Journal", savedForum.getAggregationType());
        assertEquals("sr-42", savedForum.getWizardSubmitterResearcherId());
        assertEquals(java.time.Instant.parse("2026-03-14T10:15:30Z"), savedForum.getWizardSubmittedAt());
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

    @Test
    void buildFactsFromImportEventsSkipsUnchangedPayloadAndMarksUpdatedOnChanges() throws Exception {
        ScopusImportEvent unchanged = new ScopusImportEvent();
        unchanged.setId("ev-unchanged");
        unchanged.setEntityType(ScopusImportEntityType.PUBLICATION);
        unchanged.setSource("USER_PUBLICATION_WIZARD");
        unchanged.setSourceRecordId("USER_DEFINED:PUBLICATION:unchanged");
        unchanged.setPayloadHash("hash-same");
        unchanged.setPayload(objectMapper.writeValueAsString(java.util.Map.of("source_id", "USER_DEFINED:FORUM:noop")));

        ScopusImportEvent changed = new ScopusImportEvent();
        changed.setId("ev-changed");
        changed.setEntityType(ScopusImportEntityType.PUBLICATION);
        changed.setSource("USER_DEFINED");
        changed.setSourceRecordId("USER_DEFINED:PUBLICATION:changed");
        changed.setBatchId("batch-2");
        changed.setCorrelationId("corr-2");
        changed.setPayloadHash("hash-new");
        changed.setPayload(objectMapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("source_id", "USER_DEFINED:FORUM:changed"),
                java.util.Map.entry("eid", "EID-CHANGED"),
                java.util.Map.entry("doi", "10.1000/changed"),
                java.util.Map.entry("title", "Changed Title"),
                java.util.Map.entry("subtype", "ar"),
                java.util.Map.entry("subtypeDescription", "Article"),
                java.util.Map.entry("creator", "Changed Creator"),
                java.util.Map.entry("approved", "yes"),
                java.util.Map.entry("openaccess", "yes"),
                java.util.Map.entry("freetoread", "repository"),
                java.util.Map.entry("freetoreadLabel", "Open"),
                java.util.Map.entry("fund_acr", "UEFISCDI"),
                java.util.Map.entry("article_number", "A-12"),
                java.util.Map.entry("pageRange", "10-20"),
                java.util.Map.entry("author_count", "7"),
                java.util.Map.entry("citedby_count", "10"),
                java.util.Map.entry("wizardSubmittedAt", "invalid-time"),
                java.util.Map.entry("reviewStateUpdatedAt", "invalid-time"),
                java.util.Map.entry("coverDate", "2026-01-10"),
                java.util.Map.entry("coverDisplayDate", "January 2026"),
                java.util.Map.entry("description", "Changed abstract"),
                java.util.Map.entry("volume", "42"),
                java.util.Map.entry("issueIdentifier", "7"),
                java.util.Map.entry("correspondingAuthors", "A. One;B. Two"),
                java.util.Map.entry("issn", "12345678"),
                java.util.Map.entry("eIssn", "12"),
                java.util.Map.entry("author_ids", "a1;;a2"),
                java.util.Map.entry("afid", "aff1;aff2"),
                java.util.Map.entry("author_afids", "aff1-aff2"),
                java.util.Map.entry("publicationName", "Forum Updated")
        )));

        UserDefinedPublicationFact existingUnchanged = new UserDefinedPublicationFact();
        existingUnchanged.setLastPayloadHash("hash-same");
        UserDefinedPublicationFact existingChanged = new UserDefinedPublicationFact();
        existingChanged.setLastPayloadHash("hash-old");
        UserDefinedForumFact existingForum = new UserDefinedForumFact();
        existingForum.setLastPayloadHash("hash-old");

        when(importEventRepository.findAll()).thenReturn(List.of(unchanged, changed));
        when(publicationFactRepository.findBySourceRecordId("USER_DEFINED:PUBLICATION:unchanged"))
                .thenReturn(Optional.of(existingUnchanged));
        when(publicationFactRepository.findBySourceRecordId("USER_DEFINED:PUBLICATION:changed"))
                .thenReturn(Optional.of(existingChanged));
        when(forumFactRepository.findBySourceRecordId("USER_DEFINED:FORUM:changed"))
                .thenReturn(Optional.of(existingForum));

        ImportProcessingResult result = service.buildFactsFromImportEvents(null);

        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(2, result.getUpdatedCount());
        verify(publicationFactRepository).save(existingChanged);
        verify(forumFactRepository).save(existingForum);
        assertEquals(Boolean.TRUE, existingChanged.getOpenAccess());
        assertEquals(Integer.valueOf(7), existingChanged.getAuthorCount());
        assertEquals(Integer.valueOf(10), existingChanged.getCitedByCount());
        assertEquals("EID-CHANGED", existingChanged.getEid());
        assertEquals("10.1000/changed", existingChanged.getDoi());
        assertEquals("ar", existingChanged.getSubtype());
        assertEquals("Article", existingChanged.getSubtypeDescription());
        assertEquals("Changed Creator", existingChanged.getCreator());
        assertEquals(List.of("a1", "a2"), existingChanged.getAuthorIds());
        assertEquals(List.of("aff1-aff2"), existingChanged.getAuthorAffiliationSourceIds());
        assertEquals(List.of("A. One", "B. Two"), existingChanged.getCorrespondingAuthors());
        assertEquals(List.of("aff1", "aff2"), existingChanged.getAffiliationIds());
        assertEquals("42", existingChanged.getVolume());
        assertEquals("7", existingChanged.getIssueIdentifier());
        assertEquals("2026-01-10", existingChanged.getCoverDate());
        assertEquals("January 2026", existingChanged.getCoverDisplayDate());
        assertEquals("Changed abstract", existingChanged.getDescription());
        assertEquals("repository", existingChanged.getFreetoread());
        assertEquals("Open", existingChanged.getFreetoreadLabel());
        assertEquals("UEFISCDI", existingChanged.getFundingId());
        assertEquals("A-12", existingChanged.getArticleNumber());
        assertEquals("10-20", existingChanged.getPageRange());
        assertEquals(Boolean.TRUE, existingChanged.getApproved());
        assertEquals("ev-changed", existingChanged.getSourceEventId());
        assertEquals("batch-2", existingChanged.getSourceBatchId());
        assertEquals("corr-2", existingChanged.getSourceCorrelationId());
        assertEquals("hash-new", existingChanged.getLastPayloadHash());
        assertTrue(existingChanged.getUpdatedAt() != null);
        assertEquals("1234-5678", existingForum.getIssn());
        assertEquals("", existingForum.getEIssn());
        assertTrue(existingChanged.getLastMaterializedAt() != null);
    }

    @Test
    void buildFactsFromImportEventsSkipsPublicationWhenSourceRecordIdMissing() throws Exception {
        ScopusImportEvent event = new ScopusImportEvent();
        event.setId("ev-missing-id");
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("USER_DEFINED");
        event.setSourceRecordId(" ");
        event.setPayloadHash("hash-missing");
        event.setPayload(objectMapper.writeValueAsString(java.util.Map.of(
                "source_id", "USER_DEFINED:FORUM:skip",
                "title", "Should skip"
        )));
        when(importEventRepository.findAll()).thenReturn(List.of(event));

        ImportProcessingResult result = service.buildFactsFromImportEvents(null);

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        verify(publicationFactRepository, never()).save(any());
    }

    @Test
    void helperMethodsCoverBooleanNumericAndSamplingBranches() throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree("""
                {
                  "b1": true,
                  "b2": 0,
                  "b3": "yes",
                  "b4": "no",
                  "b5": "unknown",
                  "n1": "12",
                  "n2": "x",
                  "t1": "  hello  ",
                  "empty": ""
                }
                """);
        ScopusImportEvent event = new ScopusImportEvent();
        event.setEntityType(ScopusImportEntityType.PUBLICATION);
        event.setSource("USER_DEFINED");
        event.setSourceRecordId("id-1");
        event.setPayload("{}");
        event.setPayloadHash("hash");

        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "boolValue", node, "b1"));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "boolValue", node, "b2"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "boolValue", node, "b3"));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "boolValue", node, "b4"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "boolValue", node, "b5"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "boolValue", node, "missing"));

        assertEquals(Integer.valueOf(12), ReflectionTestUtils.invokeMethod(service, "intValue", node, "n1"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "intValue", node, "n2"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "intValue", node, "missing"));

        assertEquals("hello", ReflectionTestUtils.invokeMethod(service, "text", node, "t1"));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "text", node, "empty"));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "text", node, "missing"));

        assertEquals(List.of("a", "b"), ReflectionTestUtils.invokeMethod(service, "splitSemicolon", "a; ;b"));
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "splitSemicolon", " "));
        assertEquals(Optional.of(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                ReflectionTestUtils.invokeMethod(service, "parseInstant", "2026-01-01T00:00:00Z"));
        assertEquals(Optional.empty(), ReflectionTestUtils.invokeMethod(service, "parseInstant", "invalid"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "samePayloadHash", " abc ", "abc"));
        assertTrue(!(Boolean) ReflectionTestUtils.invokeMethod(service, "samePayloadHash", "abc", "def"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "isBlank", " "));
        assertEquals("PUBLICATION:USER_DEFINED:id-1 boom",
                ReflectionTestUtils.invokeMethod(service, "sample", event, "boom"));
    }
}
