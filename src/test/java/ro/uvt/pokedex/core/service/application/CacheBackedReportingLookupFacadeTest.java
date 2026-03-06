package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheBackedReportingLookupFacadeTest {

    @Mock
    private CacheService cacheService;
    @Mock
    private ProjectionBackedReportingLookupFacade projectionBackedReportingLookupFacade;

    @InjectMocks
    private CacheBackedReportingLookupFacade facade;

    @Test
    void getForumDelegatesToCacheService() {
        Forum expected = new Forum();
        when(cacheService.getCachedForums("f1")).thenReturn(expected);

        Forum actual = facade.getForum("f1");

        assertSame(expected, actual);
        verify(cacheService).getCachedForums("f1");
    }

    @Test
    void getRankingsByIssnDelegatesToProjectionFacade() {
        List<WoSRanking> expected = List.of(new WoSRanking());
        when(projectionBackedReportingLookupFacade.getRankingsByIssn("issn")).thenReturn(expected);

        List<WoSRanking> actual = facade.getRankingsByIssn("issn");

        assertSame(expected, actual);
        verify(projectionBackedReportingLookupFacade).getRankingsByIssn("issn");
    }

    @Test
    void getConferenceRankingsDelegatesToCacheService() {
        List<CoreConferenceRanking> expected = List.of(new CoreConferenceRanking());
        when(cacheService.getCachedConfRankings("icse")).thenReturn(expected);

        List<CoreConferenceRanking> actual = facade.getConferenceRankings("icse");

        assertSame(expected, actual);
        verify(cacheService).getCachedConfRankings("icse");
    }

    @Test
    void getTopRankingsDelegatesToProjectionFacade() {
        when(projectionBackedReportingLookupFacade.getTopRankings("cat", 2023)).thenReturn(7);

        int actual = facade.getTopRankings("cat", 2023);

        assertEquals(7, actual);
        verify(projectionBackedReportingLookupFacade).getTopRankings("cat", 2023);
    }

    @Test
    void getUniversityAuthorIdsDelegatesToCacheService() {
        Set<String> expected = Set.of("a1");
        when(cacheService.getUniversityAuthorIds()).thenReturn(expected);

        Set<String> actual = facade.getUniversityAuthorIds();

        assertSame(expected, actual);
        verify(cacheService).getUniversityAuthorIds();
    }
}
