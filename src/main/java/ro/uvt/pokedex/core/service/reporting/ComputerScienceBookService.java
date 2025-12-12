package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Service
public class ComputerScienceBookService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService.class);
    private static final int LAST_SENSE_YEAR = 2023;
    private final SenseRankingRepository senseRankingRepository;
    private final ConcurrentMap<String, List<SenseBookRanking>> rankingCache = new ConcurrentHashMap<>();

    @Autowired
    public ComputerScienceBookService(SenseRankingRepository senseRankingRepository, CacheService cacheService) {
        super(cacheService);
        this.senseRankingRepository = senseRankingRepository;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        Forum forum = cacheService.getCachedForums(publication.getForum());

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = List.of(LAST_SENSE_YEAR);

        if ("ch".equals(publication.getSubtype()) || "bk".equals(publication.getSubtype())) {
            computeScoresWithForum(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    this::computeSENSEScore
            );
            if("ch".equals(publication.getSubtype())) {
                scoreResult.bestPoints.set(scoreResult.bestPoints.get() / 2);
            }
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
        List<Integer> allowedYears = List.of(LAST_SENSE_YEAR);

        computeScoresWithForum(
                domain,
                forum,
                allowedYears,
                scoreResult,
                this::computeSENSEScore
        );

        return createScore(scoreResult);
    }



    private Optional<Score> computeSENSEScore(Forum forum, int year) {


        List<SenseBookRanking> bookRankings = getBookRankings(forum);

        if (bookRankings.isEmpty()) {
            return Optional.empty();
        }
        Score score = new Score();
        // Find best ranking for the given year
        SenseBookRanking conf  =  bookRankings.getFirst();
        switch (conf.getRanking()) {
            case A -> {
                score.setScore(16.0);
                score.setCategory("A");
            }
            case B -> {
                score.setScore(8.0);
                score.setCategory("B");
            }
            case C -> {
                score.setScore(4.0);
                score.setCategory("C");
            }
            default -> {
                score.setScore(1.0);
                score.setCategory("Unlisted");
            }
        }
        return Optional.of(score);
    }


    private List<SenseBookRanking> getBookRankings(Forum forum) {
        String publisher = forum.getPublisher();
        if (publisher == null || publisher.isEmpty()) {
            return List.of();
        }

        return getCachedRankings(publisher);
    }

    private List<SenseBookRanking> getCachedRankings(String name) {
        return rankingCache.computeIfAbsent(name, senseRankingRepository::findAllByNameIgnoreCase);
    }

    @Override
    public String getDescription() {
        return """
               Scoring strategy for CNATDCU's Computer Science domain (Books).
               Categories based on SENSE publisher rankings:
               - A = 16p
               - B = 8p
               - C = 4p
               - D = 2p
               """;
    }
}