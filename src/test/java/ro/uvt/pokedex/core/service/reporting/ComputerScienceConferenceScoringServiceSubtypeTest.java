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
import java.util.Map;
import java.util.Optional;

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

    @Test
    void resolvesConferenceWhenNormalizedNameMatches() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings of the 46th International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(12.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.getCategory());
    }

    @Test
    void ambiguousAcronymMatchFallsBackToScopus() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Proceedings of ICSE");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR),
                ranking("ICSE", "International Conference on Systems Engineering", CoreConferenceRanking.Rank.B)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void weakSubstringCollisionIsRejected() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Publication publication = conferencePublication("forum-1", "2023-10-10");
        Forum forum = new Forum();
        forum.setPublicationName("Conference on Software Tools, ICST");
        when(cacheService.getForum("forum-1")).thenReturn(forum);
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICST")).thenReturn(List.of(
                ranking("ICST", "International Conference on Software Testing", CoreConferenceRanking.Rank.A_STAR)
        ));

        Score score = service.getScore(publication, new Indicator());

        assertEquals(1.0, score.getScore());
        assertEquals(CoreConferenceRanking.Rank.D.toString(), score.getCategory());
        assertEquals(WoSRanking.Quarter.SCOPUS.toString(), score.getQuarter());
    }

    @Test
    void tryResolveCoreScoreCanRecoverConferenceFromLncsStyleVenueWhenAcronymAndNameMatch() {
        ComputerScienceConferenceScoringService service = new ComputerScienceConferenceScoringService(cacheService);

        Forum forum = new Forum();
        forum.setPublicationName("Lecture Notes in Computer Science, International Conference on Software Engineering, ICSE 2024");
        when(cacheService.getConferenceRankings(anyString())).thenReturn(List.of());
        when(cacheService.getConferenceRankings("ICSE")).thenReturn(List.of(
                ranking("ICSE", "International Conference on Software Engineering", CoreConferenceRanking.Rank.A_STAR)
        ));

        Optional<Score> score = service.tryResolveCoreScore(forum, 2024);

        assertEquals(true, score.isPresent());
        assertEquals(CoreConferenceRanking.Rank.A_STAR.toString(), score.get().getCategory());
    }

    private Publication conferencePublication(String forumId, String coverDate) {
        Publication publication = new Publication();
        publication.setForum(forumId);
        publication.setCoverDate(coverDate);
        publication.setScopusSubtype("cp");
        publication.setSubtype(null);
        return publication;
    }

    private CoreConferenceRanking ranking(String acronym, String name, CoreConferenceRanking.Rank rank) {
        CoreConferenceRanking ranking = new CoreConferenceRanking();
        ranking.setAcronym(acronym);
        ranking.setName(name);
        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(rank);
        ranking.setYearlyRankings(Map.of(2023, yearlyRanking, 2024, yearlyRanking));
        return ranking;
    }
}
