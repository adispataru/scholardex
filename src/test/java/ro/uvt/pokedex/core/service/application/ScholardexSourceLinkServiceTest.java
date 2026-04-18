package ro.uvt.pokedex.core.service.application;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexSourceLinkServiceTest {

    @Mock
    private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;

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
        verify(identityConflictRepository).save(any(ScholardexIdentityConflict.class));
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
}
