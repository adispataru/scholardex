package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComputerScienceJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    @Test
    void missingAisRankForYearDoesNotThrowAndFallsBackToLowerTier() {
        ComputerScienceJournalScoringService service = new ComputerScienceJournalScoringService(lookupPort);

        Domain domain = new Domain();
        domain.setName("ALL");

        Indicator indicator = new Indicator();
        indicator.setDomain(domain);

        Publication publication = new Publication();
        publication.setSubtype("ar");
        publication.setForum("forum-1");

        Forum forum = new Forum();
        forum.setIssn("1234-5678");
        when(lookupPort.getForum("forum-1")).thenReturn(forum);

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(ScoringService.LAST_YEAR, WoSRanking.Quarter.Q3));
        // No rankAis entry for LAST_YEAR on purpose.

        WoSRanking ranking = new WoSRanking();
        ranking.setId("j-1");
        ranking.setWebOfScienceCategoryIndex(Map.of("Computer Science, Theory & Methods - SCIE", rank));

        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));
        when(lookupPort.getTopRankings("Computer Science, Theory & Methods - SCIE", ScoringService.LAST_YEAR)).thenReturn(100);

        Score score = service.getScore(publication, indicator);

        assertEquals(2.0, score.getScore());
        assertEquals("C", score.getCategory());
        assertEquals("Q3", score.getQuarter());
        assertEquals(ScoringService.LAST_YEAR, score.getYear());
    }
}
