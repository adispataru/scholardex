package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumTablePageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScholardexForumMvcServiceTest {

    @Mock
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @Mock
    private WosForumResolutionService wosForumResolutionService;

    private ScholardexForumMvcService service;

    @BeforeEach
    void setUp() {
        service = new ScholardexForumMvcService(scholardexProjectionReadService, wosForumResolutionService);
    }

    @Test
    void searchBuildsWosStatusFromBulkRankingViewIndex() {
        ScholardexForumView journal = forum("f1", "Journal One", "1234-5678", null, "Journal");
        ScholardexForumView conference = forum("f2", "Conference One", null, null, "Conference Proceeding");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of("12345678", "w1"), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(journal, conference));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(journal, resolutionIndex)).thenReturn("w1");
        when(wosForumResolutionService.resolveJournalId(conference, resolutionIndex)).thenReturn(null);

        ScholardexForumTablePageResponse result = service.search(0, 25, "publicationName", "asc", null, "all");

        assertEquals(2, result.items().size());
        assertEquals("indexed", result.items().get(1).wosStatus());
        assertEquals("not_applicable", result.items().get(0).wosStatus());
        verify(wosForumResolutionService).buildResolutionIndex();
    }

    @Test
    void searchUsesDefaultPageSizeWhenSizeIsZeroOrNegative() {
        ScholardexForumView journal = forum("f1", "Journal One", "1234-5678", null, "Journal");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of("12345678", "w1"), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(journal));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(journal, resolutionIndex)).thenReturn("w1");

        ScholardexForumTablePageResponse zero = service.search(0, 0, "publicationName", "asc", null, "all");
        ScholardexForumTablePageResponse negative = service.search(0, -3, "publicationName", "asc", null, "all");

        assertEquals(25, zero.size());
        assertEquals(25, negative.size());
        assertEquals(1, zero.items().size());
    }

    @Test
    void searchAppliesQueryAndWosFiltersAndPaging() {
        ScholardexForumView indexedJournal = forum("f1", "AI Journal", "1234-5678", null, "Journal");
        ScholardexForumView notIndexedJournal = forum("f2", "Math Journal", "2234-5678", null, "Journal");
        ScholardexForumView conference = forum("f3", "AI Conference", null, null, "Conference Proceeding");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(indexedJournal, notIndexedJournal, conference));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(indexedJournal, resolutionIndex)).thenReturn("w1");
        when(wosForumResolutionService.resolveJournalId(notIndexedJournal, resolutionIndex)).thenReturn(null);
        when(wosForumResolutionService.resolveJournalId(conference, resolutionIndex)).thenReturn(null);

        ScholardexForumTablePageResponse qFiltered = service.search(0, 25, "publicationName", "asc", "  ai  ", "all");
        assertEquals(2, qFiltered.items().size());

        ScholardexForumTablePageResponse wosFiltered = service.search(0, 25, "publicationName", "asc", null, "indexed");
        assertEquals(1, wosFiltered.items().size());
        assertEquals("f1", wosFiltered.items().getFirst().id());

        ScholardexForumTablePageResponse paged = service.search(1, 1, "publicationName", "asc", null, "all");
        assertEquals(1, paged.items().size());
        assertEquals(3, paged.totalItems());
        assertEquals(3, paged.totalPages());
        assertEquals(1, paged.page());
    }

    @Test
    void searchNormalizesMissingIssnsAndProvidesForumNameFallback() {
        ScholardexForumView forum = forum("f1", " ", "null-", " null ", "Book");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(forum, resolutionIndex)).thenReturn(null);

        ScholardexForumTablePageResponse result = service.search(0, 25, "publicationName", "asc", null, "all");

        assertEquals("Untitled forum f1", result.items().getFirst().publicationName());
        assertEquals("", result.items().getFirst().issn());
        assertEquals("", result.items().getFirst().eIssn());
    }

    @Test
    void searchSupportsAllSortKeysAndDescDirection() {
        ScholardexForumView a = forum("f1", "A Journal", "1000-0000", "9000-0000", "Journal");
        ScholardexForumView b = forum("f2", "B Journal", "2000-0000", "8000-0000", "Journal");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(a, b));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(a, resolutionIndex)).thenReturn(null);
        when(wosForumResolutionService.resolveJournalId(b, resolutionIndex)).thenReturn("w2");

        assertEquals("f2", service.search(0, 25, "publicationName", "desc", null, "all").items().getFirst().id());
        assertEquals("f2", service.search(0, 25, "issn", "desc", null, "all").items().getFirst().id());
        assertEquals("f1", service.search(0, 25, "eIssn", "desc", null, "all").items().getFirst().id());
        assertEquals("f1", service.search(0, 25, "wosStatus", "desc", null, "all").items().getFirst().id());
        assertEquals("f1", service.search(0, 25, "aggregationType", "asc", null, "all").items().getFirst().id());
    }

    @Test
    void searchRejectsInvalidParameters() {
        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of());
        when(wosForumResolutionService.buildResolutionIndex())
                .thenReturn(new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of()));

        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "unknown", "asc", null, "all"));
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "publicationName", "up", null, "all"));
        assertThrows(IllegalArgumentException.class, () -> service.search(0, 25, "publicationName", "asc", null, "bad"));
    }

    @Test
    void searchRejectsOverlongQueryAndTreatsBlankQueryAsNull() {
        ScholardexForumView forum = forum("f1", "Journal One", "1234-5678", null, "Journal");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of(), java.util.Map.of());
        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(forum));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(forum, resolutionIndex)).thenReturn(null);

        ScholardexForumTablePageResponse blank = service.search(0, 25, "publicationName", "asc", "   ", "all");
        assertEquals(1, blank.items().size());

        String overlong = "x".repeat(101);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.search(0, 25, "publicationName", "asc", overlong, "all")
        );
        assertTrue(ex.getMessage().contains("Maximum length is 100"));
    }

    private ScholardexForumView forum(String id, String publicationName, String issn, String eIssn, String aggregationType) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(publicationName);
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        forum.setAggregationType(aggregationType);
        return forum;
    }
}
