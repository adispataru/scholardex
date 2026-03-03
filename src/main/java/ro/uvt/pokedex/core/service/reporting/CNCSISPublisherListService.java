package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CNCSISPublisher;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.reporting.CNCSISPublisherRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Service
public class CNCSISPublisherListService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(CNCSISPublisherListService.class);
    private static final int LAST_SENSE_YEAR = 2023;
    private final CNCSISPublisherRepository publisherRepository;
    private final ConcurrentMap<String, List<CNCSISPublisher>> rankingCache = new ConcurrentHashMap<>();

    @Autowired
    public CNCSISPublisherListService(CNCSISPublisherRepository senseRankingRepository, CacheService cacheService) {
        super(cacheService);
        this.publisherRepository = senseRankingRepository;
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

        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        if ("ch".equals(subtype) || "bk".equals(subtype)) {
            computeScoresWithForum(
                    domain,
                    forum,
                    allowedYears,
                    scoreResult,
                    this::computeSENSEScore
            );
            if("ch".equals(subtype)) {
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


        List<CNCSISPublisher> bookRankings = getPublisher(forum);

        // Find best ranking for the given year
        if(bookRankings.isEmpty()) {
            return Optional.empty();
        }else {
            Score score = new Score();
            score.setScore(1.0);
            return Optional.of(score);
        }
    }


    private List<CNCSISPublisher> getPublisher(Forum forum) {
        String publisher = forum.getPublisher();
        if (publisher == null || publisher.isEmpty()) {
            return List.of();
        }

        return getCachedRankings(publisher);
    }

    private List<CNCSISPublisher> getCachedRankings(String name) {
        return rankingCache.computeIfAbsent(name, publisherRepository::findAllByNameIgnoreCase);
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
