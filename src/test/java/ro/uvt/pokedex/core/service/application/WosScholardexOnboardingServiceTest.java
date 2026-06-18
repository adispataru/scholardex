package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusForumFact;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class WosScholardexOnboardingServiceTest {

    @Mock private WosJournalIdentityRepository journalIdentityRepository;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository scholardexForumFactRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    private WosScholardexOnboardingService service() {
        ConflictRecorder conflictRecorder = new ConflictRecorder(scholardexIdentityConflictRepository, sourceLinkService);
        ForumMergeEngine mergeEngine = new ForumMergeEngine(
                journalIdentityRepository,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                sourceLinkService,
                scholardexIdentityConflictRepository,
                new ForumMergeSafetyRule(),
                conflictRecorder
        );
        return new WosScholardexOnboardingService(
                journalIdentityRepository,
                sourceLinkService,
                scholardexPublicationFactRepository,
                conflictRecorder,
                mergeEngine,
                scholardexIdentityConflictRepository
        );
    }

    // Maps a test WosRankingView fixture to the stage-3 WosJournalIdentity that runWosOnboarding now
    // reads (mirrors WosScholardexOnboardingService.toRankingView / WosProjectionBuilderService).
    private static WosJournalIdentity identity(WosRankingView v) {
        WosJournalIdentity id = new WosJournalIdentity();
        id.setId(v.getId());
        id.setTitle(v.getName());
        id.setPrimaryIssn(v.getIssn());
        id.setEIssn(v.getEIssn());
        id.setAliasIssns(v.getAlternativeIssns() == null ? new ArrayList<>() : new ArrayList<>(v.getAlternativeIssns()));
        id.setAlternativeNames(v.getAlternativeNames() == null ? new ArrayList<>() : new ArrayList<>(v.getAlternativeNames()));
        return id;
    }

    @Test
    void runWosOnboardingCreatesCanonicalForumForWosOnlyJournal() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-1");
        rankingView.setName("Journal of Testing");
        rankingView.setIssn("1050124X");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact savedForum = forumCaptor.getValue();
        assertTrue(savedForum.getId().startsWith("sforum_"));
        assertEquals(List.of("wos-j-1"), savedForum.getWosForumIds());
        assertEquals("1050-124X", savedForum.getIssn());
    }

    @Test
    void runWosOnboardingRecordsConflictWhenForumExternalIdAlreadyLinkedInsteadOfCrashing() {
        // Two WoS journals can resolve to the same scopus forum id; the candidate search keys on
        // ISSN/name, not the external forum id, so the second save collides on the H54.2 unique index
        // uniq_scholardex_forum_scopus_id. The onboarding must record a conflict and skip, not 500.
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-dup");
        rankingView.setName("Journal Sharing A Scopus Forum");
        rankingView.setIssn("1335342X");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenThrow(new DuplicateKeyException(
                        "E11000 duplicate key error collection: scholardex.forum_facts index: uniq_scholardex_forum_scopus_id"));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-dup"),
                eq("FORUM_EXTERNAL_ID_ALREADY_LINKED"), eq("OPEN"))).thenReturn(Optional.empty());

        ImportProcessingResult result = assertDoesNotThrow(() -> service.runWosOnboarding("batch-1", "corr-1"));

        assertTrue(result.getSkippedCount() >= 1);
        assertEquals(0, result.getImportedCount());
        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(scholardexIdentityConflictRepository).save(conflictCaptor.capture());
        assertEquals("FORUM_EXTERNAL_ID_ALREADY_LINKED", conflictCaptor.getValue().getReasonCode());
        assertEquals(ScholardexEntityType.FORUM, conflictCaptor.getValue().getEntityType());
    }

    @Test
    void runWosOnboardingQuarantinesPublicationSourceLinkCollision() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:123");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION,
                "WOS:123"
        )).thenReturn(List.of(existing));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:123"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.empty());

        service.runWosOnboarding("batch-1", "corr-1");

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(scholardexIdentityConflictRepository).save(conflictCaptor.capture());
        assertEquals("SOURCE_ID_COLLISION", conflictCaptor.getValue().getReasonCode());
        assertEquals(ScholardexEntityType.PUBLICATION, conflictCaptor.getValue().getEntityType());
    }

    @Test
    void runWosOnboardingSkipsJournalWhenIdMissing() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("   ");
        rankingView.setName("No Id Journal");
        rankingView.setIssn("1234-5679");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        verify(scholardexForumFactRepository, never()).save(any());
        verify(sourceLinkService, never()).link(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void runWosOnboardingDisambiguatesByPrimaryIssnInsteadOfConflicting() {
        // H66B M8: WoS-last hits the same multi-candidate matches Scopus does (a journal sharing only an
        // eISSN with a sibling). The primary-ISSN tiebreak, now applied to WoS too, resolves to the one
        // candidate carrying the WoS journal's primary print ISSN instead of opening an ambiguity conflict.
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-epjc");
        rankingView.setName("European Physical Journal C");
        rankingView.setIssn("1434-6044");
        rankingView.setEIssn("1434-6052");

        ScholardexForumFact primaryMatch = new ScholardexForumFact();
        primaryMatch.setId("cf_eurphysjc");
        primaryMatch.setName("EUR PHYS J C");
        primaryMatch.setIssn("1434-6044");

        ScholardexForumFact eIssnSibling = new ScholardexForumFact();
        eIssnSibling.setId("cf_zeitschrift");
        eIssnSibling.setName("Zeitschrift fuer Physik C");
        eIssnSibling.setIssn("0170-9739");
        eIssnSibling.setEIssn("1434-6052");

        when(journalIdentityRepository.findAll()).thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(primaryMatch, eIssnSibling));
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(scholardexForumFactRepository).save(argThat(f ->
                "cf_eurphysjc".equals(f.getId()) && f.getWosForumIds().contains("wos-epjc")));
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "WOS".equals(c.source())
                                && "wos-epjc".equals(c.sourceRecordId()) && "cf_eurphysjc".equals(c.canonicalEntityId())
                                && "LINKED".equals(c.targetState()))),
                any());
    }

    @Test
    void relinkAmbiguousWosForumsResolvesNowUnambiguousJournalAndClosesConflict() {
        // H66B M10: a WoS journal that quarantined as ambiguous (its duplicate forum candidate has since been
        // collapsed by the membership dedup) is re-driven; with a single surviving candidate it links and the
        // stale OPEN conflict is marked RESOLVED.
        WosScholardexOnboardingService service = service();

        ScholardexIdentityConflict open = new ScholardexIdentityConflict();
        open.setEntityType(ScholardexEntityType.FORUM);
        open.setIncomingSource("WOS");
        open.setIncomingSourceRecordId("wos-relink");
        open.setReasonCode("AMBIGUOUS_ISSN_MATCH");
        open.setStatus("OPEN");

        WosJournalIdentity journal = new WosJournalIdentity();
        journal.setId("wos-relink");
        journal.setTitle("Journal Relink");
        journal.setPrimaryIssn("12345679");

        ScholardexForumFact survivor = new ScholardexForumFact();
        survivor.setId("cf-survivor");
        survivor.setName("Journal Relink");
        survivor.setIssn("1234-5679");

        when(scholardexIdentityConflictRepository.findByEntityTypeAndStatus(ScholardexEntityType.FORUM, "OPEN"))
                .thenReturn(List.of(open));
        when(journalIdentityRepository.findAllById(List.of("wos-relink"))).thenReturn(List.of(journal));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(survivor));
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-relink"), eq("AMBIGUOUS_ISSN_MATCH"), eq("OPEN")
        )).thenReturn(Optional.of(open));

        ImportProcessingResult result = service.relinkAmbiguousWosForums("batch-1", "corr-1-relink");

        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(scholardexForumFactRepository).save(argThat(f ->
                "cf-survivor".equals(f.getId()) && f.getWosForumIds() != null && f.getWosForumIds().contains("wos-relink")));
        verify(scholardexIdentityConflictRepository).save(argThat(c ->
                "RESOLVED".equals(c.getStatus()) && "wos-forum-onboarding".equals(c.getResolvedBy())));
    }

    @Test
    void relinkAmbiguousWosForumsIsNoOpWhenNoOpenWosAmbiguity() {
        WosScholardexOnboardingService service = service();
        when(scholardexIdentityConflictRepository.findByEntityTypeAndStatus(ScholardexEntityType.FORUM, "OPEN"))
                .thenReturn(List.of());

        ImportProcessingResult result = service.relinkAmbiguousWosForums("batch-1", "corr-1-relink");

        assertEquals(0, result.getProcessedCount());
        verify(journalIdentityRepository, org.mockito.Mockito.never()).findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any());
    }

    @Test
    void runWosOnboardingMarksConflictForAmbiguousCanonicalForumCandidatesByIssn() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-amb");
        rankingView.setName("Journal X");
        rankingView.setIssn("12345679");

        ScholardexForumFact f1 = new ScholardexForumFact();
        f1.setId("cf1");
        f1.setIssn("1234-5679");
        ScholardexForumFact f2 = new ScholardexForumFact();
        f2.setId("cf2");
        f2.setEIssn("1234-5679");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(f1, f2));
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-amb"), eq("AMBIGUOUS_ISSN_MATCH"), eq("OPEN")
        )).thenReturn(Optional.empty());

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getSkippedCount());
        // H66: the conflict source-link is batched as a CONFLICT command via batchUpsertWithState.
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "WOS".equals(c.source())
                                && "wos-j-amb".equals(c.sourceRecordId()) && "AMBIGUOUS_ISSN_MATCH".equals(c.reason())
                                && "CONFLICT".equals(c.targetState()))),
                any());
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                conflict.getEntityType() == ScholardexEntityType.FORUM
                        && "AMBIGUOUS_ISSN_MATCH".equals(conflict.getReasonCode())
                        && conflict.getCandidateCanonicalIds().containsAll(List.of("cf1", "cf2"))
        ));
    }

    @Test
    void runWosOnboardingUsesExistingForumSourceLinkAndMarksUpdated() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-2");
        rankingView.setName("Journal Existing");
        rankingView.setIssn("12345679");

        ScholardexForumFact existingForum = new ScholardexForumFact();
        existingForum.setId("cf-existing");
        existingForum.setName("Existing");
        existingForum.setAggregationType("JOURNAL");

        ScholardexSourceLink existingLink = new ScholardexSourceLink();
        existingLink.setEntityType(ScholardexEntityType.FORUM);
        existingLink.setSource("WOS");
        existingLink.setSourceRecordId("wos-j-2");
        existingLink.setCanonicalEntityId("cf-existing");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(existingForum));
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        // H66: re-run-idempotency now reads the preloaded source-link map, not a per-row findByKey.
        when(sourceLinkService.findByEntityTypeAndSourceRecordIds(eq(ScholardexEntityType.FORUM), any()))
                .thenReturn(List.of(existingLink));
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-2", "corr-2");

        assertEquals(1, result.getUpdatedCount());
        verify(scholardexForumFactRepository).save(argThat(f -> "cf-existing".equals(f.getId())));
        // H66: link is batched via batchUpsertWithState.
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "WOS".equals(c.source())
                                && "wos-j-2".equals(c.sourceRecordId()) && "cf-existing".equals(c.canonicalEntityId())
                                && "LINKED".equals(c.targetState()) && "wos-forum-onboarding".equals(c.reason()))),
                any());
    }

    @Test
    void runWosOnboardingOpensInvalidIssnConflictButStillImportsForum() {
        WosScholardexOnboardingService service = service();

        WosRankingView rankingView = new WosRankingView();
        rankingView.setId("wos-j-badissn");
        rankingView.setName("Bad ISSN Journal");
        rankingView.setIssn("??");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of(identity(rankingView)));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("wos-j-badissn"), eq("NORMALIZATION_INVALID_ISSN"), eq("OPEN")
        )).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-3", "corr-3");

        assertEquals(1, result.getImportedCount());
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                conflict.getEntityType() == ScholardexEntityType.FORUM
                        && "NORMALIZATION_INVALID_ISSN".equals(conflict.getReasonCode())
        ));
    }

    @Test
    void runWosOnboardingSkipsNonWosPublicationIdsAndLinksOnlyValidWosIds() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact nonWos = new ScholardexPublicationFact();
        nonWos.setId("spub-non");
        nonWos.setWosId("NON_WOS");
        ScholardexPublicationFact valid = new ScholardexPublicationFact();
        valid.setId("spub-ok");
        valid.setWosId("WOS:OK");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(nonWos, valid));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(eq(ScholardexEntityType.PUBLICATION), anyString()))
                .thenReturn(List.of());

        service.runWosOnboarding("batch-4", "corr-4");
        verify(sourceLinkService, times(1)).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:OK"), eq("spub-ok"),
                eq("wos-publication-link"), isNull(), eq("batch-4"), eq("corr-4"), eq(false)
        );
    }

    @Test
    void runWosOnboardingPublicationCollisionReusesExistingOpenConflict() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:dup");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        ScholardexIdentityConflict existingConflict = new ScholardexIdentityConflict();
        existingConflict.setId("conf_existing");
        existingConflict.setDetectedAt(java.time.Instant.parse("2024-01-01T00:00:00Z"));

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(ScholardexEntityType.PUBLICATION, "WOS:dup"))
                .thenReturn(List.of(existing));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:dup"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(existingConflict));

        service.runWosOnboarding("batch-z", "corr-z");

        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf_existing".equals(conflict.getId())
                        && conflict.getDetectedAt() != null
                        && "batch-z".equals(conflict.getSourceBatchId())
                        && "corr-z".equals(conflict.getSourceCorrelationId())
        ));
    }

    @Test
    void runWosOnboardingMapsJournalIdentityAliasIssnsAndNames() {
        WosScholardexOnboardingService service = service();

        // Forum onboarding now reads stage-3 wos.journal_identity directly (not the stage-4 ranking
        // view). Verify the identity's alias ISSNs flow into the canonical forum.
        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("wos-map-1");
        identity.setTitle("Mapped Journal");
        identity.setPrimaryIssn("1234-5679");
        identity.setEIssn("8765-4326");
        identity.setAliasIssns(new ArrayList<>(List.of("11112220", "3333-4447")));
        identity.setAlternativeNames(new ArrayList<>(List.of("Alt Name A", "Alt Name B")));

        when(journalIdentityRepository.findAll()).thenReturn(List.of(identity));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.runWosOnboarding("batch-map", "corr-map");

        verify(scholardexForumFactRepository).save(argThat(f ->
                f.getWosForumIds() != null
                        && f.getWosForumIds().contains("wos-map-1")
                        && f.getAliasIssns() != null
                        && f.getAliasIssns().contains("3333-4447")
        ));
    }

    @Test
    void runWosOnboardingPublicationLinksProcessedInSortedIdOrder() {
        WosScholardexOnboardingService service = service();

        ScholardexPublicationFact p2 = new ScholardexPublicationFact();
        p2.setId("spub-b");
        p2.setWosId("WOS:B");
        ScholardexPublicationFact p1 = new ScholardexPublicationFact();
        p1.setId("spub-a");
        p1.setWosId("WOS:A");

        when(journalIdentityRepository.findAll())
                .thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(p2, p1));
        when(sourceLinkService.findByEntityTypeAndSourceRecordId(eq(ScholardexEntityType.PUBLICATION), anyString()))
                .thenReturn(List.of());

        service.runWosOnboarding("batch-order", "corr-order");

        var order = inOrder(sourceLinkService);
        order.verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:A"), eq("spub-a"),
                eq("wos-publication-link"), isNull(), eq("batch-order"), eq("corr-order"), eq(false)
        );
        order.verify(sourceLinkService).link(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:B"), eq("spub-b"),
                eq("wos-publication-link"), isNull(), eq("batch-order"), eq("corr-order"), eq(false)
        );
    }

    @Test
    void runScopusForumCanonicalizationDoesNotBridgeDifferentJournalsSharingAnEissn() {
        // H57 Layer 1: a Scopus forum matching a canonical candidate ONLY via a shared eISSN, while
        // carrying a different primary print ISSN and an unrelated name, must NOT be folded in. Mint a
        // separate forum + flag FORUM_CROSS_JOURNAL_ISSN.
        WosScholardexOnboardingService service = service();

        ScopusForumFact storedProducts = new ScopusForumFact();
        storedProducts.setSourceId("20954");
        storedProducts.setPublicationName("Journal of Stored Products Research");
        storedProducts.setIssn("0022-474X");

        ScholardexForumFact steroid = new ScholardexForumFact(); // misassigned eISSN bridges the two
        steroid.setId("cf_steroid");
        steroid.setName("Journal of Steroid Biochemistry");
        steroid.setIssn("0960-0760");
        steroid.setEIssn("0022-474X");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(storedProducts));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(steroid));
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.runScopusForumCanonicalization("batch-s", "corr-s");

        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact saved = forumCaptor.getValue();
        assertTrue(!"cf_steroid".equals(saved.getId())); // separate forum, not folded into Steroid
        assertEquals("0022-474X", saved.getIssn());
        verify(scholardexIdentityConflictRepository).save(argThat(c ->
                "FORUM_CROSS_JOURNAL_ISSN".equals(c.getReasonCode())
                        && c.getEntityType() == ScholardexEntityType.FORUM));
    }

    @Test
    void runWosOnboardingDropsEissnThatIsAnotherJournalsPrimaryPrintIssn() {
        // H57 Layer 2: the WoS Steroid identity carries eIssn 0022-474X, which is Stored Products'
        // primary print ISSN. That misassigned token is dropped, so it never enters Steroid's identity.
        WosScholardexOnboardingService service = service();

        WosJournalIdentity steroid = new WosJournalIdentity();
        steroid.setId("jid_steroid");
        steroid.setTitle("Journal of Steroid Biochemistry");
        steroid.setPrimaryIssn("0960-0760");
        steroid.setEIssn("0022-474X");

        ScopusForumFact storedProducts = new ScopusForumFact(); // makes 0022-474X a known primary print ISSN
        storedProducts.setSourceId("20954");
        storedProducts.setIssn("0022-474X");

        when(journalIdentityRepository.findAll()).thenReturn(List.of(steroid));
        when(scopusForumFactRepository.findAll()).thenReturn(List.of(storedProducts));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.runWosOnboarding("batch-w", "corr-w");

        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact saved = forumCaptor.getValue();
        assertEquals("0960-0760", saved.getIssn());
        assertTrue(!"0022-474X".equals(saved.getEIssn()));
        assertTrue(saved.getAliasIssns() == null || !saved.getAliasIssns().contains("0022-474X"));
    }

    @Test
    void runScopusForumCanonicalizationCreatesCanonicalForumAndScopusSourceLinkForOrphan() {
        WosScholardexOnboardingService service = service();

        ScopusForumFact scopusForum = new ScopusForumFact();
        scopusForum.setSourceId("scopus-forum-1");
        scopusForum.setPublicationName("Orphan Journal");
        scopusForum.setIssn("12345679");
        scopusForum.setAggregationType("Journal");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(scopusForum));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runScopusForumCanonicalization("batch-s", "corr-s");

        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact saved = forumCaptor.getValue();
        assertTrue(saved.getId().startsWith("sforum_"));
        assertEquals(List.of("scopus-forum-1"), saved.getScopusForumIds());
        assertEquals("1234-5679", saved.getIssn());
        assertEquals("SCOPUS", saved.getSource());
        // H66: source links are now batched via batchUpsertWithState instead of per-row link().
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "SCOPUS".equals(c.source())
                                && "scopus-forum-1".equals(c.sourceRecordId()) && saved.getId().equals(c.canonicalEntityId())
                                && "LINKED".equals(c.targetState()) && "scopus-forum-onboarding".equals(c.reason()))),
                any());
    }

    @Test
    void runScopusForumCanonicalizationOnlyLinksScopusForumAlreadyFoldedIntoCanonical() {
        // A scopus forum whose id already appears in a canonical forum's scopusForumIds (folded in by
        // WoS onboarding) must not be re-merged/re-saved — only its FORUM/SCOPUS source link is ensured
        // so publication re-pointing can resolve it.
        WosScholardexOnboardingService service = service();

        ScholardexForumFact canonical = new ScholardexForumFact();
        canonical.setId("cf-linked");
        canonical.setScopusForumIds(new ArrayList<>(List.of("scopus-forum-7")));

        ScopusForumFact scopusForum = new ScopusForumFact();
        scopusForum.setSourceId("scopus-forum-7");
        scopusForum.setPublicationName("Already Linked Journal");
        scopusForum.setIssn("13352342");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(scopusForum));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(canonical));

        ImportProcessingResult result = service.runScopusForumCanonicalization("batch-s", "corr-s");

        assertEquals(0, result.getImportedCount());
        assertTrue(result.getSkippedCount() >= 1);
        verify(scholardexForumFactRepository, never()).save(any());
        // H66: batched source link instead of per-row link().
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "SCOPUS".equals(c.source())
                                && "scopus-forum-7".equals(c.sourceRecordId()) && "cf-linked".equals(c.canonicalEntityId())
                                && "LINKED".equals(c.targetState()) && "scopus-forum-onboarding".equals(c.reason()))),
                any());
    }

    @Test
    void runScopusForumCanonicalizationDedupsTwoOrphanScopusForumsSharingAnIssn() {
        WosScholardexOnboardingService service = service();

        ScopusForumFact a = new ScopusForumFact();
        a.setSourceId("scopus-a");
        a.setPublicationName("Shared Journal A");
        a.setIssn("12345679");
        ScopusForumFact b = new ScopusForumFact();
        b.setSourceId("scopus-b");
        b.setPublicationName("Shared Journal B");
        b.setIssn("1234-5679");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(b, a)); // unsorted on purpose
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runScopusForumCanonicalization("batch-s", "corr-s");

        // First orphan creates the canonical forum; the second folds into the same id (shared ISSN).
        assertEquals(1, result.getImportedCount());
        assertEquals(1, result.getUpdatedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository, times(2)).save(forumCaptor.capture());
        List<ScholardexForumFact> saves = forumCaptor.getAllValues();
        assertEquals(saves.get(0).getId(), saves.get(1).getId());
        assertTrue(saves.get(1).getScopusForumIds().containsAll(List.of("scopus-a", "scopus-b")));
    }

    @Test
    void runScopusForumCanonicalizationResolvesStaleAmbiguityConflictWhenForumNowLinks() {
        // A Scopus forum previously left as AMBIGUOUS_ISSN_MATCH that is now linked (here via the
        // already-folded-into-canonical path) must have its stale open conflict marked RESOLVED so it
        // stops showing on /admin/conflicts.
        WosScholardexOnboardingService service = service();

        ScholardexForumFact canonical = new ScholardexForumFact();
        canonical.setId("cf_eurphysjc");
        canonical.setScopusForumIds(new ArrayList<>(List.of("27545")));

        ScopusForumFact scopusForum = new ScopusForumFact();
        scopusForum.setSourceId("27545");
        scopusForum.setPublicationName("European Physical Journal C");
        scopusForum.setIssn("1434-6044");

        ScholardexIdentityConflict staleConflict = new ScholardexIdentityConflict();
        staleConflict.setId("conf_eurphysjc");
        staleConflict.setEntityType(ScholardexEntityType.FORUM);
        staleConflict.setIncomingSource("SCOPUS");
        staleConflict.setIncomingSourceRecordId("27545");
        staleConflict.setReasonCode("AMBIGUOUS_ISSN_MATCH");
        staleConflict.setStatus("OPEN");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(scopusForum));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(canonical));
        // H66: the per-row resolve now only fires when the conflict is in the preloaded OPEN set.
        when(scholardexIdentityConflictRepository.findByEntityTypeAndStatus(ScholardexEntityType.FORUM, "OPEN"))
                .thenReturn(List.of(staleConflict));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                ScholardexEntityType.FORUM, "SCOPUS", "27545", "AMBIGUOUS_ISSN_MATCH", "OPEN"))
                .thenReturn(Optional.of(staleConflict));

        service.runScopusForumCanonicalization("batch-s", "corr-s");

        verify(scholardexIdentityConflictRepository).save(argThat(c ->
                "conf_eurphysjc".equals(c.getId())
                        && "RESOLVED".equals(c.getStatus())
                        && c.getResolvedAt() != null));
    }

    @Test
    void runScopusForumCanonicalizationDisambiguatesByPrimaryIssnInsteadOfConflicting() {
        // H55.6: a scopus forum that matches two canonical forums (one on its primary print ISSN, one
        // only on a shared eISSN — e.g. European Physical Journal C vs its predecessor Zeitschrift)
        // links to the primary-ISSN match instead of opening an ambiguity conflict.
        WosScholardexOnboardingService service = service();

        ScopusForumFact scopusForum = new ScopusForumFact();
        scopusForum.setSourceId("27545");
        scopusForum.setPublicationName("European Physical Journal C");
        scopusForum.setIssn("1434-6044");
        scopusForum.setEIssn("1434-6052");

        ScholardexForumFact primaryMatch = new ScholardexForumFact();
        primaryMatch.setId("cf_eurphysjc");
        primaryMatch.setName("EUR PHYS J C");
        primaryMatch.setIssn("1434-6044");

        ScholardexForumFact eIssnSibling = new ScholardexForumFact();
        eIssnSibling.setId("cf_zeitschrift");
        eIssnSibling.setName("Zeitschrift fuer Physik C");
        eIssnSibling.setIssn("0170-9739");
        eIssnSibling.setEIssn("1434-6052");

        when(scopusForumFactRepository.findAll()).thenReturn(List.of(scopusForum));
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of(primaryMatch, eIssnSibling));
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runScopusForumCanonicalization("batch-s", "corr-s");

        assertEquals(0, result.getSkippedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(scholardexForumFactRepository).save(argThat(f ->
                "cf_eurphysjc".equals(f.getId()) && f.getScopusForumIds().contains("27545")));
        verify(sourceLinkService).batchUpsertWithState(
                argThat(cmds -> cmds.stream().anyMatch(c ->
                        c.entityType() == ScholardexEntityType.FORUM && "SCOPUS".equals(c.source())
                                && "27545".equals(c.sourceRecordId()) && "cf_eurphysjc".equals(c.canonicalEntityId())
                                && "LINKED".equals(c.targetState()) && "scopus-forum-onboarding".equals(c.reason()))),
                any());
        verify(scholardexIdentityConflictRepository, never()).save(any());
    }

    @Test
    void openConflictSetsDetectedAtWhenMissingAndPreservesWhenPresent() {
        WosScholardexOnboardingService service = service();

        ScholardexIdentityConflict missingDetectedAt = new ScholardexIdentityConflict();
        missingDetectedAt.setId("conf-missing");
        missingDetectedAt.setDetectedAt(null);
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("WOS:ONE"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(missingDetectedAt));

        ReflectionTestUtils.invokeMethod(
                service,
                "openConflict",
                ScholardexEntityType.FORUM,
                "WOS",
                "WOS:ONE",
                "SOURCE_ID_COLLISION",
                null,
                "batch-c1",
                "corr-c1"
        );

        ScholardexIdentityConflict withDetectedAt = new ScholardexIdentityConflict();
        withDetectedAt.setId("conf-set");
        withDetectedAt.setDetectedAt(java.time.Instant.parse("2024-01-01T00:00:00Z"));
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.FORUM), eq("WOS"), eq("WOS:TWO"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.of(withDetectedAt));

        ReflectionTestUtils.invokeMethod(
                service,
                "openConflict",
                ScholardexEntityType.FORUM,
                "WOS",
                "WOS:TWO",
                "SOURCE_ID_COLLISION",
                List.of("c1", "c2"),
                "batch-c2",
                "corr-c2"
        );

        verify(scholardexIdentityConflictRepository, times(2)).save(any(ScholardexIdentityConflict.class));
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf-missing".equals(conflict.getId())
                        && conflict.getDetectedAt() != null
                        && conflict.getCandidateCanonicalIds().isEmpty()
        ));
        verify(scholardexIdentityConflictRepository).save(argThat(conflict ->
                "conf-set".equals(conflict.getId())
                        && java.time.Instant.parse("2024-01-01T00:00:00Z").equals(conflict.getDetectedAt())
                        && conflict.getCandidateCanonicalIds().equals(List.of("c1", "c2"))
        ));
    }

}
