package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRankingFacadeTest {

    @Mock
    private ScopusProjectionReadService scopusProjectionReadService;
    @Mock
    private RankingRepository rankingRepository;

    @InjectMocks
    private UserRankingFacade facade;

    @Test
    void nonJournalForumReturnsEmpty() {
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setAggregationType("Conference");
        when(scopusProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum));

        assertTrue(facade.resolveJournalRankingForForum("f1").isEmpty());
    }

    @Test
    void missingGeneratedIdReturnsEmpty() {
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setAggregationType("Journal");
        when(scopusProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum));

        assertTrue(facade.resolveJournalRankingForForum("f1").isEmpty());
    }

    @Test
    void rankingFoundReturnsPresent() {
        Forum forum = new Forum();
        forum.setId("f1");
        forum.setAggregationType("Journal");
        forum.setIssn("1234-5678");
        WoSRanking ranking = new WoSRanking();
        ranking.setId("1234-5678");
        when(scopusProjectionReadService.findForumById("f1")).thenReturn(Optional.of(forum));
        when(rankingRepository.findById("1234-5678")).thenReturn(Optional.of(ranking));

        Optional<WoSRanking> result = facade.resolveJournalRankingForForum("f1");
        assertTrue(result.isPresent());
        assertEquals("1234-5678", result.get().getId());
    }
}
