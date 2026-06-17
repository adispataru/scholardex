package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictRecorderTest {

    @Mock private ScholardexIdentityConflictRepository identityConflictRepository;
    @Mock private ScholardexSourceLinkService sourceLinkService;

    @Test
    void openConflict_createsAndPopulatesWhenNoneOpen() {
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), eq("OPEN"))).thenReturn(Optional.empty());

        ConflictRecorder recorder = new ConflictRecorder(identityConflictRepository, sourceLinkService);
        recorder.openConflict(ScholardexEntityType.FORUM, "SCOPUS", "s1", "AMBIGUOUS_ISSN_MATCH",
                List.of("sforum_a", "sforum_b"), "b1", "c1");

        ArgumentCaptor<ScholardexIdentityConflict> captor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(captor.capture());
        ScholardexIdentityConflict saved = captor.getValue();
        assertEquals(ScholardexEntityType.FORUM, saved.getEntityType());
        assertEquals("SCOPUS", saved.getIncomingSource());
        assertEquals("s1", saved.getIncomingSourceRecordId());
        assertEquals("AMBIGUOUS_ISSN_MATCH", saved.getReasonCode());
        assertEquals("OPEN", saved.getStatus());
        assertEquals(List.of("sforum_a", "sforum_b"), saved.getCandidateCanonicalIds());
        assertEquals("b1", saved.getSourceBatchId());
        assertNotNull(saved.getDetectedAt());
    }

    @Test
    void openConflict_reusesExistingOpenConflict() {
        ScholardexIdentityConflict existing = new ScholardexIdentityConflict();
        existing.setDetectedAt(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        when(identityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                any(), any(), any(), any(), eq("OPEN"))).thenReturn(Optional.of(existing));

        ConflictRecorder recorder = new ConflictRecorder(identityConflictRepository, sourceLinkService);
        recorder.openConflict(ScholardexEntityType.FORUM, "WOS", "w1", "FORUM_CROSS_JOURNAL_ISSN", null, "b2", "c2");

        ArgumentCaptor<ScholardexIdentityConflict> captor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(identityConflictRepository).save(captor.capture());
        assertEquals(existing, captor.getValue()); // same instance refreshed, not a new one
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z"), captor.getValue().getDetectedAt()); // preserved
        assertEquals(List.of(), captor.getValue().getCandidateCanonicalIds()); // null -> empty
    }

    @Test
    void markConflictLink_delegatesToSourceLinkService() {
        ConflictRecorder recorder = new ConflictRecorder(identityConflictRepository, sourceLinkService);
        recorder.markConflictLink(ScholardexEntityType.FORUM, "WOS", "w1", "FORUM_EXTERNAL_ID_ALREADY_LINKED", "b1", "c1");
        verify(sourceLinkService).markConflict(ScholardexEntityType.FORUM, "WOS", "w1",
                "FORUM_EXTERNAL_ID_ALREADY_LINKED", null, "b1", "c1", false);
    }
}
