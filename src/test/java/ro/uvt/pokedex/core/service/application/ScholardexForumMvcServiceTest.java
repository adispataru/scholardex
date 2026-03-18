package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;
import ro.uvt.pokedex.core.model.scopus.Forum;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Forum journal = forum("f1", "Journal One", "1234-5678", null, "Journal");
        Forum conference = forum("f2", "Conference One", null, null, "Conference Proceeding");
        WosForumResolutionService.ResolutionIndex resolutionIndex =
                new WosForumResolutionService.ResolutionIndex(java.util.Map.of("12345678", "w1"), java.util.Map.of());

        when(scholardexProjectionReadService.findAllForums()).thenReturn(List.of(journal, conference));
        when(wosForumResolutionService.buildResolutionIndex()).thenReturn(resolutionIndex);
        when(wosForumResolutionService.resolveJournalId(journal, resolutionIndex)).thenReturn("w1");
        when(wosForumResolutionService.resolveJournalId(conference, resolutionIndex)).thenReturn(null);

        ScholardexForumPageResponse result = service.search(0, 25, "publicationName", "asc", null, "all");

        assertEquals(2, result.items().size());
        assertEquals("indexed", result.items().get(1).wosStatus());
        assertEquals("not_applicable", result.items().get(0).wosStatus());
        verify(wosForumResolutionService).buildResolutionIndex();
    }

    private Forum forum(String id, String publicationName, String issn, String eIssn, String aggregationType) {
        Forum forum = new Forum();
        forum.setId(id);
        forum.setPublicationName(publicationName);
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        forum.setAggregationType(aggregationType);
        return forum;
    }
}
