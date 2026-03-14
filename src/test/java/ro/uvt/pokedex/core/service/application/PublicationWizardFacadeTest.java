package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.service.application.model.WizardPublicationCommand;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @InjectMocks
    private PublicationWizardFacade facade;

    @Test
    void resolveForumIdUsesSelectedExistingForum() {
        Forum existing = new Forum();
        existing.setId("f1");
        when(scholardexProjectionReadService.findForumById("f1")).thenReturn(Optional.of(existing));

        assertEquals(Optional.of("f1"), facade.resolveForumId(new Forum(), "f1"));
    }

    @Test
    void resolveForumIdUsesDeterministicIdForNewForumDraft() {
        Forum draft = new Forum();
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
        Author author = new Author();
        author.setId("a1");
        author.setName("Author One");
        Affiliation affiliation = new Affiliation();
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
        submitter.setResearcherId("r-1");

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

        Forum existing = new Forum();
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
