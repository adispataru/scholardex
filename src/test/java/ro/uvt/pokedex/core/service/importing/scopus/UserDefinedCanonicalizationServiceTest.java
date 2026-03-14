package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDefinedCanonicalizationServiceTest {

    @Mock
    private UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    @Mock
    private UserDefinedForumFactRepository userDefinedForumFactRepository;
    @Mock
    private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock
    private ScholardexForumFactRepository scholardexForumFactRepository;
    @Mock
    private ScholardexSourceLinkService sourceLinkService;
    @Mock
    private ScholardexEdgeWriterService edgeWriterService;
    @Mock
    private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;

    private UserDefinedCanonicalizationService service;

    @BeforeEach
    void setUp() {
        service = new UserDefinedCanonicalizationService(
                userDefinedPublicationFactRepository,
                userDefinedForumFactRepository,
                scholardexPublicationFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                edgeWriterService,
                publicationCanonicalizationService
        );
    }

    @Test
    void rebuildCanonicalFactsMapsUserDefinedFactsIntoCanonicalFactsAndLinks() {
        UserDefinedForumFact forumFact = new UserDefinedForumFact();
        forumFact.setSourceRecordId("USER_DEFINED:FORUM:abc");
        forumFact.setSourceEventId("ev-f");
        forumFact.setSourceBatchId("batch-1");
        forumFact.setSourceCorrelationId("corr-1");
        forumFact.setPublicationName("Forum One");
        forumFact.setIssn("1234-5678");
        forumFact.setAggregationType("Journal");
        forumFact.setReviewState("PENDING_OPERATOR_REVIEW");
        forumFact.setWizardSubmitterEmail("wizard@example.com");
        forumFact.setWizardSubmittedAt(Instant.parse("2026-03-14T10:15:30Z"));

        UserDefinedPublicationFact publicationFact = new UserDefinedPublicationFact();
        publicationFact.setSourceRecordId("USER_DEFINED:PUBLICATION:abc");
        publicationFact.setSourceEventId("ev-p");
        publicationFact.setSourceBatchId("batch-1");
        publicationFact.setSourceCorrelationId("corr-1");
        publicationFact.setForumSourceRecordId("USER_DEFINED:FORUM:abc");
        publicationFact.setEid("USER_DEFINED:EID:abc");
        publicationFact.setTitle("Paper One");
        publicationFact.setCoverDate("2026-03-10");
        publicationFact.setCreator("Creator");
        publicationFact.setAuthorIds(List.of("sauth_1"));
        publicationFact.setAuthorAffiliationSourceIds(List.of("saff_1"));
        publicationFact.setAffiliationIds(List.of("saff_1"));
        publicationFact.setReviewState("PENDING_OPERATOR_REVIEW");

        when(userDefinedForumFactRepository.findAll()).thenReturn(List.of(forumFact));
        when(userDefinedPublicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findByUserSourceId("USER_DEFINED:PUBLICATION:abc")).thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findByEid("USER_DEFINED:EID:abc")).thenReturn(Optional.empty());
        when(publicationCanonicalizationService.buildCanonicalPublicationId(
                any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("spub_abc");

        ImportProcessingResult result = service.rebuildCanonicalFacts();

        assertEquals(2, result.getProcessedCount());
        assertEquals(2, result.getImportedCount());

        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        assertTrue(forumCaptor.getValue().getId().startsWith("sforum_"));
        assertEquals(List.of("USER_DEFINED:FORUM:abc"), forumCaptor.getValue().getUserSourceForumIds());

        ArgumentCaptor<ScholardexPublicationFact> publicationCaptor = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(scholardexPublicationFactRepository).save(publicationCaptor.capture());
        assertEquals("spub_abc", publicationCaptor.getValue().getId());
        assertEquals("USER_DEFINED:PUBLICATION:abc", publicationCaptor.getValue().getUserSourceId());
        assertTrue(publicationCaptor.getValue().getForumId().startsWith("sforum_"));
        assertEquals("PENDING_OPERATOR_REVIEW", publicationCaptor.getValue().getReviewState());

        verify(sourceLinkService).link(eq(ScholardexEntityType.FORUM), eq("USER_DEFINED"), eq("USER_DEFINED:FORUM:abc"),
                any(), eq("user-defined-forum-fact-bridge"), eq("ev-f"), eq("batch-1"), eq("corr-1"), eq(false));
        verify(sourceLinkService).link(eq(ScholardexEntityType.PUBLICATION), eq("USER_DEFINED"), eq("USER_DEFINED:PUBLICATION:abc"),
                eq("spub_abc"), eq("user-defined-fact-bridge"), eq("ev-p"), eq("batch-1"), eq("corr-1"), eq(false));
        verify(edgeWriterService).upsertAuthorshipEdge(any());
        verify(edgeWriterService).upsertPublicationAuthorAffiliationEdge(any());
    }

    @Test
    void rebuildCanonicalFactsQuarantinesAmbiguousForumMatches() {
        UserDefinedForumFact forumFact = new UserDefinedForumFact();
        forumFact.setSourceRecordId("USER_DEFINED:FORUM:amb");
        forumFact.setPublicationName("Forum One");
        forumFact.setIssn("1234-5678");

        ScholardexForumFact first = new ScholardexForumFact();
        first.setId("sforum_1");
        first.setIssn("1234-5678");
        ScholardexForumFact second = new ScholardexForumFact();
        second.setId("sforum_2");
        second.setEIssn("1234-5678");

        when(userDefinedForumFactRepository.findAll()).thenReturn(List.of(forumFact));
        when(userDefinedPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(first, second));

        ImportProcessingResult result = service.rebuildCanonicalFacts();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        verify(sourceLinkService).markConflict(eq(ScholardexEntityType.FORUM), eq("USER_DEFINED"),
                eq("USER_DEFINED:FORUM:amb"), eq("USER_DEFINED_FORUM_AMBIGUOUS"),
                any(), any(), any(), eq(false));
        verify(scholardexForumFactRepository, never()).save(any());
    }
}
