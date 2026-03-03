package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.ReportingLookupPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComputerScienceConferenceScoringServiceSubtypeTest {

    @Mock
    private ReportingLookupPort cacheService;

    @Test
    void usesScopusSubtypeFallbackForConferenceBranch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = new Publication();
        publication.setForum("forum-1");
        publication.setCoverDate("2023-10-10");
        publication.setScopusSubtype("cp");
        publication.setSubtype(null);

        Forum forum = new Forum();
        forum.setPublicationName("Test Conference, TCONF");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }
}
