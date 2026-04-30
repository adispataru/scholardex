package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractWoSForumScoringServiceTest {

    @Test
    void compareScoresByPointsPrefersHigherScore() {
        TestService service = new TestService();
        AbstractForumScoringService.ScoreResult result = service.newResult();
        result.bestPoints.set(2.0);

        Score candidate = new Score();
        candidate.setScore(4.0);

        assertTrue(service.compare(candidate, result));
    }

    @Test
    void compareScoresByPointsRejectsEqualScoreWhenQuarterMissing() {
        TestService service = new TestService();
        AbstractForumScoringService.ScoreResult result = service.newResult();
        result.bestPoints.set(4.0);
        result.bestQuarter.set(WoSRanking.Quarter.Q2);

        Score candidate = new Score();
        candidate.setScore(4.0);
        candidate.setQuarter(null);

        assertFalse(service.compare(candidate, result));
    }

    @Test
    void compareScoresByPointsUsesQuarterTieBreakWhenScoresEqual() {
        TestService service = new TestService();
        AbstractForumScoringService.ScoreResult result = service.newResult();
        result.bestPoints.set(4.0);
        result.bestQuarter.set(WoSRanking.Quarter.Q3);

        Score candidate = new Score();
        candidate.setScore(4.0);
        candidate.setQuarter(WoSRanking.Quarter.Q1.name());

        assertTrue(service.compare(candidate, result));
    }

    private static final class TestService extends AbstractWoSForumScoringService {
        TestService() {
            super(null);
        }

        AbstractForumScoringService.ScoreResult newResult() {
            return initializeScoreResult();
        }

        boolean compare(Score score, AbstractForumScoringService.ScoreResult result) {
            return compareScoresByPoints(score, result);
        }

        @Override
        public Score getScore(ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel publication,
                              ro.uvt.pokedex.core.model.reporting.Indicator indicator) {
            Score score = new Score();
            score.setCoreRankingEquivalent(CoreConferenceRanking.Rank.NON_RANK.name());
            return score;
        }

        @Override
        public Score getScore(ro.uvt.pokedex.core.model.activities.ActivityInstance activity,
                              ro.uvt.pokedex.core.model.reporting.Indicator indicator) {
            Score score = new Score();
            score.setCoreRankingEquivalent(CoreConferenceRanking.Rank.NON_RANK.name());
            return score;
        }

        @Override
        public String getDescription() {
            return "test";
        }
    }
}
