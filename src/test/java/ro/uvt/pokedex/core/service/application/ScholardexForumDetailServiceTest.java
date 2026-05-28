package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceBookService;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;

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
    @Mock
    private ComputerScienceConferenceScoringService computerScienceConferenceScoringService;
    @Mock
    private ComputerScienceBookService computerScienceBookService;

    private ScholardexForumDetailService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexForumDetailService(scholardexProjectionReadService, wosRankingDetailsReadService, wosForumResolutionService, computerScienceConferenceScoringService, computerScienceBookService);
    }

    @Test
    void returnsEmptyWhenForumMissing() {
        when(scholardexProjectionReadService.findForumById("missing")).thenReturn(Optional.empty());

        assertTrue(service.findDetail("missing").isEmpty());
    }

    @Test
    void journalLoadsWosDetails() {
        ScholardexForumView forum = forum("j1", "Journal");
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
    void conferenceWithoutMatchUsesCorePlaceholder() {
        ScholardexForumView forum = forum("c1", "Conference Proceeding");
        when(scholardexProjectionReadService.findForumById("c1")).thenReturn(Optional.of(forum));
        when(computerScienceConferenceScoringService.matchByForumName(forum.getPublicationName())).thenReturn(Optional.empty());

        ScholardexForumDetailViewModel detail = service.findDetail("c1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.CONFERENCE, detail.forumType());
        assertTrue(detail.showCorePlaceholder());
        assertFalse(detail.wosIndexed());
        verify(wosRankingDetailsReadService, never()).findByJournalId("c1");
    }

    @Test
    void conferenceWithMatchExposesCoreRanking() {
        ScholardexForumView forum = forum("c2", "Conference Proceeding");
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setId("ICSE-International Conference on Software Engineering");
        ranking.setAcronym("ICSE");
        ranking.setName("International Conference on Software Engineering");
        when(scholardexProjectionReadService.findForumById("c2")).thenReturn(Optional.of(forum));
        when(computerScienceConferenceScoringService.matchByForumName(forum.getPublicationName())).thenReturn(Optional.of(ranking));

        ScholardexForumDetailViewModel detail = service.findDetail("c2").orElseThrow();

        assertEquals(ranking, detail.coreRanking());
        assertFalse(detail.showCorePlaceholder());
    }

    @Test
    void bookSeriesWithoutMatchUsesBookPlaceholder() {
        ScholardexForumView forum = forum("b1", "Book Series");
        when(scholardexProjectionReadService.findForumById("b1")).thenReturn(Optional.of(forum));
        when(computerScienceBookService.matchByPublisher(forum.getPublisher())).thenReturn(Optional.empty());

        ScholardexForumDetailViewModel detail = service.findDetail("b1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.BOOK, detail.forumType());
        assertTrue(detail.showBookPlaceholder());
        assertFalse(detail.wosIndexed());
        verify(wosRankingDetailsReadService, never()).findByJournalId("b1");
    }

    @Test
    void bookSeriesWithMatchExposesSenseRanking() {
        ScholardexForumView forum = forum("b2", "Book Series");
        forum.setPublisher("Springer");
        SenseBookRanking ranking = new SenseBookRanking();
        ranking.setId("springer");
        ranking.setName("Springer");
        ranking.setRanking(SenseBookRanking.Rank.A);
        when(scholardexProjectionReadService.findForumById("b2")).thenReturn(Optional.of(forum));
        when(computerScienceBookService.matchByPublisher("Springer")).thenReturn(Optional.of(ranking));

        ScholardexForumDetailViewModel detail = service.findDetail("b2").orElseThrow();

        assertEquals(ranking, detail.senseBookRanking());
        assertFalse(detail.showBookPlaceholder());
    }

    @Test
    void unknownAggregationTypeUsesGenericPlaceholder() {
        ScholardexForumView forum = forum("o1", "Series");
        when(scholardexProjectionReadService.findForumById("o1")).thenReturn(Optional.of(forum));

        ScholardexForumDetailViewModel detail = service.findDetail("o1").orElseThrow();

        assertEquals(ScholardexForumDetailViewModel.ForumType.OTHER, detail.forumType());
        assertTrue(detail.showGenericPlaceholder());
        verify(wosRankingDetailsReadService, never()).findByJournalId("o1");
    }

    private ScholardexForumView forum(String id, String aggregationType) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName("Forum " + id);
        forum.setAggregationType(aggregationType);
        return forum;
    }
}
