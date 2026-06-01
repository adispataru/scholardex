package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

class AbstractForumScoringServiceTest {

    @Test
    void getForumFromActivityMapsEissnAndIsbn() {
        TestService service = new TestService();
        ActivityInstance activity = new ActivityInstance();
        activity.setReferenceFields(Map.of(
                Activity.ReferenceField.FORUM_NAME, "Forum Name",
                Activity.ReferenceField.FORUM_EISSN, "2000-0001",
                Activity.ReferenceField.FORUM_ISBN, "978-1-2345-6789-0"
        ));

        ScholardexForumView forum = service.exposeForumFromActivity(activity);

        assertEquals("Forum Name", forum.getPublicationName());
        assertEquals("2000-0001", forum.getEIssn());
        assertEquals("978-1-2345-6789-0", forum.getIsbn());
    }

    @Test
    void getForumFromActivityUsesEventNameAndPublisherWhenPresent() {
        TestService service = new TestService();
        ActivityInstance activity = new ActivityInstance();
        activity.setReferenceFields(Map.of(
                Activity.ReferenceField.FORUM_NAME, "Old Name",
                Activity.ReferenceField.EVENT_NAME, "Event Name Wins",
                Activity.ReferenceField.FORUM_PUBLISHER, "Publisher X"
        ));

        ScholardexForumView forum = service.exposeForumFromActivity(activity);

        assertEquals("Event Name Wins", forum.getPublicationName());
        assertEquals("Publisher X", forum.getPublisher());
    }

    @Test
    void getBestQuarterReturnsSortedBestAndNotFoundForEmpty() {
        TestService service = new TestService();

        WoSRanking ranking = new WoSRanking();
        WoSRanking.Rank catA = new WoSRanking.Rank();
        catA.setQAis(Map.of(2023, WoSRanking.Quarter.Q3, 2022, WoSRanking.Quarter.Q1));
        catA.setQIF(Map.of(2023, WoSRanking.Quarter.Q2));
        ranking.setWebOfScienceCategoryIndex(Map.of("A", catA));

        assertEquals(WoSRanking.Quarter.Q1, service.getBestQuarter(ranking));

        WoSRanking empty = new WoSRanking();
        empty.setWebOfScienceCategoryIndex(Map.of());
        assertEquals(WoSRanking.Quarter.NOT_FOUND, service.getBestQuarter(empty));
    }

    @Test
    void getCategoryCoversKnownAndDefaultMappings() {
        TestService service = new TestService();
        assertEquals(CoreConferenceRanking.Rank.A, service.exposeCategory(8.0));
        assertEquals(CoreConferenceRanking.Rank.D, service.exposeCategory(1.0));
        assertEquals(CoreConferenceRanking.Rank.NON_RANK, service.exposeCategory(3.0));
    }

    @Test
    void computeScoresWithForumCopiesProvenanceAndExtra() {
        TestService service = new TestService();
        AbstractForumScoringService.ScoreResult result = service.newResult();
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName("F");

        service.exposeComputeScoresWithForum(
                forum,
                List.of(2023, 2022),
                result,
                (f, year) -> {
                    if (year == 2023) {
                        Score s = new Score();
                        s.setScore(4.0);
                        s.setCoreRankingEquivalent("B");
                        s.setScoringSource("WOS");
                        s.setScoringInfo(Map.of("k", "v"));
                        s.getExtra().put("M", 10);
                        return Optional.of(s);
                    }
                    return Optional.empty();
                }
        );

        Score score = service.toScore(result);
        assertEquals(4.0, score.getScore());
        assertEquals("B", score.getCoreRankingEquivalent());
        assertEquals(2023, score.getYear());
        assertEquals("WOS", score.getScoringSource());
        assertEquals("v", score.getScoringInfo().get("k"));
        assertEquals(10, score.getExtra().get("M"));
    }

    @Test
    void computeScoresWithUniversityPicksFirstThenLowerScore() {
        TestService service = new TestService();
        AbstractForumScoringService.ScoreResult result = service.newResult();
        service.exposeComputeScoresWithUniversity(
                List.of(2023, 2022),
                result,
                year -> year == 2023 ? Optional.of(9.0) : Optional.of(7.0)
        );

        Score score = service.toScore(result);
        assertEquals(7.0, score.getScore());
        assertEquals(2022, score.getYear());
    }

    @Test
    void getCategoryHandlesAStarCase() {
        TestService service = new TestService();
        assertEquals(CoreConferenceRanking.Rank.A_STAR, service.exposeCategory(12.0));
    }

    private static final class TestService extends AbstractForumScoringService {
        TestService() {
            super(org.mockito.Mockito.mock(ReportingLookupPort.class));
        }

        ScholardexForumView exposeForumFromActivity(ActivityInstance activity) {
            return getForumFromActivity(activity);
        }

        CoreConferenceRanking.Rank exposeCategory(double points) {
            return getCategory(points);
        }

        AbstractForumScoringService.ScoreResult newResult() {
            return initializeScoreResult();
        }

        void exposeComputeScoresWithForum(ScholardexForumView forum,
                                          List<Integer> years,
                                          AbstractForumScoringService.ScoreResult result,
                                          java.util.function.BiFunction<ScholardexForumView, Integer, Optional<Score>> extractor) {
            computeScoresWithForum(null, forum, years, result, extractor);
        }

        void exposeComputeScoresWithUniversity(List<Integer> years,
                                               AbstractForumScoringService.ScoreResult result,
                                               java.util.function.Function<Integer, Optional<Double>> extractor) {
            computeScoresWithUniversity(null, years, result, (ignored, y) -> extractor.apply(y));
        }

        Score toScore(AbstractForumScoringService.ScoreResult result) {
            return createScore(result);
        }

        @Override
        public Score getScore(ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel publication, Indicator indicator) {
            return new Score();
        }

        @Override
        public Score getScore(ActivityInstance activity, Indicator indicator) {
            return new Score();
        }

        @Override
        public ScoringStrategy strategy() {
            return ScoringStrategy.CS;  // arbitrary — these stubs are tested directly, not via the factory
        }

        @Override
        public String getDescription() {
            return "test";
        }
    }
}
