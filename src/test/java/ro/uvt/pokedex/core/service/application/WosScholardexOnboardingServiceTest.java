package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.reporting.wos.WosJournalIdentity;
import ro.uvt.pokedex.core.repository.reporting.WosJournalIdentityRepository;
import ro.uvt.pokedex.core.repository.reporting.WosRankingViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WosScholardexOnboardingServiceTest {

    @Mock private WosJournalIdentityRepository wosJournalIdentityRepository;
    @Mock private WosRankingViewRepository wosRankingViewRepository;
    @Mock private ScopusForumFactRepository scopusForumFactRepository;
    @Mock private ScholardexForumFactRepository scholardexForumFactRepository;
    @Mock private ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    @Mock private ScholardexIdentityConflictRepository scholardexIdentityConflictRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;

    @Test
    void runWosOnboardingCreatesCanonicalForumForWosOnlyJournal() {
        WosScholardexOnboardingService service = new WosScholardexOnboardingService(
                wosJournalIdentityRepository,
                wosRankingViewRepository,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                scholardexSourceLinkRepository,
                scholardexIdentityConflictRepository,
                scholardexPublicationFactRepository
        );

        WosJournalIdentity identity = new WosJournalIdentity();
        identity.setId("wos-j-1");
        identity.setTitle("Journal of Testing");
        identity.setPrimaryIssn("1234567X");
        when(wosJournalIdentityRepository.findAll()).thenReturn(List.of(identity));
        when(wosRankingViewRepository.findAll()).thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of());
        when(scholardexSourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.FORUM, "WOS", "wos-j-1")).thenReturn(Optional.empty());
        when(scholardexForumFactRepository.save(any(ScholardexForumFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scholardexSourceLinkRepository.save(any(ScholardexSourceLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ImportProcessingResult result = service.runWosOnboarding("batch-1", "corr-1");

        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScholardexForumFact> forumCaptor = ArgumentCaptor.forClass(ScholardexForumFact.class);
        verify(scholardexForumFactRepository).save(forumCaptor.capture());
        ScholardexForumFact savedForum = forumCaptor.getValue();
        assertTrue(savedForum.getId().startsWith("sforum_"));
        assertEquals(List.of("wos-j-1"), savedForum.getWosForumIds());
        assertEquals("1234-567X", savedForum.getIssn());
    }

    @Test
    void runWosOnboardingQuarantinesPublicationSourceLinkCollision() {
        WosScholardexOnboardingService service = new WosScholardexOnboardingService(
                wosJournalIdentityRepository,
                wosRankingViewRepository,
                scopusForumFactRepository,
                scholardexForumFactRepository,
                scholardexSourceLinkRepository,
                scholardexIdentityConflictRepository,
                scholardexPublicationFactRepository
        );

        ScholardexPublicationFact publication = new ScholardexPublicationFact();
        publication.setId("spub_1");
        publication.setWosId("WOS:123");

        ScholardexSourceLink existing = new ScholardexSourceLink();
        existing.setCanonicalEntityId("spub_other");

        when(wosJournalIdentityRepository.findAll()).thenReturn(List.of());
        when(wosRankingViewRepository.findAll()).thenReturn(List.of());
        when(scopusForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexForumFactRepository.findAll()).thenReturn(List.of());
        when(scholardexPublicationFactRepository.findAll()).thenReturn(List.of(publication));
        when(scholardexSourceLinkRepository.findByEntityTypeAndSourceRecordId(
                ScholardexEntityType.PUBLICATION,
                "WOS:123"
        )).thenReturn(List.of(existing));
        when(scholardexSourceLinkRepository.findByEntityTypeAndSourceAndSourceRecordId(
                ScholardexEntityType.PUBLICATION, "WOS", "WOS:123")).thenReturn(Optional.empty());
        when(scholardexIdentityConflictRepository.findByEntityTypeAndIncomingSourceAndIncomingSourceRecordIdAndReasonCodeAndStatus(
                eq(ScholardexEntityType.PUBLICATION), eq("WOS"), eq("WOS:123"), eq("SOURCE_ID_COLLISION"), eq("OPEN")
        )).thenReturn(Optional.empty());

        service.runWosOnboarding("batch-1", "corr-1");

        ArgumentCaptor<ScholardexIdentityConflict> conflictCaptor = ArgumentCaptor.forClass(ScholardexIdentityConflict.class);
        verify(scholardexIdentityConflictRepository).save(conflictCaptor.capture());
        assertEquals("SOURCE_ID_COLLISION", conflictCaptor.getValue().getReasonCode());
        assertEquals(ScholardexEntityType.PUBLICATION, conflictCaptor.getValue().getEntityType());
    }
}
