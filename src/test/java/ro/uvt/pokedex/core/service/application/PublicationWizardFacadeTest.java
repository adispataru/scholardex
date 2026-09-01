package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationWizardFacadeTest {

    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private ScopusImportEventIngestionService importEventIngestionService;
    @Mock
    private ScopusCanonicalMaterializationService canonicalMaterializationService;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository;
    @Mock
    private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;

    @InjectMocks
    private PublicationWizardFacade facade;

    @Test
    void listForumsAndFindAuthorsForAffiliation() {
        ScholardexForumView forum = new ScholardexForumView();
        ScholardexAuthorView author = new ScholardexAuthorView();
        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));
        when(scholardexProjectionReadService.findAuthorsByAffiliationId("aff1")).thenReturn(List.of(author));

        assertEquals(1, facade.listForums().size());
        assertTrue(facade.findAuthorsForAffiliation(" ").isEmpty());
        assertEquals(1, facade.findAuthorsForAffiliation("aff1").size());
    }

    @Test
    void resolveForumIdUsesSelectedExistingForum() {
        ScholardexForumView existing = new ScholardexForumView();
        existing.setId("f1");
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.of(existing));

        assertEquals(Optional.of("f1"), facade.resolveForumId(new ScholardexForumView(), "f1"));
    }

    @Test
    void resolveForumIdUsesDeterministicIdForNewForumDraft() {
        ScholardexForumView draft = new ScholardexForumView();
        draft.setPublicationName("Journal of Testing");
        draft.setIssn("1234-5678");
        draft.setAggregationType("Journal");

        Optional<String> first = facade.resolveForumId(draft, null);
        Optional<String> second = facade.resolveForumId(draft, null);

        assertTrue(first.isPresent());
        assertEquals(first, second);
        assertTrue(first.get().startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));
    }

    @Test
    void submitPublicationIngestsCanonicalEventAndBuildsViews() {
        WizardPublicationCommand command = buildCommand();
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("a1");
        author.setName("Author One");
        ScholardexAffiliationView affiliation = new ScholardexAffiliationView();
        affiliation.setAfid("af1");
        affiliation.setName("West University");
        affiliation.setCity("Timisoara");
        affiliation.setCountry("RO");
        author.setAffiliations(List.of(affiliation));

        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of(author));
        when(scholardexProjectionReadService.findAffiliationById("af1")).thenReturn(Optional.of(affiliation));
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-1"));

        User submitter = new User();
        submitter.setEmail("user@example.com");

        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, submitter);

        assertTrue(result.imported());
        assertTrue(result.sourceRecordId().startsWith(UserDefinedWizardOnboardingContract.PUBLICATION_SOURCE_RECORD_PREFIX));
        assertTrue(result.eid().startsWith("USER_DEFINED:EID:"));
        assertTrue(result.forumSourceId().startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> batchCaptor = ArgumentCaptor.forClass(String.class);
        verify(importEventIngestionService).ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                batchCaptor.capture(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                payloadCaptor.capture()
        );
        String batchId = batchCaptor.getValue();
        assertNotNull(batchId);
        assertTrue(batchId.startsWith("wizard-publication-"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("a1", payload.get("author_ids"));
        assertEquals("Author One", payload.get("author_names"));
        assertEquals("af1", payload.get("afid"));
        assertEquals(0, payload.get("approved"));
        assertEquals(result.sourceRecordId(), payload.get("wizardSourceRecordId"));
        assertEquals(result.forumSourceId(), payload.get("source_id"));

        verify(canonicalMaterializationService).rebuildFactsAndViews(eq("wizard-publication-submit"), any());
    }

    @Test
    void submitPublicationPreservesExistingSelectedForumId() {
        WizardPublicationCommand command = buildCommand();
        command.setForum("f-existing");

        ScholardexForumView existing = new ScholardexForumView();
        existing.setId("f-existing");
        when(scholardexProjectionReadService.findForumById("f-existing")).thenReturn(Optional.of(existing));
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-2"));

        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, new User());

        assertEquals("f-existing", result.forumSourceId());
        assertTrue(result.sourceRecordId().startsWith(UserDefinedWizardOnboardingContract.PUBLICATION_SOURCE_RECORD_PREFIX));
    }

    @Test
    void submitPublicationTreatsDuplicateAsNonFatal() {
        WizardPublicationCommand command = buildCommand();
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.skipped());

        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, new User());

        assertFalse(result.imported());
        verify(canonicalMaterializationService).rebuildFactsAndViews(eq("wizard-publication-submit"), any());
    }

    @Test
    void buildPublicationDraftUsesWizardDraftWhenProvided() {
        ScholardexForumView draftForum = new ScholardexForumView();
        draftForum.setPublicationName(" Forum Name ");
        draftForum.setIssn("12345678");
        draftForum.setEIssn("87654321");
        draftForum.setIsbn(" isbn ");
        draftForum.setAggregationType(" Journal ");
        draftForum.setPublisher(" Pub ");

        WizardPublicationCommand command = facade.buildPublicationDraft("f1", "a1,a2", "creator", draftForum);
        assertEquals("Forum Name", command.getWizardForumPublicationName());
        assertEquals("1234-5678", command.getWizardForumIssn());
        assertEquals("8765-4321", command.getWizardForumEIssn());
        assertEquals("isbn", command.getWizardForumIsbn());
        assertEquals("Journal", command.getWizardForumAggregationType());
        assertEquals("Pub", command.getWizardForumPublisher());
        assertEquals(List.of("a1", "a2"), command.getAuthorIds());
        assertEquals("f1", command.getForum());
        assertEquals("creator", command.getCreator());
        assertEquals("a1,a2", command.getAuthorIdsCsv());
    }

    @Test
    void buildPublicationDraftLoadsExistingForumWhenNoWizardDraft() {
        ScholardexForumView existing = new ScholardexForumView();
        existing.setPublicationName("Loaded");
        existing.setIssn("11112222");
        existing.setEIssn("33334444");
        existing.setAggregationType("Conference");
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.of(existing));

        WizardPublicationCommand command = facade.buildPublicationDraft("f1", "a1", "creator", null);
        assertEquals("Loaded", command.getWizardForumPublicationName());
        assertEquals("1111-2222", command.getWizardForumIssn());
        assertEquals("3333-4444", command.getWizardForumEIssn());
        assertEquals("Conference", command.getWizardForumAggregationType());
    }

    @Test
    void submitPublicationValidationAndErrorPaths() {
        WizardPublicationCommand invalid = new WizardPublicationCommand();
        invalid.setCreator("c");
        invalid.setSubtypeDescription("Article");
        invalid.setCoverDate("2026-01-01");
        invalid.setWizardForumAggregationType("Journal");
        invalid.setWizardForumPublicationName("J");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(invalid, new User()));

        WizardPublicationCommand badDate = buildCommand();
        badDate.setCoverDate("06/01/2026");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(badDate, new User()));

        WizardPublicationCommand command = buildCommand();
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.error("boom"));

        assertThrows(IllegalStateException.class, () -> facade.submitPublication(command, new User()));
    }

    @Test
    void submitPublicationValidatesAllRequiredFields() {
        WizardPublicationCommand missingCreator = buildCommand();
        missingCreator.setCreator("  ");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(missingCreator, new User()));

        WizardPublicationCommand missingType = buildCommand();
        missingType.setSubtypeDescription(" ");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(missingType, new User()));

        WizardPublicationCommand missingForum = buildCommand();
        missingForum.setForum(" ");
        missingForum.setWizardForumPublicationName(" ");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(missingForum, new User()));

        WizardPublicationCommand missingForumType = buildCommand();
        missingForumType.setWizardForumAggregationType(" ");
        assertThrows(IllegalArgumentException.class, () -> facade.submitPublication(missingForumType, new User()));
    }

    @Test
    void submitPublicationBuildsSubtypeAndForumSourceFromDraftFields() {
        WizardPublicationCommand command = buildCommand();
        command.setForum(" ");
        command.setSubtype(" ");
        command.setSubtypeDescription("Review");
        command.setWizardForumPublicationName("Journal X");
        command.setWizardForumIssn("1111-2222");
        command.setWizardForumEIssn("3333-4444");
        command.setWizardForumAggregationType("Journal");
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-3"));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, null);
        verify(importEventIngestionService).ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                payloadCaptor.capture()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("re", payload.get("subtype"));
        assertEquals(result.forumSourceId(), payload.get("source_id"));
        assertEquals("", payload.get("wizardSubmitterEmail"));
        assertEquals("", payload.get("wizardSubmitterResearcherId"));
    }

    @Test
    void submitPublicationForumSourceIdChangesWhenDraftIdentityFieldsChange() {
        WizardPublicationCommand commandA = buildCommand();
        commandA.setForum(" ");
        commandA.setWizardForumPublicationName("Journal X");
        commandA.setWizardForumIssn("1111-2222");
        commandA.setWizardForumEIssn("3333-4444");
        commandA.setWizardForumAggregationType("Journal");

        WizardPublicationCommand commandB = buildCommand();
        commandB.setForum(" ");
        commandB.setWizardForumPublicationName("Journal X");
        commandB.setWizardForumIssn("9999-2222");
        commandB.setWizardForumEIssn("3333-4444");
        commandB.setWizardForumAggregationType("Journal");

        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-4"));

        PublicationWizardFacade.SubmissionResult r1 = facade.submitPublication(commandA, new User());
        PublicationWizardFacade.SubmissionResult r2 = facade.submitPublication(commandB, new User());

        assertTrue(r1.forumSourceId().startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));
        assertTrue(r2.forumSourceId().startsWith(UserDefinedWizardOnboardingContract.FORUM_SOURCE_RECORD_PREFIX));
        assertFalse(r1.forumSourceId().equals(r2.forumSourceId()));
    }

    @Test
    void submitPublicationNormalizesForumIdentityFieldsBeforeSourceIdGeneration() {
        WizardPublicationCommand commandA = buildCommand();
        commandA.setForum(" ");
        commandA.setWizardForumPublicationName(" Journal X ");
        commandA.setWizardForumIssn("11112222");
        commandA.setWizardForumEIssn("33334444");
        commandA.setWizardForumAggregationType(" Journal ");

        WizardPublicationCommand commandB = buildCommand();
        commandB.setForum(" ");
        commandB.setWizardForumPublicationName("Journal X");
        commandB.setWizardForumIssn("1111-2222");
        commandB.setWizardForumEIssn("3333-4444");
        commandB.setWizardForumAggregationType("Journal");

        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-6"));

        PublicationWizardFacade.SubmissionResult r1 = facade.submitPublication(commandA, new User());
        PublicationWizardFacade.SubmissionResult r2 = facade.submitPublication(commandB, new User());
        assertEquals(r1.forumSourceId(), r2.forumSourceId());
    }

    @Test
    void submitPublicationEidSuffixMatchesSourceRecordSuffix() {
        WizardPublicationCommand command = buildCommand();
        when(scholardexProjectionReadService.findAuthorsByIdIn(List.of("a1"))).thenReturn(List.of());
        when(importEventIngestionService.ingest(
                eq(ScopusImportEntityType.PUBLICATION),
                eq(UserDefinedWizardOnboardingContract.SOURCE),
                any(),
                any(),
                any(),
                eq(PublicationWizardFacade.PAYLOAD_FORMAT_JSON_OBJECT),
                any())
        ).thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-5"));

        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, new User());
        String sourceSuffix = result.sourceRecordId().substring(result.sourceRecordId().lastIndexOf(':') + 1);
        assertEquals("USER_DEFINED:EID:" + sourceSuffix, result.eid());
    }

    @Test
    void buildPublicationDraftWithoutForumKeepsForumFieldsEmpty() {
        WizardPublicationCommand command = facade.buildPublicationDraft("missing", "a1", "creator", null);
        assertEquals(List.of("a1"), command.getAuthorIds());
        assertNull(command.getWizardForumPublicationName());
    }

    @Test
    void chapterSubmissionMintsADeterministicBookEntityAndSkipsForums() {
        // H99 item 7: a chapter at a publisher outside the Scopus book list (Mirton) mints a USER_DEFINED
        // book entity and the pub carries book_id with NO forum — the Scopus ch/bk shape.
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setTitle("Capitol despre sisteme distribuite");
        command.setCreator("user@example.com");
        command.setSubtype("ch");
        command.setSubtypeDescription("Book Chapter");
        command.setCoverDate("2026-03-01");
        command.setWizardBookTitle("Sisteme distribuite moderne");
        command.setWizardBookIsbn("978-973-52-0000-1");
        command.setWizardBookPublisher("Editura Mirton");
        command.setPageRange("45-78");
        command.setExternalAuthorNames("Ion Popescu\nMaria Ionescu");

        when(bookFactRepository.findById(any())).thenReturn(Optional.empty());
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-book"));

        User submitter = new User();
        submitter.setEmail("user@example.com");

        PublicationWizardFacade.SubmissionResult result = facade.submitPublication(command, submitter);
        assertTrue(result.imported());

        ArgumentCaptor<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact> bookCaptor =
                ArgumentCaptor.forClass(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact.class);
        verify(bookFactRepository).save(bookCaptor.capture());
        var book = bookCaptor.getValue();
        assertTrue(book.getId().startsWith(UserDefinedWizardOnboardingContract.BOOK_ID_PREFIX));
        assertEquals("Editura Mirton", book.getPublisher());
        assertEquals("Sisteme distribuite moderne", book.getTitle());
        assertEquals(Integer.valueOf(2026), book.getPublicationYear());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals(book.getId(), payload.get("book_id"));
        assertEquals("", payload.get("source_id"));                       // no forum minted for a book pub
        // external co-authors count toward the divisor and land in author_names, unminted
        assertEquals(2, payload.get("author_count"));
        assertEquals("Ion Popescu;Maria Ionescu", payload.get("author_names"));
        assertEquals("45-78", payload.get("pageRange"));
    }

    @Test
    void chapterSubmissionAttachesToASelectedListedBook() {
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setTitle("Chapter in a listed volume");
        command.setCreator("user@example.com");
        command.setSubtype("ch");
        command.setSubtypeDescription("Book Chapter");
        command.setCoverDate("2025-06-01");
        command.setBookId("606201523");

        var listed = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact();
        listed.setId("606201523");
        when(bookFactRepository.findById("606201523")).thenReturn(Optional.of(listed));
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-book2"));

        User submitter = new User();
        submitter.setEmail("user@example.com");
        facade.submitPublication(command, submitter);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(importEventIngestionService).ingest(any(), any(), any(), any(), any(), any(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("606201523", payload.get("book_id"));
        verify(bookFactRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void submitAutoConfirmsTheSubmittersAuthorship() {
        // H99 item 7: the researcher just declared their own publication — the CONFIRMED authorship
        // decision is written at submit time (scoring reads only decision-confirmed pubs).
        WizardPublicationCommand command = buildCommand();
        when(importEventIngestionService.ingest(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ScopusImportEventIngestionService.EventIngestionOutcome.imported("ev-3"));
        var canonical = new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact();
        canonical.setId("spub_wizard_1");
        when(scholardexPublicationFactRepository.findByEid(any())).thenReturn(Optional.of(canonical));

        User submitter = new User();
        submitter.setEmail("user@example.com");
        facade.submitPublication(command, submitter);

        verify(publicationAuthorshipDecisionService).upsertDecision(
                eq("user@example.com"), eq("spub_wizard_1"),
                eq(ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision.Status.CONFIRMED),
                eq("wizard-self-submission"));
    }

    @Test
    void bookSubmissionRequiresABookOrItsDraft() {
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setTitle("A book with no venue");
        command.setCreator("user@example.com");
        command.setSubtype("bk");
        command.setSubtypeDescription("Book");
        command.setCoverDate("2026-01-01");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facade.submitPublication(command, new User()));
        assertTrue(ex.getMessage().contains("book"));
    }

    private WizardPublicationCommand buildCommand() {
        WizardPublicationCommand command = new WizardPublicationCommand();
        command.setTitle("A Test Publication");
        command.setDoi("10.1000/xyz");
        command.setCreator("creator-1");
        command.setSubtypeDescription("Article");
        command.setCoverDate("2026-03-08");
        command.setVolume("12");
        command.setIssueIdentifier("2");
        command.setForum("USER_DEFINED:FORUM:seed");
        command.setAuthorIdsCsv("a1");
        command.setWizardForumPublicationName("Journal of Tests");
        command.setWizardForumIssn("1234-5678");
        command.setWizardForumAggregationType("Journal");
        return command;
    }
}
