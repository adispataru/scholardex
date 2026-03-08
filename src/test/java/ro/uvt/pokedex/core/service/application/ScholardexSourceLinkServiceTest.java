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
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
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
    void linkedCanonicalIdIsImmutableAndOpensConflictOnRelinkAttempt() {
        ScholardexSourceLinkService service = new ScholardexSourceLinkService(sourceLinkRepository, identityConflictRepository);
        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setEntityType(ScholardexEntityType.PUBLICATION);
        existing.setSource("WOS");
        existing.setSourceRecordId("WOS:1");
        existing.setCanonicalEntityId("spub_old");
        existing.setLinkState("LINKED");
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
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
        when(sourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
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
}
