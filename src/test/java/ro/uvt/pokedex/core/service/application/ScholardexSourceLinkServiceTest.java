package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexSourceLinkServiceTest {

    @Mock
    private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock
    private ImportRunMetricService importRunMetricService;
    @Mock
    private ScholardexProjectionDirtyService projectionDirtyService;

    private ScholardexSourceLinkService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
    }

    @Test
    void linkNormalizesAliasAndWritesCanonicalState() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.FORUM, "WOS", "journal-1")).thenReturn(Optional.empty());

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.FORUM,
                "WOSEXTRACTOR",
                "journal-1",
                "sforum_1",
                "test",
                "ev-1",
                "b-1",
                "c-1",
                false
        );

        assertTrue(result.accepted());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        ScholardexSourceLink saved = captor.getValue();
        assertEquals("WOS", saved.getSource());
        assertEquals("LINKED", saved.getLinkState());
        assertEquals("sforum_1", saved.getCanonicalEntityId());
    }

    @Test
    void linkSkipsRewriteWhenExistingLinkUnchanged() {
        // H56 lever 2: a persisted link with the same durable content (state, canonical, reason) is not
        // re-written, even on an explicit replay with fresh provenance.
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setId("sl1");
        existing.setEntityType(ScholardexEntityType.FORUM);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("27545");
        existing.setLinkState("LINKED");
        existing.setCanonicalEntityId("sforum_a");
        existing.setLinkReason("scopus-forum-onboarding");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.FORUM, "SCOPUS", "27545")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.FORUM, "SCOPUS", "27545", "sforum_a",
                "scopus-forum-onboarding", "ev-2", "b-2", "c-2", true);

        assertTrue(result.accepted());
        verify(sourceLinkRepository, never()).save(any(ScholardexSourceLink.class));
    }

    @Test
    void linkStillRewritesWhenDurableContentChanges() {
        // A real change (UNMATCHED -> LINKED) must be persisted, never skipped by the no-op guard.
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setId("sl2");
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("au-1");
        existing.setLinkState("UNMATCHED");
        existing.setCanonicalEntityId(null);
        existing.setLinkReason("author-bridge");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "au-1")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "au-1", "sauth_1",
                "author-bridge", "ev-1", "b-1", "c-1", false);

        assertTrue(result.accepted());
        verify(sourceLinkRepository).save(any(ScholardexSourceLink.class));
    }

    @Test
    void linkNormalizesUserWizardAliasToUserDefined() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "USER_DEFINED", "record-1")).thenReturn(Optional.empty());

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "USER_PUBLICATION_WIZARD",
                "record-1",
                "spub_1",
                "wizard-submit",
                "ev-1",
                "b-1",
                "c-1",
                false
        );

        assertTrue(result.accepted());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("USER_DEFINED", captor.getValue().getSource());
    }

    @Test
    void findByKeyNormalizesScopusFamilyVariantsToScopus() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setSource("SCOPUS");

        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60000434")).thenReturn(Optional.of(existing));

        Optional<ScholardexSourceLink> resolved = service.findByKey(
                ScholardexEntityType.AFFILIATION,
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "60000434"
        );

        assertTrue(resolved.isPresent());
        assertEquals("SCOPUS", resolved.get().getSource());
    }

    @Test
    void findByKeyStillFallsBackToLegacyRawScopusVariantRows() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setSource("SCOPUS_JSON_UPLOAD");

        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "14027901400")).thenReturn(Optional.empty());
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS_JSON_UPLOAD", "14027901400")).thenReturn(Optional.of(legacy));

        Optional<ScholardexSourceLink> resolved = service.findByKey(
                ScholardexEntityType.AUTHOR,
                "SCOPUS_JSON_UPLOAD",
                "14027901400"
        );

        assertTrue(resolved.isPresent());
        assertEquals("SCOPUS_JSON_UPLOAD", resolved.get().getSource());
    }

    @Test
    void reconcileLinksCollapsesSafeLegacyScopusVariantRowIntoNormalizedScopusRow() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-link");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60000434");
        legacy.setCanonicalEntityId("saff_uvt");
        legacy.setLinkState("LINKED");

        ScholardexSourceLink normalized = new ScholardexSourceLink();
        normalized.setId("normalized-link");
        normalized.setEntityType(ScholardexEntityType.AFFILIATION);
        normalized.setSource("SCOPUS");
        normalized.setSourceRecordId("60000434");
        normalized.setCanonicalEntityId("saff_uvt");
        normalized.setLinkState("LINKED");

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60000434"
        )).thenReturn(Optional.of(normalized));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        verify(sourceLinkRepository).save(argThat(saved ->
                "normalized-link".equals(saved.getId()) && "SCOPUS".equals(saved.getSource())));
        verify(sourceLinkRepository).delete(legacy);
    }

    @Test
    void reconcileLinksLeavesConflictingLegacyScopusVariantRowsAndOpensConflict() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-link");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        legacy.setSourceRecordId("60000434");
        legacy.setCanonicalEntityId("saff_variant");
        legacy.setLinkState("LINKED");

        ScholardexSourceLink normalized = new ScholardexSourceLink();
        normalized.setId("normalized-link");
        normalized.setEntityType(ScholardexEntityType.AFFILIATION);
        normalized.setSource("SCOPUS");
        normalized.setSourceRecordId("60000434");
        normalized.setCanonicalEntityId("saff_uvt");
        normalized.setLinkState("LINKED");

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60000434"
        )).thenReturn(Optional.of(normalized));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.AFFILIATION), eq("SCOPUS"), eq("60000434"), eq("SOURCE_LINK_RELINK_REJECTED"), eq("OPEN")
        )).thenReturn(Optional.empty());

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.skipped());
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
        verify(sourceLinkRepository, never()).delete(any());
    }

    @Test
    void linkedCanonicalIdIsImmutableAndOpensConflictOnRelinkAttempt() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("WOS");
        existing.setSourceRecordId("WOS:1");
        existing.setCanonicalEntityId("spub_old");
        existing.setLinkState("LINKED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "WOS", "WOS:1")).thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:1"), eq("SOURCE_LINK_RELINK_REJECTED"), eq("OPEN")
        )).thenReturn(Optional.empty());

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "WOS",
                "WOS:1",
                "spub_new",
                "relink",
                null,
                "b",
                "c",
                true
        );

        assertFalse(result.accepted());
        verify(sourceLinkRepository, never()).save(any(ScholardexSourceLink.class));
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void higherPrecedenceSourceRelinksLinkedCanonicalIdWithoutOpeningConflict() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("2-s2.0-1");
        existing.setCanonicalEntityId("spub_old");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-07T14:31:06Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "2-s2.0-1")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "SCOPUS_JSON_BOOTSTRAP",
                "2-s2.0-1",
                "spub_new",
                "bootstrap-relink",
                "event-new",
                "batch-new",
                "corr-new",
                false
        );

        assertTrue(result.accepted());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("spub_new", captor.getValue().getCanonicalEntityId());
        assertEquals("event-new", captor.getValue().getSourceEventId());
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void higherPrecedenceSourceRelinkRecordsAggregateMetric() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("2-s2.0-1");
        existing.setCanonicalEntityId("spub_old");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-07T14:31:06Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "2-s2.0-1")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "SCOPUS_JSON_BOOTSTRAP",
                "2-s2.0-1",
                "spub_new",
                "bootstrap-relink",
                "event-new",
                "batch-new",
                "corr-new",
                false
        );

        assertTrue(result.accepted());
        verify(importRunMetricService).record(
                "batch-new",
                "SCOPUS_JSON_BOOTSTRAP",
                "PUBLICATION",
                "auto-relinked-identity-link",
                1
        );
    }

    @Test
    void higherPrecedenceIdentityRelinkMarksOldAndNewCanonicalProjectionsDirty() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService,
                projectionDirtyService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("14027901400");
        existing.setCanonicalEntityId("sauth_old");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-07T14:31:06Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "14027901400")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR,
                "SCOPUS_JSON_BOOTSTRAP",
                "14027901400",
                "sauth_new",
                "bootstrap-relink",
                "event-new",
                "batch-new",
                "corr-new",
                false
        );

        assertTrue(result.accepted());
        verify(projectionDirtyService).markDirty(
                ScholardexEntityType.AUTHOR,
                "sauth_old",
                "batch-new",
                "event-new",
                "corr-new",
                "auto-relinked-identity-link"
        );
        verify(projectionDirtyService).markDirty(
                ScholardexEntityType.AUTHOR,
                "sauth_new",
                "batch-new",
                "event-new",
                "corr-new",
                "auto-relinked-identity-link"
        );
    }

    @Test
    void higherPrecedenceEdgeRelinkDoesNotMarkIdentityProjectionsDirty() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService,
                projectionDirtyService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHORSHIP);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("authorship-2");
        existing.setCanonicalEntityId("spub_old::sauth_old::SCOPUS");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-07T14:31:06Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHORSHIP, "SCOPUS", "authorship-2")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHORSHIP,
                "SCOPUS_JSON_BOOTSTRAP",
                "authorship-2",
                "spub_new::sauth_new::SCOPUS",
                "bootstrap-edge",
                "event-edge",
                "batch-edge",
                "corr-edge",
                false
        );

        assertTrue(result.accepted());
        verify(projectionDirtyService, never()).markDirty(any(), any(), any(), any(), any(), any());
    }

    @Test
    void batchHigherPrecedenceIdentityRelinkMarksOldAndNewCanonicalProjectionsDirty() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService,
                projectionDirtyService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("14027901400");
        existing.setCanonicalEntityId("sauth_old");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-07T14:31:06Z"));
        ScholardexSourceLinkService.SourceLinkKey key =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "14027901400");

        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR,
                        "SCOPUS_JSON_BOOTSTRAP",
                        "14027901400",
                        "sauth_new",
                        "LINKED",
                        "bootstrap-relink",
                        "event-new",
                        "batch-new",
                        "corr-new",
                        false
                )),
                Map.of(key, existing),
                false
        );

        assertEquals(1, result.acceptedCount());
        verify(sourceLinkRepository).saveAll(any());
        verify(projectionDirtyService).markDirty(
                ScholardexEntityType.AUTHOR,
                "sauth_old",
                "batch-new",
                "event-new",
                "corr-new",
                "auto-relinked-identity-link"
        );
        verify(projectionDirtyService).markDirty(
                ScholardexEntityType.AUTHOR,
                "sauth_new",
                "batch-new",
                "event-new",
                "corr-new",
                "auto-relinked-identity-link"
        );
    }

    @Test
    void lowerPrecedenceSourceSkipsLinkedCanonicalIdRelinkWithoutOpeningConflict() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        existing.setSourceRecordId("2-s2.0-2");
        existing.setCanonicalEntityId("spub_high");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-08T19:50:47Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "2-s2.0-2")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "SCOPUS_JSON_BOOTSTRAP",
                "2-s2.0-2",
                "spub_low",
                "bootstrap-relink",
                "event-low",
                "batch-low",
                "corr-low",
                false
        );

        assertFalse(result.accepted());
        assertEquals("linked-canonical-id-kept-by-precedence", result.reason());
        verify(sourceLinkRepository, never()).save(any(ScholardexSourceLink.class));
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void lowerPrecedenceSourceSkipRecordsAggregateMetric() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        existing.setSourceRecordId("2-s2.0-2");
        existing.setCanonicalEntityId("spub_high");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-08T19:50:47Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "2-s2.0-2")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "SCOPUS_JSON_BOOTSTRAP",
                "2-s2.0-2",
                "spub_low",
                "bootstrap-relink",
                "event-low",
                "batch-low",
                "corr-low",
                false
        );

        assertFalse(result.accepted());
        verify(importRunMetricService).record(
                "batch-low",
                "SCOPUS_JSON_BOOTSTRAP",
                "PUBLICATION",
                "skipped-lower-precedence-identity-link",
                1
        );
    }

    @Test
    void lowerPrecedenceEdgeSourceLinkSkipRecordsEdgeEvidenceMetric() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(
                sourceLinkRepository,
                identityConflictRepository,
                new ImportSourcePrecedencePolicy(),
                importRunMetricService
        );
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHORSHIP);
        existing.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        existing.setSourceRecordId("authorship-1");
        existing.setCanonicalEntityId("spub_1::sauth_high::SCOPUS");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-08T19:50:47Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHORSHIP, "SCOPUS", "authorship-1")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHORSHIP,
                "SCOPUS_JSON_BOOTSTRAP",
                "authorship-1",
                "spub_1::sauth_low::SCOPUS",
                "bootstrap-edge",
                "event-edge",
                "batch-edge",
                "corr-edge",
                false
        );

        assertFalse(result.accepted());
        verify(importRunMetricService).record(
                "batch-edge",
                "SCOPUS_JSON_BOOTSTRAP",
                "AUTHORSHIP",
                "skipped-duplicate-lower-precedence-edge-evidence",
                1
        );
    }

    @Test
    void equalPrecedenceNewerSourceRelinksLinkedCanonicalIdWithoutOpeningConflict() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS_PYTHON_AUTHOR_WORKS");
        existing.setSourceRecordId("2-s2.0-3");
        existing.setCanonicalEntityId("spub_author_works");
        existing.setLinkState("LINKED");
        existing.setUpdatedAt(Instant.parse("2026-05-08T19:50:47Z"));
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "2-s2.0-3")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION,
                "SCOPUS_PYTHON_CITATIONS_PUBLICATION",
                "2-s2.0-3",
                "spub_citations",
                "citations-relink",
                "69fe3edecf213f08bd33119e",
                "batch-citations",
                "corr-citations",
                false
        );

        assertTrue(result.accepted());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("spub_citations", captor.getValue().getCanonicalEntityId());
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void skippedToLinkedRequiresExplicitReplayAttempt() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("123");
        existing.setLinkState("SKIPPED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "123")).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult rejected = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "123", "sauth_1",
                "bridge", null, null, null, false
        );
        assertFalse(rejected.accepted());

        ScholardexSourceLinkService.SourceLinkWriteResult accepted = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "123", "sauth_1",
                "bridge", null, null, null, true
        );
        assertTrue(accepted.accepted());
    }

    @Test
    void batchUpsertUsesPreloadedStateAndReturnsPerItemResults() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("a-1");
        existing.setCanonicalEntityId("sauth_1");
        existing.setLinkState("LINKED");

        ScholardexSourceLinkService.SourceLinkKey key =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "a-1");
        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(
                        new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                ScholardexEntityType.AUTHOR, "SCOPUS", "a-1", "sauth_1", "LINKED",
                                "bridge", null, null, null, false
                        ),
                        new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                                ScholardexEntityType.AUTHOR, "SCOPUS", "a-1", "sauth_2", "LINKED",
                                "bridge", null, null, null, true
                        )
                ),
                Map.of(key, existing)
        );

        assertEquals(1, result.acceptedCount());
        assertEquals(1, result.rejectedCount());
        verify(sourceLinkRepository).saveAll(any());
        verify(identityConflictRepository, never()).save(any(ScholardexIdentityConflict.class));
    }

    @Test
    void batchUpsertFallsBackToRepositoryLookupOnCacheMissWhenEnabled() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setId("link-1");
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS_JSON_UPLOAD");
        existing.setSourceRecordId("14027901400");
        existing.setCanonicalEntityId("sauth_existing");
        existing.setLinkState("LINKED");

        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "14027901400"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR,
                        "SCOPUS_JSON_UPLOAD",
                        "14027901400",
                        "sauth_existing",
                        "LINKED",
                        "scopus-author-bridge",
                        "event-1",
                        "batch-2",
                        "corr-2",
                        true
                )),
                Map.of(),
                true
        );

        assertEquals(1, result.acceptedCount());
        verify(sourceLinkRepository).saveAll(argThat(savedLinks -> {
            if (!(savedLinks instanceof Iterable<?> iterable)) {
                return false;
            }
            java.util.Iterator<?> iterator = iterable.iterator();
            if (!iterator.hasNext()) {
                return false;
            }
            Object value = iterator.next();
            if (!(value instanceof ScholardexSourceLink saved)) {
                return false;
            }
            return "link-1".equals(saved.getId())
                    && "batch-2".equals(saved.getSourceBatchId())
                    && "SCOPUS".equals(saved.getSource());
        }));
    }

    @Test
    void batchUpsertDoesNotFallbackLookupWhenDisabled() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR,
                        "SCOPUS",
                        "author-1",
                        "sauth-1",
                        "LINKED",
                        "batch",
                        null,
                        "b-1",
                        "c-1",
                        false
                )),
                Map.of(),
                false
        );

        assertEquals(1, result.acceptedCount());
        verify(sourceLinkRepository, never()).findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                any(), anyString(), anyString()
        );
        verify(sourceLinkRepository).saveAll(any());
    }

    @Test
    void batchUpsertReplacesSyntheticPlaceholderWithPersistedRowWhenFallbackIsEnabled() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);

        ScholardexSourceLink placeholder = new ScholardexSourceLink();
        placeholder.setEntityType(ScholardexEntityType.AUTHOR);
        placeholder.setSource("SCOPUS_JSON_UPLOAD");
        placeholder.setSourceRecordId("14027901400");
        placeholder.setCanonicalEntityId("sauth_placeholder");
        placeholder.setLinkState("LINKED");

        ScholardexSourceLink persisted = new ScholardexSourceLink();
        persisted.setId("persisted-link-1");
        persisted.setEntityType(ScholardexEntityType.AUTHOR);
        persisted.setSource("SCOPUS_JSON_UPLOAD");
        persisted.setSourceRecordId("14027901400");
        persisted.setCanonicalEntityId("sauth_existing");
        persisted.setLinkState("LINKED");

        ScholardexSourceLinkService.SourceLinkKey key =
                ScholardexSourceLinkService.SourceLinkKey.of(
                        ScholardexEntityType.AUTHOR,
                        "SCOPUS_JSON_UPLOAD",
                        "14027901400"
                );

        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "14027901400"
        )).thenReturn(Optional.of(persisted));

        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR,
                        "SCOPUS_JSON_UPLOAD",
                        "14027901400",
                        "sauth_existing",
                        "LINKED",
                        "scopus-author-bridge",
                        "event-2",
                        "batch-3",
                        "corr-3",
                        true
                )),
                Map.of(key, placeholder),
                true
        );

        assertEquals(1, result.acceptedCount());
        verify(sourceLinkRepository).saveAll(argThat(savedLinks -> {
            if (!(savedLinks instanceof Iterable<?> iterable)) {
                return false;
            }
            java.util.Iterator<?> iterator = iterable.iterator();
            if (!iterator.hasNext()) {
                return false;
            }
            Object value = iterator.next();
            if (!(value instanceof ScholardexSourceLink saved)) {
                return false;
            }
            return "persisted-link-1".equals(saved.getId())
                    && "sauth_existing".equals(saved.getCanonicalEntityId())
                    && "batch-3".equals(saved.getSourceBatchId())
                    && "SCOPUS".equals(saved.getSource());
        }));
    }

    // =========================================================================
    // applyAssembly — VoidMethodCall survivors (L490-503) and L493 no-coverage
    // =========================================================================

    @Test
    void applyAssemblySetsAllFieldsOnNewLink() {
        // Kills L490(entityType), L492(sourceRecordId), L496(linkReason), L497(sourceEventId),
        // L499(sourceCorrelationId), L501(linkedAt), L503(updatedAt)
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "aa-full")).thenReturn(Optional.empty());

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "aa-full", "sauth_aa",
                "reason-full", "evt-full", "b-full", "c-full", false
        );

        assertTrue(result.accepted());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        ScholardexSourceLink saved = captor.getValue();
        assertEquals(ScholardexEntityType.AUTHOR, saved.getEntityType());
        assertEquals("SCOPUS", saved.getSource());
        assertEquals("aa-full", saved.getSourceRecordId());
        assertEquals("sauth_aa", saved.getCanonicalEntityId());
        assertEquals("LINKED", saved.getLinkState());
        assertEquals("reason-full", saved.getLinkReason());
        assertEquals("evt-full", saved.getSourceEventId());
        assertEquals("b-full", saved.getSourceBatchId());
        assertEquals("c-full", saved.getSourceCorrelationId());
        assertNotNull(saved.getLinkedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void applyAssemblyNullsCanonicalEntityIdWhenLinkStateIsConflict() {
        // L493: STATE_LINKED || STATE_UNMATCHED → if neither (e.g. CONFLICT), canonicalEntityId = null
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-conflict")).thenReturn(Optional.empty());

        service.markConflict(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-conflict",
                "conflict-reason", "evt", "b1", "c1", false
        );

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertNull(captor.getValue().getCanonicalEntityId());
        assertEquals("CONFLICT", captor.getValue().getLinkState());
    }

    @Test
    void applyAssemblySetsCanonicalEntityIdWhenLinkStateIsUnmatched() {
        // L493 NO_COVERAGE: STATE_UNMATCHED path — canonicalEntityId should be set
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "aff-unmatched")).thenReturn(Optional.empty());

        service.markUnmatched(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "aff-unmatched",
                "saff_fallback", "unmatched-reason", "evt", "b1", "c1", false
        );

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("saff_fallback", captor.getValue().getCanonicalEntityId());
        assertEquals("UNMATCHED", captor.getValue().getLinkState());
    }

    @Test
    void applyAssemblyPreservesLinkedAtOnExistingLink() {
        // L500: removed conditional → setLinkedAt always overwrites existing value
        Instant originalLinkedAt = Instant.parse("2019-06-01T00:00:00Z");
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-linked");
        existing.setCanonicalEntityId("sauth_existing");
        existing.setLinkState("LINKED");
        existing.setLinkedAt(originalLinkedAt);
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-linked")).thenReturn(Optional.of(existing));

        service.link(ScholardexEntityType.AUTHOR, "SCOPUS", "auth-linked", "sauth_existing",
                "bridge", "evt", "b1", "c1", false);

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals(originalLinkedAt, captor.getValue().getLinkedAt());
    }

    // =========================================================================
    // openRelinkConflict — VoidMethodCall survivors (L574-591)
    // =========================================================================

    @Test
    void openRelinkConflictSetsAllFieldsOnSavedConflict() {
        // Kills L574-591 VoidMethodCall survivors — all identity conflict field setters
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("WOS");
        existing.setSourceRecordId("pub-relink");
        existing.setCanonicalEntityId("spub_old");
        existing.setLinkState("LINKED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "WOS", "pub-relink")).thenReturn(Optional.of(existing));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("pub-relink"),
                eq("SOURCE_LINK_RELINK_REJECTED"), eq("OPEN")
        )).thenReturn(Optional.empty());

        service.link(ScholardexEntityType.PUBLICATION, "WOS", "pub-relink", "spub_new",
                "relink-reason", "evt-relink", "b-relink", "c-relink", true);

        ArgumentCaptor<ScholardexIdentityConflict> captor =
                ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(captor.capture());
        ScholardexIdentityConflict conflict = captor.getValue();
        assertEquals(ScholardexEntityType.PUBLICATION, conflict.getEntityType());
        assertEquals("WOS", conflict.getIncomingSource());
        assertEquals("pub-relink", conflict.getIncomingSourceRecordId());
        assertEquals("SOURCE_LINK_RELINK_REJECTED", conflict.getReasonCode());
        assertEquals("OPEN", conflict.getStatus());
        assertFalse(conflict.getCandidateCanonicalIds().isEmpty());
        assertTrue(conflict.getCandidateCanonicalIds().contains("spub_old"));
        assertTrue(conflict.getCandidateCanonicalIds().contains("spub_new"));
        assertEquals("evt-relink", conflict.getSourceEventId());
        assertEquals("b-relink", conflict.getSourceBatchId());
        assertEquals("c-relink", conflict.getSourceCorrelationId());
        assertNotNull(conflict.getDetectedAt());
    }

    // =========================================================================
    // isTransitionAllowed — L548 replaced boolean return with true
    // =========================================================================

    @Test
    void isTransitionAllowedReturnsFalseForConflictNextWhenCurrentIsSkippedWithReplay() {
        // L548: "replaced boolean return with true" makes all SKIPPED+replay transitions allowed
        // Normal: SKIPPED+replay only allows UNMATCHED or LINKED, not CONFLICT → rejects
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-skip");
        existing.setLinkState("SKIPPED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-skip")).thenReturn(Optional.of(existing));

        // markConflict with explicitReplayAttempt=true: SKIPPED → CONFLICT should be REJECTED
        ScholardexSourceLinkService.SourceLinkWriteResult result = service.markConflict(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-skip",
                "reason", "evt", "b1", "c1", true
        );

        assertFalse(result.accepted());
        verify(sourceLinkRepository, times(0)).save(any());
    }

    // =========================================================================
    // mergeNormalizedLink — conditional guards (L512-532)
    // =========================================================================

    @Test
    void mergeNormalizedLinkCopiesLinkedAtFromLegacyWhenTargetHasNone() {
        // L512: removed conditional → always overwrites even when non-null
        // L513 void call: setLinkedAt not called → linkedAt remains null
        Instant legacyLinkedAt = Instant.parse("2020-03-15T00:00:00Z");

        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-la");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60001111");
        legacy.setCanonicalEntityId("saff_merge1");
        legacy.setLinkState("LINKED");
        legacy.setLinkedAt(legacyLinkedAt);

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-la");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60001111");
        target.setCanonicalEntityId("saff_merge1");
        target.setLinkState("LINKED");
        // target.linkedAt is null → should get legacy's value

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60001111"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals(legacyLinkedAt, captor.getValue().getLinkedAt());
    }

    @Test
    void mergeNormalizedLinkDoesNotOverwriteExistingLinkedAt() {
        // L512: "if (target.getLinkedAt() == null)" — when removed, existing linkedAt gets overwritten
        Instant targetLinkedAt = Instant.parse("2021-05-01T00:00:00Z");
        Instant legacyLinkedAt = Instant.parse("2020-01-01T00:00:00Z");

        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-la2");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60002222");
        legacy.setCanonicalEntityId("saff_merge2");
        legacy.setLinkState("LINKED");
        legacy.setLinkedAt(legacyLinkedAt);

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-la2");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60002222");
        target.setCanonicalEntityId("saff_merge2");
        target.setLinkState("LINKED");
        target.setLinkedAt(targetLinkedAt);  // already set

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60002222"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals(targetLinkedAt, captor.getValue().getLinkedAt());
    }

    @Test
    void mergeNormalizedLinkSetsCanonicalEntityIdFromDesiredCanonicalWhenTargetLinkedAndMissing() {
        // L515: STATE_LINKED check removed → even LINKED targets with null canonicalId don't get it set
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-can");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60003333");
        legacy.setCanonicalEntityId("saff_desired");
        legacy.setLinkState("LINKED");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-can");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60003333");
        target.setCanonicalEntityId(null);  // missing — should be filled from desiredCanonical
        target.setLinkState("LINKED");

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60003333"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("saff_desired", captor.getValue().getCanonicalEntityId());
    }

    @Test
    void mergeNormalizedLinkDoesNotSetCanonicalEntityIdWhenTargetStateIsConflict() {
        // L515: removed conditional kills CONFLICT case — canonical would be set incorrectly
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-nocan");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60004444");
        legacy.setCanonicalEntityId("saff_desired2");
        legacy.setLinkState("CONFLICT");  // normalizes to CONFLICT

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-nocan");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60004444");
        target.setCanonicalEntityId(null);
        target.setLinkState("CONFLICT");  // neither LINKED nor UNMATCHED

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60004444"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertNull(captor.getValue().getCanonicalEntityId());
    }

    @Test
    void mergeNormalizedLinkCopiesLinkReasonFromLegacyWhenTargetHasNone() {
        // L523: removed conditional → linkReason not copied even when target has none
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-lr");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60005555");
        legacy.setCanonicalEntityId("saff_lr");
        legacy.setLinkState("LINKED");
        legacy.setLinkReason("legacy-reason");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-lr");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60005555");
        target.setCanonicalEntityId("saff_lr");
        target.setLinkState("LINKED");
        // target linkReason is null → should get legacy's value

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60005555"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("legacy-reason", captor.getValue().getLinkReason());
    }

    @Test
    void mergeNormalizedLinkDoesNotOverwriteExistingLinkReason() {
        // L523: both guards removed → existing linkReason would be overwritten with legacy's value
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-lr2");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60006666");
        legacy.setCanonicalEntityId("saff_lr2");
        legacy.setLinkState("LINKED");
        legacy.setLinkReason("legacy-reason-2");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-lr2");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60006666");
        target.setCanonicalEntityId("saff_lr2");
        target.setLinkState("LINKED");
        target.setLinkReason("existing-reason-2");  // already set

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60006666"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("existing-reason-2", captor.getValue().getLinkReason());
    }

    // =========================================================================
    // batchUpsertWithState — L302 LINKED + null canonicalId rejection
    // =========================================================================

    @Test
    void batchUpsertWithStateRejectsLinkedCommandWithNullCanonicalEntityId() {
        // L302: removed conditional → LINKED command with null canonical passes through → NPE or wrong state
        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "auth-no-canonical",
                        null,  // null canonicalEntityId with LINKED state
                        "LINKED", "bridge", "evt", "b1", "c1", false
                )),
                Map.of()
        );

        assertEquals(0, result.acceptedCount());
        assertEquals(1, result.rejectedCount());
        verify(sourceLinkRepository, times(0)).saveAll(any());
    }

    @Test
    void batchUpsertWithStateReturnsEmptyWhenCommandsIsNull() {
        // L277 isEmpty check removed: null commands → NullPointerException → mutation killed
        ScholardexSourceLinkService.BatchWriteResult result =
                service.batchUpsertWithState(null, Map.of(), false);
        assertEquals(0, result.acceptedCount());
        verify(sourceLinkRepository, times(0)).saveAll(any());
    }

    @Test
    void batchUpsertWithStateReturnsEmptyWhenCommandsIsEmpty() {
        // L277 null check removed: empty list reaches loop (no NPE) but no items saved — functionally same
        // However: if isEmpty() check is removed, batchUpsertWithState's early return is bypassed
        // The loop doesn't execute → still 0 saved, but verify still distinguishes by side effects
        ScholardexSourceLinkService.BatchWriteResult result =
                service.batchUpsertWithState(List.of(), Map.of(), false);
        assertEquals(0, result.acceptedCount());
        verify(sourceLinkRepository, times(0)).saveAll(any());
    }

    // =========================================================================
    // upsertWithState — L225/L226 entityType null guard
    // =========================================================================

    @Test
    void upsertWithStateRejectsWhenEntityTypeIsNull() {
        // L225: entityType == null guard — if removed, NPE when calling entityType.name()
        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                null, "SCOPUS", "rec-null-et", "sauth_x",
                "bridge", null, null, null, false
        );
        assertFalse(result.accepted());
        verify(sourceLinkRepository, times(0)).save(any());
    }

    @Test
    void upsertWithStateRejectsWhenSourceRecordIdIsBlank() {
        // L225: normalizedRecordId == null guard
        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "  ", "sauth_x",
                "bridge", null, null, null, false
        );
        assertFalse(result.accepted());
        verify(sourceLinkRepository, times(0)).save(any());
    }

    // =========================================================================
    // findByCanonical — L77-80 (uncovered)
    // =========================================================================

    @Test
    void findByCanonicalReturnsDelegatedListWhenEntityTypeAndIdAreValid() {
        // Covers L77-80 no-coverage mutations
        ScholardexSourceLink link = new ScholardexSourceLink();
        when(sourceLinkRepository.findByEntityTypeAndCanonicalEntityId(
                ScholardexEntityType.AUTHOR, "sauth_1")).thenReturn(List.of(link));

        List<ScholardexSourceLink> result = service.findByCanonical(
                ScholardexEntityType.AUTHOR, "sauth_1");

        assertEquals(1, result.size());
        verify(sourceLinkRepository).findByEntityTypeAndCanonicalEntityId(
                ScholardexEntityType.AUTHOR, "sauth_1");
    }

    @Test
    void findByCanonicalReturnsEmptyListWhenEntityTypeIsNull() {
        // L77: entityType == null guard — kills removed-conditional mutation
        List<ScholardexSourceLink> result = service.findByCanonical(null, "sauth_1");
        assertTrue(result.isEmpty());
        verify(sourceLinkRepository, times(0)).findByEntityTypeAndCanonicalEntityId(any(), anyString());
    }

    @Test
    void findByCanonicalReturnsEmptyListWhenCanonicalEntityIdIsBlank() {
        // L77: normalize(canonicalEntityId) == null guard
        List<ScholardexSourceLink> result = service.findByCanonical(ScholardexEntityType.AUTHOR, "  ");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // findByEntityTypeAndSourceRecordId — L83-87 (uncovered)
    // =========================================================================

    @Test
    void findByEntityTypeAndSourceRecordIdReturnsDelegatedListWhenValid() {
        // Covers L83-87 no-coverage mutations
        ScholardexSourceLink link = new ScholardexSourceLink();
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION, "pub-1")).thenReturn(List.of(link));

        List<ScholardexSourceLink> result = service.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION, "pub-1");

        assertEquals(1, result.size());
    }

    @Test
    void findByEntityTypeAndSourceRecordIdReturnsEmptyWhenEntityTypeIsNull() {
        // L84: entityType == null guard
        List<ScholardexSourceLink> result =
                service.findByEntityTypeAndSourceRecordId(null, "pub-1");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // findByEntityTypeAndSourceRecordIds — L90-104 (uncovered)
    // =========================================================================

    @Test
    void findByEntityTypeAndSourceRecordIdsReturnsDelegatedListWhenValid() {
        // Covers L90-104 no-coverage mutations
        ScholardexSourceLink link = new ScholardexSourceLink();
        when(sourceLinkRepository.findByEntityTypeAndSourceRecordIdIn(
                eq(ScholardexEntityType.AFFILIATION), any()))
                .thenReturn(List.of(link));

        List<ScholardexSourceLink> result = service.findByEntityTypeAndSourceRecordIds(
                ScholardexEntityType.AFFILIATION, List.of("aff-1", "aff-2"));

        assertEquals(1, result.size());
    }

    @Test
    void findByEntityTypeAndSourceRecordIdsReturnsEmptyWhenNullInput() {
        // L91: entityType == null OR sourceRecordIds == null
        List<ScholardexSourceLink> result =
                service.findByEntityTypeAndSourceRecordIds(null, List.of("x"));
        assertTrue(result.isEmpty());

        result = service.findByEntityTypeAndSourceRecordIds(ScholardexEntityType.AUTHOR, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void findByEntityTypeAndSourceRecordIdsReturnsEmptyWhenAllIdsAreBlank() {
        // L101: normalized set is empty after filtering → returns empty without querying
        List<ScholardexSourceLink> result = service.findByEntityTypeAndSourceRecordIds(
                ScholardexEntityType.AUTHOR, List.of("  ", ""));
        assertTrue(result.isEmpty());
        verify(sourceLinkRepository, times(0)).findByEntityTypeAndSourceRecordIdIn(any(), any());
    }

    // =========================================================================
    // reconcileLinks direct-update branch (L414-447, no normalizedExisting)
    // =========================================================================

    @Test
    void reconcileLinksUpdatesSourceWhenNormalizedAndNoMatchingTarget() {
        // sourceChanged=true but no normalizedExisting → direct-update path: link.source → "SCOPUS"
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-norm-src");
        link.setEntityType(ScholardexEntityType.AFFILIATION);
        link.setSource("SCOPUS_JSON_BOOTSTRAP");
        link.setSourceRecordId("60007777");
        link.setCanonicalEntityId("saff_n");
        link.setLinkState("LINKED");
        link.setLinkedAt(Instant.parse("2021-01-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60007777"
        )).thenReturn(Optional.empty());

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("SCOPUS", captor.getValue().getSource());
        assertEquals("link-norm-src", captor.getValue().getId());
    }

    @Test
    void reconcileLinksForcesSKippedWhenLinkStateIsUnrecognized() {
        // desiredState=null (unknown state → null) → forced to SKIPPED, changed=true
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-bad-state");
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource("SCOPUS");
        link.setSourceRecordId("auth-bad");
        link.setLinkState("GARBAGE_STATE");
        link.setLinkedAt(Instant.parse("2021-06-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("SKIPPED", captor.getValue().getLinkState());
    }

    @Test
    void reconcileLinksForceUnmatchedWhenLinkedWithNullCanonical() {
        // STATE_LINKED + desiredCanonical==null → setLinkState(UNMATCHED), setLinkReason(...)
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-no-can");
        link.setEntityType(ScholardexEntityType.PUBLICATION);
        link.setSource("SCOPUS");
        link.setSourceRecordId("pub-no-can");
        link.setLinkState("LINKED");
        link.setCanonicalEntityId(null);
        link.setLinkedAt(Instant.parse("2022-03-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("UNMATCHED", captor.getValue().getLinkState());
        assertEquals("reconcile-missing-linked-canonical", captor.getValue().getLinkReason());
    }

    @Test
    void reconcileLinksNullsCanonicalForConflictWithCanonical() {
        // !STATE_LINKED && !STATE_UNMATCHED && desiredCanonical!=null → canonicalEntityId=null
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-conflict-can");
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource("SCOPUS");
        link.setSourceRecordId("auth-conflict-can");
        link.setLinkState("CONFLICT");
        link.setCanonicalEntityId("sauth_old");
        link.setLinkedAt(Instant.parse("2021-09-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertNull(captor.getValue().getCanonicalEntityId());
    }

    @Test
    void reconcileLinksSetsLinkedAtWhenMissing() {
        // link.getLinkedAt()==null → set now, changed=true
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-no-lat");
        link.setEntityType(ScholardexEntityType.AFFILIATION);
        link.setSource("SCOPUS");
        link.setSourceRecordId("aff-no-lat");
        link.setLinkState("LINKED");
        link.setCanonicalEntityId("saff_x");
        // linkedAt intentionally null

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertNotNull(captor.getValue().getLinkedAt());
    }

    @Test
    void reconcileLinksSkipsWhenNothingChanged() {
        // All fields already normalized and complete → no changes → skipped++
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-complete");
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource("SCOPUS");
        link.setSourceRecordId("auth-complete");
        link.setLinkState("LINKED");
        link.setCanonicalEntityId("sauth_complete");
        link.setLinkedAt(Instant.parse("2020-01-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(0, result.updated());
        assertEquals(1, result.skipped());
        verify(sourceLinkRepository, never()).save(any());
    }

    @Test
    void reconcileLinksUpdatesRecordIdWhenNormalizedRecordIdDiffers() {
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-record-normalize");
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource("SCOPUS");
        link.setSourceRecordId(" author-123 ");
        link.setLinkState("LINKED");
        link.setCanonicalEntityId("sauth_123");
        link.setLinkedAt(Instant.parse("2020-01-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(1, result.updated());
        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("author-123", captor.getValue().getSourceRecordId());
    }

    @Test
    void reconcileLinksCountsErrorWhenSaveThrows() {
        ScholardexSourceLink link = new ScholardexSourceLink();
        link.setId("link-save-error");
        link.setEntityType(ScholardexEntityType.AUTHOR);
        link.setSource("SCOPUS");
        link.setSourceRecordId(" author-500 ");
        link.setLinkState("LINKED");
        link.setCanonicalEntityId("sauth_500");
        link.setLinkedAt(Instant.parse("2020-01-01T00:00:00Z"));

        when(sourceLinkRepository.findAll()).thenReturn(List.of(link));
        when(sourceLinkRepository.save(any(ScholardexSourceLink.class)))
                .thenThrow(new RuntimeException("db-down"));

        ScholardexSourceLinkService.ImportRepairSummary result = service.reconcileLinks();

        assertEquals(0, result.updated());
        assertEquals(0, result.skipped());
        assertEquals(1, result.errors());
    }

    // =========================================================================
    // isTransitionAllowed — CONFLICT and LINKED current-state cases (L550-554)
    // =========================================================================

    @Test
    void isTransitionAllowedAllowsConflictToLinkedWithExplicitReplay() {
        // L551: explicitReplayAttempt && STATE_LINKED.equals(next) → true → accepted
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-conflict-replay");
        existing.setLinkState("CONFLICT");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-conflict-replay"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-conflict-replay",
                "sauth_new", "conflict-resolved", "evt", "b1", "c1", true
        );

        assertTrue(result.accepted());
        verify(sourceLinkRepository).save(any(ScholardexSourceLink.class));
    }

    @Test
    void isTransitionAllowedRejectsConflictToLinkedWithoutReplay() {
        // L551: false && STATE_LINKED.equals(next) → false → rejected
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-conflict-noreplay");
        existing.setLinkState("CONFLICT");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-conflict-noreplay"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-conflict-noreplay",
                "sauth_new", "relink", "evt", "b1", "c1", false
        );

        assertFalse(result.accepted());
        verify(sourceLinkRepository, never()).save(any());
    }

    @Test
    void isTransitionAllowedRejectsLinkedToSkipped() {
        // L553: STATE_LINKED current → STATE_LINKED.equals("SKIPPED") = false → rejected
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("pub-linked-skip");
        existing.setCanonicalEntityId("spub_x");
        existing.setLinkState("LINKED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-linked-skip"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.markSkipped(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-linked-skip",
                "skip-reason", "evt", "b1", "c1", false
        );

        assertFalse(result.accepted());
        verify(sourceLinkRepository, never()).save(any());
    }

    @Test
    void isTransitionAllowedAllowsUnmatchedToSkippedWithoutReplay() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("pub-unmatched-skip");
        existing.setCanonicalEntityId("spub_u");
        existing.setLinkState("UNMATCHED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-unmatched-skip"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.markSkipped(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-unmatched-skip",
                "still-no-target", "evt", "b1", "c1", false
        );

        assertTrue(result.accepted());
        verify(sourceLinkRepository).save(any(ScholardexSourceLink.class));
    }

    @Test
    void isTransitionAllowedAllowsUnmatchedToUnmatchedIdempotently() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-unmatched-unmatched");
        existing.setCanonicalEntityId("sauth_same");
        existing.setLinkState("UNMATCHED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-unmatched-unmatched"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.markUnmatched(
                ScholardexEntityType.AUTHOR, "SCOPUS", "auth-unmatched-unmatched",
                "sauth_same", "still-unmatched", "evt", "b1", "c1", false
        );

        assertTrue(result.accepted());
        verify(sourceLinkRepository).save(any(ScholardexSourceLink.class));
    }

    @Test
    void isTransitionAllowedAllowsLinkedToLinkedWithoutReplay() {
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("pub-linked-linked");
        existing.setCanonicalEntityId("spub_same");
        existing.setLinkState("LINKED");
        when(sourceLinkRepository.findFirstByEntityTypeAndSourceAndSourceRecordIdOrderByUpdatedAtDesc(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-linked-linked"
        )).thenReturn(Optional.of(existing));

        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.PUBLICATION, "SCOPUS", "pub-linked-linked",
                "spub_same", "idempotent", "evt", "b1", "c1", false
        );

        assertTrue(result.accepted());
        verify(sourceLinkRepository).save(any(ScholardexSourceLink.class));
    }

    // =========================================================================
    // upsertWithState — L234-236: LINKED + null canonicalEntityId rejection
    // =========================================================================

    @Test
    void upsertWithStateRejectsLinkedWithNullCanonicalId() {
        // L234: STATE_LINKED && normalizedCanonicalId==null → rejected with "linked-requires-canonical-id"
        ScholardexSourceLinkService.SourceLinkWriteResult result = service.link(
                ScholardexEntityType.AUTHOR, "SCOPUS", "rec-no-can",
                null, "bridge", null, null, null, false
        );

        assertFalse(result.accepted());
        assertEquals("linked-requires-canonical-id", result.reason());
        verify(sourceLinkRepository, never()).save(any());
    }

    // =========================================================================
    // batchUpsertWithState — L292-300: invalid-source-link-key (null entityType in command)
    // =========================================================================

    @Test
    void batchUpsertWithStateRejectsCommandWhenEntityTypeIsNull() {
        // L292: command.entityType()==null → invalid-source-link-key, results in rejected item
        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        null, "SCOPUS", "rec-null-et2",
                        "sauth_x", "LINKED", "bridge", "evt", "b1", "c1", false
                )),
                Map.of()
        );

        assertEquals(0, result.acceptedCount());
        assertEquals(1, result.rejectedCount());
        verify(sourceLinkRepository, times(0)).saveAll(any());
    }

    @Test
    void batchUpsertWithStateRejectsTransitionWhenIsTransitionAllowedReturnsFalse() {
        // L322: isTransitionAllowed("SKIPPED","CONFLICT",false)=false → rejected item
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.AUTHOR);
        existing.setSource("SCOPUS");
        existing.setSourceRecordId("auth-batch-skip");
        existing.setLinkState("SKIPPED");

        ScholardexSourceLinkService.SourceLinkKey key =
                ScholardexSourceLinkService.SourceLinkKey.of(ScholardexEntityType.AUTHOR, "SCOPUS", "auth-batch-skip");

        ScholardexSourceLinkService.BatchWriteResult result = service.batchUpsertWithState(
                List.of(new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                        ScholardexEntityType.AUTHOR, "SCOPUS", "auth-batch-skip",
                        null, "CONFLICT", "reason", "evt", "b1", "c1", false
                )),
                Map.of(key, existing)
        );

        assertEquals(0, result.acceptedCount());
        assertEquals(1, result.rejectedCount());
        verify(sourceLinkRepository, times(0)).saveAll(any());
    }

    // =========================================================================
    // normalizeState — unknown state returns null (L631)
    // =========================================================================

    @Test
    void upsertWithStateRejectsUnknownStateString() {
        // normalizeState("NOT_A_VALID_STATE") → null → rejected at L225 (normalizedState==null)
        ScholardexSourceLinkService.SourceLinkWriteResult result = service.upsertWithState(
                ScholardexEntityType.AUTHOR, "SCOPUS", "rec-bad-state",
                "sauth_x", "NOT_A_VALID_STATE",
                "bridge", null, null, null, false
        );

        assertFalse(result.accepted());
        verify(sourceLinkRepository, never()).save(any());
    }

    // =========================================================================
    // findPaged — normalizePage / normalizeSize / entity type routing (L107-133)
    // =========================================================================

    @Test
    void findPagedUsesAllEntitiesQueryWhenEntityTypeIsNull() {
        // L123: parsedEntityType==null → findAllBySource... (no entity-type arg)
        when(sourceLinkRepository.findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        Page<ScholardexSourceLink> page = service.findPaged(0, 10, null, null, null, null, null);

        assertNotNull(page);
        verify(sourceLinkRepository).findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class));
        verify(sourceLinkRepository, never())
                .findAllByEntityTypeAndSourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                        any(), any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class));
    }

    @Test
    void findPagedUsesEntityTypeQueryWhenEntityTypeIsProvided() {
        // L129-132: parsedEntityType!=null → findAllByEntityTypeAndSource...
        when(sourceLinkRepository.findAllByEntityTypeAndSourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        Page<ScholardexSourceLink> page = service.findPaged(0, 10, "AUTHOR", null, null, null, null);

        assertNotNull(page);
        verify(sourceLinkRepository).findAllByEntityTypeAndSourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                eq(ScholardexEntityType.AUTHOR), any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class));
    }

    @Test
    void findPagedUsesPageZeroWhenPageIsNullOrNegative() {
        // L634-636: normalizePage(null)=0; normalizePage(-1)=0
        when(sourceLinkRepository.findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service.findPaged(null, 10, null, null, null, null, null);
        service.findPaged(-5, 10, null, null, null, null, null);

        verify(sourceLinkRepository, times(2))
                .findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                        any(), any(), any(Instant.class), any(Instant.class),
                        argThat(p -> p.getPageNumber() == 0));
    }

    @Test
    void findPagedUsesDefaultSizeWhenSizeIsNullOrZero() {
        // L641-643: normalizeSize(null)=20; normalizeSize(0)=20
        when(sourceLinkRepository.findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service.findPaged(0, null, null, null, null, null, null);
        service.findPaged(0, 0, null, null, null, null, null);

        verify(sourceLinkRepository, times(2))
                .findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                        any(), any(), any(Instant.class), any(Instant.class),
                        argThat(p -> p.getPageSize() == 20));
    }

    @Test
    void findPagedCapsPageSizeAt200() {
        // L645: Math.min(size, 200) caps at 200 for size>200
        when(sourceLinkRepository.findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        service.findPaged(0, 500, null, null, null, null, null);

        verify(sourceLinkRepository).findAllBySourceContainingIgnoreCaseAndLinkStateContainingIgnoreCaseAndUpdatedAtBetween(
                any(), any(), any(Instant.class), any(Instant.class),
                argThat(p -> p.getPageSize() == 200));
    }

    // =========================================================================
    // mergeNormalizedLink — lineage field propagation (L526-534)
    // =========================================================================

    @Test
    void mergeNormalizedLinkCopiesSourceEventIdFromLegacyWhenTargetHasNone() {
        // L526-527: target.sourceEventId==null → copied from legacy
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-evt");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60008888");
        legacy.setCanonicalEntityId("saff_evt");
        legacy.setLinkState("LINKED");
        legacy.setSourceEventId("legacy-event-1");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-evt");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60008888");
        target.setCanonicalEntityId("saff_evt");
        target.setLinkState("LINKED");
        // target sourceEventId is null

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60008888"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("legacy-event-1", captor.getValue().getSourceEventId());
    }

    @Test
    void mergeNormalizedLinkCopiesSourceBatchIdFromLegacyWhenTargetHasNone() {
        // L529-530: target.sourceBatchId==null → copied from legacy
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-bat");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60009999");
        legacy.setCanonicalEntityId("saff_bat");
        legacy.setLinkState("LINKED");
        legacy.setSourceBatchId("legacy-batch-1");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-bat");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60009999");
        target.setCanonicalEntityId("saff_bat");
        target.setLinkState("LINKED");
        // target sourceBatchId is null

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60009999"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("legacy-batch-1", captor.getValue().getSourceBatchId());
    }

    @Test
    void mergeNormalizedLinkCopiesSourceCorrelationIdFromLegacyWhenTargetHasNone() {
        // L532-533: target.sourceCorrelationId==null → copied from legacy
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-cor");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60011111");
        legacy.setCanonicalEntityId("saff_cor");
        legacy.setLinkState("LINKED");
        legacy.setSourceCorrelationId("legacy-corr-1");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-cor");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60011111");
        target.setCanonicalEntityId("saff_cor");
        target.setLinkState("LINKED");
        // target sourceCorrelationId is null

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60011111"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertEquals("legacy-corr-1", captor.getValue().getSourceCorrelationId());
    }

    @Test
    void mergeNormalizedLinkSetsLinkStateFromDesiredWhenTargetLinkStateIsNull() {
        // L520-521: target.getLinkState()==null && desiredState!=null → set from desired
        ScholardexSourceLink legacy = new ScholardexSourceLink();
        legacy.setId("legacy-ls");
        legacy.setEntityType(ScholardexEntityType.AFFILIATION);
        legacy.setSource("SCOPUS_JSON_BOOTSTRAP");
        legacy.setSourceRecordId("60012222");
        legacy.setCanonicalEntityId("saff_ls");
        legacy.setLinkState("LINKED");

        ScholardexSourceLink target = new ScholardexSourceLink();
        target.setId("target-ls");
        target.setEntityType(ScholardexEntityType.AFFILIATION);
        target.setSource("SCOPUS");
        target.setSourceRecordId("60012222");
        target.setCanonicalEntityId("saff_ls");
        target.setLinkState(null);  // null → should be set to LINKED from legacy/desiredState

        when(sourceLinkRepository.findAll()).thenReturn(List.of(legacy));
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.AFFILIATION, "SCOPUS", "60012222"
        )).thenReturn(Optional.of(target));

        service.reconcileLinks();

        ArgumentCaptor<ScholardexSourceLink> captor = ArgumentCaptor.forClass(ScholardexSourceLink.class);
        verify(sourceLinkRepository).save(captor.capture());
        assertNotNull(captor.getValue().getLinkState());
    }
}
