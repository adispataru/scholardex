package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.*;

/**
 * Scoring service that evaluates Computer Science conferences based on CORE rankings.
 */
@Service
public class ComputerScienceConferenceScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceConferenceScoringService.class);
    private static final int LAST_CORE_YEAR = 2023;

    @Autowired
    public ComputerScienceConferenceScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum forum = lookupPort.getForum(publication.getForum());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);

        if (PublicationSubtypeSupport.isSubtype(publication, "cp")) {
            computeScoresWithForum(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    this::computeCOREScore
            );
            if(forum != null && forum.getPublicationName().contains("Lecture Notes in ")) {
                // Special case for LNCS chapters
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
                scoreResult.bestYear.set(LAST_CORE_YEAR);
            }
        }

        // Default scoring for SCOPUS-indexed conferences
        if (scoreResult.bestPoints.get() == 0 && forum != null) {
            scoreResult.bestPoints.set(1.0);
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
            scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            scoreResult.bestYear.set(LAST_CORE_YEAR);
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        if(forum.getPublicationName() == null) {
            return createScore(scoreResult);
        }
        List<Integer> allowedYears = 
                Indicator.parseYearRange(indicator.getScoreYearRange(), activity.getYear());

        computeScoresWithForum(
                domain,
                forum,
                allowedYears,
                scoreResult,
                this::computeCOREScore
        );

        // Handle LNCS and SCOPUS cases similar to publication scoring
        if (scoreResult.bestPoints.get() == 0 && forum != null) {
            if (forum.getPublicationName().contains("Lecture Notes in ")) {
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
            } else {
                scoreResult.bestPoints.set(1.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            }
            scoreResult.bestYear.set(LAST_CORE_YEAR);
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  Conference-specific scoring logic                                 */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeCOREScore(Forum forum, int year) {
        if(forum.getPublicationName() == null) {
            return Optional.empty();
        }
        String acronym = getAcronym(forum.getPublicationName());
        if (acronym.isEmpty()) {
            return Optional.empty();
        }

        List<CoreConferenceRanking> confRankings = lookupPort.getConferenceRankings(acronym);
        Score scoreResult = new Score();
        // Filter rankings by exact name match if multiple exist
        if (confRankings.size() > 1) {
            confRankings = confRankings.stream()
                    .filter(r -> forum.getPublicationName().contains(r.getName()))
                    .toList();
        }


        List<Double> scores = new ArrayList<>();
        for (CoreConferenceRanking conf : confRankings) {
            Optional<CoreConferenceRanking.YearlyRanking> yearlyRankOptional = Optional.ofNullable(conf.getClosestYear(year));
            if (yearlyRankOptional.isPresent()) {
                CoreConferenceRanking.YearlyRanking yearlyRank = yearlyRankOptional.get();
                double score = switch (yearlyRank.getRank()) {
                    case A_STAR -> 12.0;
                    case A -> 8.0;
                    case B -> 4.0;
                    case C -> 2.0;
                    default -> 1.0;
                };
                scores.add(score);
            }
        }

        if (scores.isEmpty()) {
            return Optional.empty();
        }

        double maxScore = scores.getFirst();
        for (Double score : scores) {
            if (score > maxScore) {
                maxScore = score;
            }
        }
        scoreResult.setScore(maxScore);
        scoreResult.setCategory(getCategory(maxScore).toString());

        return Optional.of(scoreResult);
    }

    private static String getAcronym(String publicationName) {
        String[] tokens = publicationName.split(", ");
        String[] split = tokens[tokens.length - 1].split(" ");
        return split[0];
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return "Scoring strategy for CNATDCU's Computer Science domain.(Categories based on CORE)\n" +
                "A* = 12p\n" +
                "A = 8p\n" +
                "B = 4p\n" +
                "C = 2p\n" +
                "D = 1p\n" +
                "LNCS = C = 2p\n" +
                "SCOPUS = D = 1p\n";
    }
}
