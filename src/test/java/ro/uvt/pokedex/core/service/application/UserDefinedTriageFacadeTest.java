package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexIdentityConflict;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexIdentityConflictRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedPublicationFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDefinedTriageFacadeTest {

    @Mock
    private UserDefinedPublicationFactRepository userDefinedPublicationFactRepository;
    @Mock
    private UserDefinedForumFactRepository userDefinedForumFactRepository;
    @Mock
    private ScholardexSourceLinkRepository sourceLinkRepository;
    @Mock
    private ScholardexIdentityConflictRepository identityConflictRepository;

    @Test
    void snapshotBuildsUserDefinedCountsAndRecentRows() {
        ScholardexSourceLink sourceLink = new ScholardexSourceLink();
        sourceLink.setSource("USER_DEFINED");
        ScholardexIdentityConflict conflict = new ScholardexIdentityConflict();
        conflict.setIncomingSource("USER_DEFINED");

        when(userDefinedPublicationFactRepository.count()).thenReturn(4L);
        when(userDefinedForumFactRepository.count()).thenReturn(2L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", "LINKED")).thenReturn(3L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", "UNMATCHED")).thenReturn(1L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", "CONFLICT")).thenReturn(2L);
        when(sourceLinkRepository.countBySourceAndLinkState("USER_DEFINED", "SKIPPED")).thenReturn(0L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "OPEN")).thenReturn(5L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "RESOLVED")).thenReturn(1L);
        when(identityConflictRepository.countByIncomingSourceAndStatus("USER_DEFINED", "DISMISSED")).thenReturn(0L);
        when(sourceLinkRepository.findBySourceOrderByUpdatedAtDesc(org.mockito.ArgumentMatchers.eq("USER_DEFINED"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sourceLink)));
        when(identityConflictRepository.findByIncomingSourceOrderByDetectedAtDesc(org.mockito.ArgumentMatchers.eq("USER_DEFINED"), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conflict)));

        UserDefinedTriageFacade facade = new UserDefinedTriageFacade(
                userDefinedPublicationFactRepository,
                userDefinedForumFactRepository,
                sourceLinkRepository,
                identityConflictRepository
        );

        UserDefinedTriageFacade.UserDefinedTriageSnapshot snapshot = facade.snapshot(25, 30);

        assertEquals(4L, snapshot.publicationFactCount());
        assertEquals(2L, snapshot.forumFactCount());
        assertEquals(6L, snapshot.sourceLinks().total());
        assertEquals(6L, snapshot.conflicts().total());
        assertEquals(1, snapshot.recentSourceLinks().size());
        assertEquals(1, snapshot.recentConflicts().size());

        ArgumentCaptor<Pageable> linksPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(sourceLinkRepository).findBySourceOrderByUpdatedAtDesc(org.mockito.ArgumentMatchers.eq("USER_DEFINED"), linksPageCaptor.capture());
        assertEquals(25, linksPageCaptor.getValue().getPageSize());
        ArgumentCaptor<Pageable> conflictsPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(identityConflictRepository).findByIncomingSourceOrderByDetectedAtDesc(org.mockito.ArgumentMatchers.eq("USER_DEFINED"), conflictsPageCaptor.capture());
        assertEquals(30, conflictsPageCaptor.getValue().getPageSize());
    }
}
