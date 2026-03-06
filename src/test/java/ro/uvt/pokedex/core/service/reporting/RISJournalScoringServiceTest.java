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
class RISJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    @Test
    void returnsDeterministicRisScoreFromWosRanking() {
        RISJournalScoringService service = new RISJournalScoringService(lookupPort);
        Indicator indicator = indicatorForAllDomain();
        Publication publication = publication("forum-1", "2023-01-01");
        Forum forum = forum("1234-5678");
        WoSRanking ranking = rankingWithRis("ECONOMICS - SCIE", 2023, 0.72, WoSRanking.Quarter.Q2);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, indicator);

        assertEquals(0.72, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals("Q2", score.getQuarter());
    }

    private Indicator indicatorForAllDomain() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setScoreYearRange("IY");
        return indicator;
    }

    private Publication publication(String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setForum(forumId);
        publication.setSubtype("ar");
        publication.setCoverDate(coverDate);
        return publication;
    }

    private Forum forum(String issn) {
        Forum forum = new Forum();
        forum.setIssn(issn);
        return forum;
    }

    private WoSRanking rankingWithRis(String category, int year, double value, WoSRanking.Quarter quarter) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setRis(Map.of(year, value));

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQAis(Map.of(year, quarter));

        WoSRanking ranking = new WoSRanking();
        ranking.setScore(score);
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
