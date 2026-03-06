package ro.uvt.pokedex.core.service.reporting;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
class ImpactFactorJournalScoringServiceTest {

    @Mock
    private ReportingLookupPort lookupPort;

    @Test
    void missingIfDataReturnsEmptyScoreAndIncrementsMissingCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);

        Indicator indicator = indicatorForAllDomain();
        Publication publication = publication("forum-1", "ar", "2023-01-01");
        Forum forum = forum("1234-5678", null);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of());
        when(lookupPort.getRankingsByIssn(null)).thenReturn(List.of());

        Score score = service.getScore(publication, indicator);

        assertEquals(0.0, score.getScore());
        assertEquals(0, score.getYear());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.requests").counter().count());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.missing").counter().count());
        assertEquals(0.0, meterRegistry.get("pokedex.reporting.if.success").counter().count());
    }

    @Test
    void ifDataPresentReturnsScoreAndIncrementsSuccessCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImpactFactorJournalScoringService service = new ImpactFactorJournalScoringService(lookupPort, meterRegistry);

        Indicator indicator = indicatorForAllDomain();
        Publication publication = publication("forum-1", "ar", "2023-01-01");
        Forum forum = forum("1234-5678", null);
        WoSRanking ranking = rankingWithIf("ECONOMICS - SCIE", 2023, 2.5, WoSRanking.Quarter.Q1);

        when(lookupPort.getForum("forum-1")).thenReturn(forum);
        when(lookupPort.getRankingsByIssn("1234-5678")).thenReturn(List.of(ranking));

        Score score = service.getScore(publication, indicator);

        assertEquals(2.5, score.getScore());
        assertEquals(2023, score.getYear());
        assertEquals("Q1", score.getQuarter());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.requests").counter().count());
        assertEquals(0.0, meterRegistry.get("pokedex.reporting.if.missing").counter().count());
        assertEquals(1.0, meterRegistry.get("pokedex.reporting.if.success").counter().count());
    }

    private Indicator indicatorForAllDomain() {
        Domain domain = new Domain();
        domain.setName("ALL");
        Indicator indicator = new Indicator();
        indicator.setDomain(domain);
        indicator.setScoreYearRange("IY");
        return indicator;
    }

    private Publication publication(String forumId, String subtype, String coverDate) {
        Publication publication = new Publication();
        publication.setId("pub-1");
        publication.setForum(forumId);
        publication.setSubtype(subtype);
        publication.setCoverDate(coverDate);
        return publication;
    }

    private Forum forum(String issn, String eIssn) {
        Forum forum = new Forum();
        forum.setPublicationName("Test Journal");
        forum.setIssn(issn);
        forum.setEIssn(eIssn);
        return forum;
    }

    private WoSRanking rankingWithIf(String category, int year, double value, WoSRanking.Quarter quarter) {
        WoSRanking.Score score = new WoSRanking.Score();
        score.setIF(Map.of(year, value));

        WoSRanking.Rank rank = new WoSRanking.Rank();
        rank.setQIF(Map.of(year, quarter));
        rank.setRankIF(Map.of(year, 1));

        WoSRanking ranking = new WoSRanking();
        ranking.setId("jid-1");
        ranking.setScore(score);
        ranking.setWebOfScienceCategoryIndex(Map.of(category, rank));
        return ranking;
    }
}
