package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexForumDetailServiceTest {

    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private WosRankingDetailsReadService wosRankingDetailsReadService;
    @Mock
    private WosForumResolutionService wosForumResolutionService;

    private ScholardexForumDetailService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexForumDetailService(scholardexProjectionReadService, wosRankingDetailsReadService, wosForumResolutionService);
    }

    @Test
    void returnsEmptyWhenForumMissing() {
        when(scholardexProjectionReadService.findForumById("missing")).thenReturn(Optional.empty());

        assertTrue(service.findDetail("missing").isEmpty());
    }

    @Test
    void journalLoadsWosDetails() {
        Forum forum = forum("j1", "Journal");
        WoSRanking ranking = new WoSRanking();
        ranking.setId("j1");
        when(scholardexProjectionReadService.findForumById("j1")).thenReturn(Optional.of(forum));
        when(wosForumResolutionService.resolveJournalId(forum)).thenReturn("wos-j1");
        when(wosRankingDetailsReadService.findByJournalId("wos-j1")).thenReturn(Optional.of(ranking));

        ScholardexForumDetailViewModel detail = service.findDetail("j1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.JOURNAL, detail.forumType());
        assertTrue(detail.wosIndexed());
        assertEquals(ranking, detail.wosRanking());
    }

    @Test
    void conferenceUsesCorePlaceholderAndSkipsWosLookup() {
        Forum forum = forum("c1", "Conference Proceeding");
        when(scholardexProjectionReadService.findForumById("c1")).thenReturn(Optional.of(forum));

        ScholardexForumDetailViewModel detail = service.findDetail("c1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.CONFERENCE, detail.forumType());
        assertTrue(detail.showCorePlaceholder());
        assertFalse(detail.wosIndexed());
        verify(wosRankingDetailsReadService, never()).findByJournalId("c1");
    }

    @Test
    void bookSeriesUsesBookPlaceholderAndSkipsWosLookup() {
        Forum forum = forum("b1", "Book Series");
        when(scholardexProjectionReadService.findForumById("b1")).thenReturn(Optional.of(forum));

        ScholardexForumDetailViewModel detail = service.findDetail("b1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.BOOK, detail.forumType());
        assertTrue(detail.showBookPlaceholder());
        assertFalse(detail.wosIndexed());
        verify(wosRankingDetailsReadService, never()).findByJournalId("b1");
    }

    @Test
    void unknownAggregationTypeUsesGenericPlaceholder() {
        Forum forum = forum("o1", "Series");
        when(scholardexProjectionReadService.findForumById("o1")).thenReturn(Optional.of(forum));

        ScholardexForumDetailViewModel detail = service.findDetail("o1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.OTHER, detail.forumType());
        assertTrue(detail.showGenericPlaceholder());
        verify(wosRankingDetailsReadService, never()).findByJournalId("o1");
    }

    private Forum forum(String id, String aggregationType) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName("Forum " + id);
        forum.setAggregationType(aggregationType);
        return forum;
    }
}
