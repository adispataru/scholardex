package ro.uvt.pokedex.core.service.importing.scopus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        publicationFact.setDoi("10.1000/ABC");
        publicationFact.setTitle("Paper One");
        publicationFact.setSubtype("ar");
        publicationFact.setSubtypeDescription("Article");
        publicationFact.setCoverDate("2026-03-10");
        publicationFact.setCoverDisplayDate("March 2026");
        publicationFact.setCreator("Creator");
        publicationFact.setAuthorCount(2);
        publicationFact.setCorrespondingAuthors(List.of("A. One"));
        publicationFact.setAuthorIds(List.of("sauth_1"));
        publicationFact.setAuthorAffiliationSourceIds(List.of("saff_1"));
        publicationFact.setAffiliationIds(List.of("saff_1"));
        publicationFact.setVolume("42");
        publicationFact.setIssueIdentifier("7");
        publicationFact.setDescription("Desc");
        publicationFact.setCitedByCount(11);
        publicationFact.setOpenAccess(Boolean.TRUE);
        publicationFact.setFreetoread("repo");
        publicationFact.setFreetoreadLabel("gold");
        publicationFact.setFundingId("UEFISCDI");
        publicationFact.setArticleNumber("A-10");
        publicationFact.setPageRange("10-19");
        publicationFact.setApproved(Boolean.TRUE);
        publicationFact.setReviewState("PENDING_OPERATOR_REVIEW");
        publicationFact.setReviewReason("wizard-submission");
        publicationFact.setReviewStateUpdatedAt(Instant.parse("2026-03-14T10:15:30Z"));
        publicationFact.setReviewStateUpdatedBy("wizard@example.com");
        publicationFact.setModerationFlow("USER_DEFINED_WIZARD");
        publicationFact.setWizardSubmitterEmail("wizard@example.com");
        publicationFact.setWizardSubmitterResearcherId("sr-1");
        publicationFact.setWizardSubmittedAt(Instant.parse("2026-03-14T10:15:30Z"));

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
        assertEquals("Forum One", forumCaptor.getValue().getName());
        assertEquals("forum one", forumCaptor.getValue().getNameNormalized());
        assertEquals("1234-5678", forumCaptor.getValue().getIssn());
        assertNull(forumCaptor.getValue().getEIssn());
        assertEquals("Journal", forumCaptor.getValue().getAggregationType());
        assertEquals("journal", forumCaptor.getValue().getAggregationTypeNormalized());
        assertEquals("ev-f", forumCaptor.getValue().getSourceEventId());
        assertEquals("USER_DEFINED", forumCaptor.getValue().getSource());
        assertEquals("USER_DEFINED:FORUM:abc", forumCaptor.getValue().getSourceRecordId());
        assertEquals("batch-1", forumCaptor.getValue().getSourceBatchId());
        assertEquals("corr-1", forumCaptor.getValue().getSourceCorrelationId());
        assertEquals("PENDING_OPERATOR_REVIEW", forumCaptor.getValue().getReviewState());
        assertNull(forumCaptor.getValue().getReviewReason());
        assertNull(forumCaptor.getValue().getReviewStateUpdatedBy());
        assertNull(forumCaptor.getValue().getModerationFlow());
        assertEquals("wizard@example.com", forumCaptor.getValue().getWizardSubmitterEmail());
        assertEquals(Instant.parse("2026-03-14T10:15:30Z"), forumCaptor.getValue().getWizardSubmittedAt());
        assertTrue(forumCaptor.getValue().getCreatedAt() != null);
        assertTrue(forumCaptor.getValue().getUpdatedAt() != null);
        assertEquals(List.of(), forumCaptor.getValue().getAliasIssns());

        ArgumentCaptor<ScholardexPublicationFact> publicationCaptor = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(scholardexPublicationFactRepository).save(publicationCaptor.capture());
        assertEquals("spub_abc", publicationCaptor.getValue().getId());
        assertEquals("USER_DEFINED:PUBLICATION:abc", publicationCaptor.getValue().getUserSourceId());
        assertTrue(publicationCaptor.getValue().getForumId().startsWith("sforum_"));
        assertEquals("10.1000/ABC", publicationCaptor.getValue().getDoi());
        assertEquals("10.1000/abc", publicationCaptor.getValue().getDoiNormalized());
        assertEquals("Paper One", publicationCaptor.getValue().getTitle());
        assertEquals("PENDING_OPERATOR_REVIEW", publicationCaptor.getValue().getReviewState());
        assertEquals("wizard-submission", publicationCaptor.getValue().getReviewReason());
        assertEquals(Instant.parse("2026-03-14T10:15:30Z"), publicationCaptor.getValue().getReviewStateUpdatedAt());
        assertEquals("wizard@example.com", publicationCaptor.getValue().getReviewStateUpdatedBy());
        assertEquals("USER_DEFINED_WIZARD", publicationCaptor.getValue().getModerationFlow());
        assertEquals("wizard@example.com", publicationCaptor.getValue().getWizardSubmitterEmail());
        assertEquals("sr-1", publicationCaptor.getValue().getWizardSubmitterResearcherId());
        assertEquals(Instant.parse("2026-03-14T10:15:30Z"), publicationCaptor.getValue().getWizardSubmittedAt());
        assertEquals("USER_DEFINED:EID:abc", publicationCaptor.getValue().getEid());
        assertEquals("ar", publicationCaptor.getValue().getSubtype());
        assertEquals("Article", publicationCaptor.getValue().getSubtypeDescription());
        assertEquals("ar", publicationCaptor.getValue().getScopusSubtype());
        assertEquals("Article", publicationCaptor.getValue().getScopusSubtypeDescription());
        assertEquals("Creator", publicationCaptor.getValue().getCreator());
        assertEquals(Integer.valueOf(2), publicationCaptor.getValue().getAuthorCount());
        assertEquals(List.of("A. One"), publicationCaptor.getValue().getCorrespondingAuthors());
        assertEquals(List.of("saff_1"), publicationCaptor.getValue().getAffiliationIds());
        assertEquals("42", publicationCaptor.getValue().getVolume());
        assertEquals("7", publicationCaptor.getValue().getIssueIdentifier());
        assertEquals("2026-03-10", publicationCaptor.getValue().getCoverDate());
        assertEquals("March 2026", publicationCaptor.getValue().getCoverDisplayDate());
        assertEquals("Desc", publicationCaptor.getValue().getDescription());
        assertEquals(Integer.valueOf(11), publicationCaptor.getValue().getCitedByCount());
        assertEquals(Boolean.TRUE, publicationCaptor.getValue().getOpenAccess());
        assertEquals("repo", publicationCaptor.getValue().getFreetoread());
        assertEquals("gold", publicationCaptor.getValue().getFreetoreadLabel());
        assertEquals("UEFISCDI", publicationCaptor.getValue().getFundingId());
        assertEquals("A-10", publicationCaptor.getValue().getArticleNumber());
        assertEquals("10-19", publicationCaptor.getValue().getPageRange());
        assertEquals(Boolean.TRUE, publicationCaptor.getValue().getApproved());
        assertEquals("ev-p", publicationCaptor.getValue().getSourceEventId());
        assertEquals("USER_DEFINED", publicationCaptor.getValue().getSource());
        assertEquals("USER_DEFINED:PUBLICATION:abc", publicationCaptor.getValue().getSourceRecordId());
        assertEquals("batch-1", publicationCaptor.getValue().getSourceBatchId());
        assertEquals("corr-1", publicationCaptor.getValue().getSourceCorrelationId());
        assertTrue(publicationCaptor.getValue().getCreatedAt() != null);
        assertTrue(publicationCaptor.getValue().getUpdatedAt() != null);
        assertEquals("paper one", publicationCaptor.getValue().getTitleNormalized());

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

    @Test
    void rebuildCanonicalFactsSkipsForumWithoutSourceRecordId() {
        UserDefinedForumFact forumFact = new UserDefinedForumFact();
        forumFact.setSourceRecordId("  ");
        forumFact.setPublicationName("No Source");
        UserDefinedPublicationFact publicationFact = new UserDefinedPublicationFact();
        publicationFact.setSourceRecordId("  ");

        when(userDefinedForumFactRepository.findAll()).thenReturn(List.of(forumFact));
        when(userDefinedPublicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());

        ImportProcessingResult result = service.rebuildCanonicalFacts();

        assertEquals(2, result.getProcessedCount());
        assertEquals(2, result.getSkippedCount());
        verify(scholardexForumFactRepository, never()).save(any());
        verify(sourceLinkService, never()).link(
                eq(ScholardexEntityType.FORUM),
                eq("USER_DEFINED"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(false)
        );
    }

    @Test
    void rebuildCanonicalFactsUpdatesExistingForumAndPublication() {
        UserDefinedForumFact forumFact = new UserDefinedForumFact();
        forumFact.setSourceRecordId("USER_DEFINED:FORUM:existing");
        forumFact.setSourceEventId("ev-f");
        forumFact.setSourceBatchId("batch-2");
        forumFact.setSourceCorrelationId("corr-2");
        forumFact.setPublicationName("Existing Forum");
        forumFact.setIssn("1234-5678");
        forumFact.setEIssn("8765-4321");
        forumFact.setAggregationType("Journal");

        ScholardexForumFact existingForum = new ScholardexForumFact();
        existingForum.setId("sforum_existing");
        existingForum.setIssn("1234-5678");
        existingForum.setAliasIssns(List.of("0000-0000", "1234-5678", "8765-4321"));
        existingForum.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        UserDefinedPublicationFact publicationFact = new UserDefinedPublicationFact();
        publicationFact.setSourceRecordId("USER_DEFINED:PUBLICATION:existing");
        publicationFact.setSourceEventId("ev-p");
        publicationFact.setSourceBatchId("batch-2");
        publicationFact.setSourceCorrelationId("corr-2");
        publicationFact.setForumSourceRecordId("USER_DEFINED:FORUM:existing");
        publicationFact.setEid("eid-existing");
        publicationFact.setDoi("10.1000/xyz");
        publicationFact.setTitle("Existing Paper");
        publicationFact.setCoverDate("2026-01-01");
        publicationFact.setCreator("Creator");
        publicationFact.setAuthorIds(List.of("author-1"));
        publicationFact.setAuthorAffiliationSourceIds(List.of("saff_1"));
        publicationFact.setAffiliationIds(List.of("saff_1"));

        ScholardexPublicationFact existingPublication = new ScholardexPublicationFact();
        existingPublication.setId("spub_existing");
        existingPublication.setUserSourceId("USER_DEFINED:PUBLICATION:existing");
        existingPublication.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        ScholardexSourceLink authorLink = new ScholardexSourceLink();
        authorLink.setCanonicalEntityId("sauth_1");

        when(userDefinedForumFactRepository.findAll()).thenReturn(List.of(forumFact));
        when(userDefinedPublicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(existingForum));
        when(scholardexPublicationFactRepository.findByUserSourceId("USER_DEFINED:PUBLICATION:existing"))
                .thenReturn(Optional.of(existingPublication));
        when(publicationCanonicalizationService.buildCanonicalPublicationId(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("spub_existing");
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "USER_DEFINED", "author-1"))
                .thenReturn(Optional.of(authorLink));

        ImportProcessingResult result = service.rebuildCanonicalFacts();

        assertEquals(2, result.getProcessedCount());
        assertEquals(0, result.getImportedCount());
        assertEquals(2, result.getUpdatedCount());

        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        assertEquals("sforum_existing", forumCaptor.getValue().getId());
        assertEquals(List.of("0000-0000"), forumCaptor.getValue().getAliasIssns());

        ArgumentCaptor<ScholardexPublicationFact> publicationCaptor = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(scholardexPublicationFactRepository).save(publicationCaptor.capture());
        assertEquals("spub_existing", publicationCaptor.getValue().getId());
        assertEquals(List.of("sauth_1"), publicationCaptor.getValue().getAuthorIds());
        assertEquals(List.of(), publicationCaptor.getValue().getPendingAuthorSourceIds());
    }

    @Test
    void rebuildCanonicalFactsPublicationEdgesHandlePendingUnresolvedDedupAndShortAffiliationList() {
        UserDefinedPublicationFact publicationFact = new UserDefinedPublicationFact();
        publicationFact.setSourceRecordId("USER_DEFINED:PUBLICATION:edge");
        publicationFact.setSourceEventId("ev-edge");
        publicationFact.setSourceBatchId("batch-edge");
        publicationFact.setSourceCorrelationId("corr-edge");
        publicationFact.setForumSourceRecordId("external-forum-id");
        publicationFact.setEid("eid-edge");
        publicationFact.setAuthorIds(List.of("unknown-a", "sauth_known", "sauth_other"));
        publicationFact.setAuthorAffiliationSourceIds(List.of("AF1", "saff_dup-saff_dup"));

        when(userDefinedForumFactRepository.findAll()).thenReturn(List.of());
        when(userDefinedPublicationFactRepository.findAll()).thenReturn(List.of(publicationFact));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findByUserSourceId("USER_DEFINED:PUBLICATION:edge"))
                .thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findByEid("eid-edge"))
                .thenReturn(Optional.empty());
        when(publicationCanonicalizationService.buildCanonicalPublicationId(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("spub_edge");
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "USER_DEFINED", "unknown-a"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "unknown-a"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "USER_DEFINED", "AF1"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "AF1"))
                .thenReturn(Optional.empty());

        ImportProcessingResult result = service.rebuildCanonicalFacts();

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        verify(edgeWriterService, times(3)).upsertAuthorshipEdge(any());
        verify(edgeWriterService, times(1)).upsertPublicationAuthorAffiliationEdge(any());
        verify(sourceLinkService).markConflict(
                eq(ScholardexEntityType.PUBLICATION_AUTHOR_AFFILIATION),
                eq("USER_DEFINED"),
                eq("user_defined:publication:edge::author::unknown-a::affiliation::af1"),
                eq("PUBLICATION_AUTHOR_AFFILIATION_UNRESOLVED"),
                eq("ev-edge"),
                eq("batch-edge"),
                eq("corr-edge"),
                eq(false)
        );
        ArgumentCaptor<ScholardexPublicationFact> publicationCaptor = ArgumentCaptor.forClass(ScholardexPublicationFact.class);
        verify(scholardexPublicationFactRepository).save(publicationCaptor.capture());
        assertEquals(List.of("unknown-a"), publicationCaptor.getValue().getPendingAuthorSourceIds());
        assertEquals(3, publicationCaptor.getValue().getAuthorIds().size());

        ArgumentCaptor<ScholardexEdgeWriterService.EdgeWriteCommand> authorshipCaptor =
                ArgumentCaptor.forClass(ScholardexEdgeWriterService.EdgeWriteCommand.class);
        verify(edgeWriterService, times(3)).upsertAuthorshipEdge(authorshipCaptor.capture());
        assertTrue(authorshipCaptor.getAllValues().stream().anyMatch(c ->
                "UNMATCHED".equals(c.linkState()) && "canonical-author-fallback".equals(c.linkReason())));
        assertTrue(authorshipCaptor.getAllValues().stream().anyMatch(c ->
                "LINKED".equals(c.linkState()) && "publication-authorship-bridge".equals(c.linkReason())));
    }

    @Test
    void helpersCoverFallbackAndSourceLinkResolution() {
        ScholardexSourceLink authorScopus = new ScholardexSourceLink();
        authorScopus.setCanonicalEntityId("sauth_resolved");
        ScholardexSourceLink affScopus = new ScholardexSourceLink();
        affScopus.setCanonicalEntityId("saff_resolved");
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "USER_DEFINED", "A-1")).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AUTHOR, "SCOPUS", "A-1")).thenReturn(Optional.of(authorScopus));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "USER_DEFINED", "AF-1")).thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "AF-1")).thenReturn(Optional.of(affScopus));

        assertEquals("sauth_direct", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAuthorId", "sauth_direct"));
        assertEquals("sauth_resolved", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAuthorId", "A-1"));
        assertEquals("saff_direct", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "saff_direct"));
        assertEquals("saff_resolved", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "AF-1"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "  "));

        @SuppressWarnings("unchecked")
        UserDefinedCanonicalizationService.AuthorBridgeResult bridge =
                ReflectionTestUtils.invokeMethod(service, "bridgeAuthors", List.of("A-1", " ", "A-2"));
        assertEquals(2, bridge.canonicalAuthorIds().size());
        assertEquals(1, bridge.pendingSourceIds().size());

        assertEquals("10.1000/abc", ReflectionTestUtils.invokeMethod(service, "normalizeDoi", "https://doi.org/10.1000/ABC"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeDoi", " "));
        assertEquals("1234-5678", ReflectionTestUtils.invokeMethod(service, "normalizeIssn", "12345678"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeIssn", "12"));
        assertEquals("ecole polytechnique", ReflectionTestUtils.invokeMethod(service, "normalizeName", "École  Polytechnique"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeName", "!!!"));
        assertEquals(List.of("a", "b"), ReflectionTestUtils.invokeMethod(service, "splitDash", "a-b"));
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "splitDash", " "));
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "splitDash", " "));
    }

    @Test
    void forumCandidateAndAliasHelpersCoverIssnAndNamePaths() {
        ScholardexForumFact byIssn = new ScholardexForumFact();
        byIssn.setIssn("1234-5678");
        ScholardexForumFact byAlias = new ScholardexForumFact();
        byAlias.setAliasIssns(List.of("8765-4321"));
        ScholardexForumFact byName = new ScholardexForumFact();
        byName.setName("Forum Name");
        byName.setAggregationType("Journal");

        UserDefinedForumFact sourceIssn = new UserDefinedForumFact();
        sourceIssn.setIssn("12345678");
        sourceIssn.setEIssn("8765-4321");
        UserDefinedForumFact sourceName = new UserDefinedForumFact();
        sourceName.setPublicationName("Forum Name");
        sourceName.setAggregationType("Journal");

        @SuppressWarnings("unchecked")
        List<ScholardexForumFact> issnCandidates = ReflectionTestUtils.invokeMethod(
                service,
                "findForumCandidates",
                List.of(byIssn, byAlias, byName),
                sourceIssn
        );
        assertEquals(2, issnCandidates.size());

        @SuppressWarnings("unchecked")
        List<ScholardexForumFact> nameCandidates = ReflectionTestUtils.invokeMethod(
                service,
                "findForumCandidates",
                List.of(byIssn, byAlias, byName),
                sourceName
        );
        assertEquals(1, nameCandidates.size());

        assertEquals(List.of("0000-0000"), ReflectionTestUtils.invokeMethod(
                service,
                "buildAliasIssns",
                "1234-5678",
                "8765-4321",
                List.of("0000-0000", "1234-5678", "8765-4321")
        ));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "buildCanonicalForumId", "1234-5678", null, "n", "a")).startsWith("sforum_"));
        assertTrue(((String) ReflectionTestUtils.invokeMethod(service, "buildCanonicalAuthorFallbackId", "USER_DEFINED", "A-1")).startsWith("sauth_"));
    }

    @Test
    void loadExistingCanonicalPublicationCoversAmbiguousAndFallbackBranches() {
        UserDefinedPublicationFact source = new UserDefinedPublicationFact();
        source.setSourceRecordId("USER_DEFINED:PUBLICATION:doi");
        source.setDoi("10.1/abc");
        source.setSourceEventId("ev-1");
        source.setSourceBatchId("b1");
        source.setSourceCorrelationId("c1");
        ImportProcessingResult result = new ImportProcessingResult(10);

        when(scholardexPublicationFactRepository.findByUserSourceId("USER_DEFINED:PUBLICATION:doi")).thenReturn(Optional.empty());
        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/abc")).thenReturn(List.of(new ScholardexPublicationFact(), new ScholardexPublicationFact()));

        ScholardexPublicationFact ambiguous = ReflectionTestUtils.invokeMethod(
                service,
                "loadExistingCanonicalPublication",
                source,
                "10.1/abc",
                result
        );
        assertNull(ambiguous);
        assertEquals(1, result.getSkippedCount());
        verify(sourceLinkService).markConflict(
                eq(ScholardexEntityType.PUBLICATION),
                eq("USER_DEFINED"),
                eq("USER_DEFINED:PUBLICATION:doi"),
                eq("USER_DEFINED_PUBLICATION_DOI_AMBIGUOUS"),
                eq("ev-1"),
                eq("b1"),
                eq("c1"),
                eq(false)
        );

        when(scholardexPublicationFactRepository.findAllByDoiNormalized("10.1/abc")).thenReturn(List.of());
        ScholardexPublicationFact created = ReflectionTestUtils.invokeMethod(
                service,
                "loadExistingCanonicalPublication",
                source,
                "10.1/abc",
                result
        );
        assertTrue(created.getId() == null);
    }

    @Test
    void helperNormalizationAndResolutionBranchesAreDeterministic() {
        ScholardexSourceLink forumLink = new ScholardexSourceLink();
        forumLink.setCanonicalEntityId("sforum_linked");
        ScholardexSourceLink affDirect = new ScholardexSourceLink();
        affDirect.setCanonicalEntityId("saff_direct");
        ScholardexSourceLink affScopus = new ScholardexSourceLink();
        affScopus.setCanonicalEntityId("saff_scopus");

        when(sourceLinkService.findByKey(ScholardexEntityType.FORUM, "USER_DEFINED", "USER_DEFINED:FORUM:from-link"))
                .thenReturn(Optional.of(forumLink));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "USER_DEFINED", "AF-DIRECT"))
                .thenReturn(Optional.of(affDirect));
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "USER_DEFINED", "AF-SCOPUS"))
                .thenReturn(Optional.empty());
        when(sourceLinkService.findByKey(ScholardexEntityType.AFFILIATION, "SCOPUS", "AF-SCOPUS"))
                .thenReturn(Optional.of(affScopus));

        assertEquals("sforum_mapped", ReflectionTestUtils.invokeMethod(
                service,
                "resolveCanonicalForumId",
                "USER_DEFINED:FORUM:mapped",
                Map.of("USER_DEFINED:FORUM:mapped", "sforum_mapped")
        ));
        assertEquals("sforum_linked", ReflectionTestUtils.invokeMethod(
                service,
                "resolveCanonicalForumId",
                "USER_DEFINED:FORUM:from-link",
                Map.of()
        ));
        assertEquals("external-forum-id", ReflectionTestUtils.invokeMethod(
                service,
                "resolveCanonicalForumId",
                "external-forum-id",
                Map.of()
        ));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveCanonicalForumId", "  ", Map.of()));

        assertEquals("saff_direct", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "AF-DIRECT"));
        assertEquals("saff_scopus", ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "AF-SCOPUS"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "resolveCanonicalAffiliationId", "   "));

        String authorshipSourceId = ReflectionTestUtils.invokeMethod(
                service,
                "buildAuthorshipSourceRecordId",
                " USER_DEFINED:PUBLICATION:1 ",
                " Author-1 "
        );
        assertEquals("user_defined:publication:1::author::author-1", authorshipSourceId);

        String paaSourceId = ReflectionTestUtils.invokeMethod(
                service,
                "buildPublicationAuthorAffiliationSourceRecordId",
                " USER_DEFINED:PUBLICATION:1 ",
                " Author-1 ",
                " AFF-1 "
        );
        assertEquals("user_defined:publication:1::author::author-1::affiliation::aff-1", paaSourceId);

        String fallbackId = ReflectionTestUtils.invokeMethod(service, "buildCanonicalAuthorFallbackId", " ", " Author-1 ");
        assertTrue(fallbackId.startsWith("sauth_"));
        assertFalse("sauth_".equals(fallbackId));

        String forumIdByIssn = ReflectionTestUtils.invokeMethod(service, "buildCanonicalForumId", "12345678", "87654321", "n", "a");
        String forumIdByName = ReflectionTestUtils.invokeMethod(service, "buildCanonicalForumId", null, null, "Forum Name", "Journal");
        assertTrue(forumIdByIssn.startsWith("sforum_"));
        assertTrue(forumIdByName.startsWith("sforum_"));
        assertFalse(forumIdByIssn.equals(forumIdByName));

        assertEquals("ecole polytechnique", ReflectionTestUtils.invokeMethod(service, "normalizeName", "École  Polytechnique"));
        assertNull(ReflectionTestUtils.invokeMethod(service, "normalizeName", "!!!"));
        assertEquals("10.1000/abc", ReflectionTestUtils.invokeMethod(service, "normalizeDoi", "doi:10.1000/ABC"));
        assertEquals("10.1000/abc", ReflectionTestUtils.invokeMethod(service, "normalizeDoi", "https://doi.org/10.1000/ABC"));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "normalizeToken", "   "));
        assertEquals("mixed", ReflectionTestUtils.invokeMethod(service, "normalizeToken", "  MiXed  "));
        assertEquals(List.of(), ReflectionTestUtils.invokeMethod(service, "safeList", (Object) null));

        String shortHash = ReflectionTestUtils.invokeMethod(service, "shortHash", "material");
        assertEquals(24, shortHash.length());
    }
}
