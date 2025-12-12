package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Service
public class ArtEventScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceConferenceScoringService.class);
    private static final int LAST_ARTS_YEAR = 2024;
    private final ArtisticEventRepository artEventsRepo;
    private final ConcurrentMap<String, List<ArtisticEvent>> rankingCache = new ConcurrentHashMap<>();

    @Autowired
    public ArtEventScoringService(ArtisticEventRepository senseRankingRepository, CacheService cacheService) {
        super(cacheService);
        this.artEventsRepo = senseRankingRepository;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(Publication publication, Indicator indicator) {

        ScoreResult scoreResult = initializeScoreResult();
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
        List<Integer> allowedYears = List.of(LAST_ARTS_YEAR);

        computeScoresWithForum(
                domain,
                forum,
                allowedYears,
                scoreResult,
                this::computeScore
        );

        return createScore(scoreResult);
    }



    private Optional<Score> computeScore(Forum forum, int year) {


        List<ArtisticEvent> eventRankings = getArtisticEvents(forum);

        if (eventRankings.isEmpty()) {
            return Optional.empty();
        }else{
            Score score = new Score();
            return eventRankings.stream()
                    .findFirst()
                    .map(r -> {
                        switch (r.getRank()) {
                            case INTERNATIONAL_TOP -> {
                                score.setScore(3.0);
                            }
                            case INTERNATIONAL -> {
                                score.setScore(2.0);
                            }
                            case NATIONAL -> {
                                score.setScore(1.0);
                            }
                            default -> {
                                return null;
                            }
                        }
                        return score;
                    });
        }
    }


    private List<ArtisticEvent> getArtisticEvents(Forum forum) {
        String publisher = forum.getPublicationName();
        if (publisher == null || publisher.isEmpty()) {
            return List.of();
        }

        return getCachedRankings(publisher);
    }

    private List<ArtisticEvent> getCachedRankings(String name) {
        return rankingCache.computeIfAbsent(name, artEventsRepo::findAllByNameIgnoreCase);
    }

    @Override
    public String getDescription() {
        return """
               Scoring strategy for CNATDCU's Artistic domain (Artistic Events).
               Categories based on SENSE publisher rankings:
               - INTERNATIONAL_TOP : 3
               - INTERNATIONAL : 2
               - NATIONAL : 1
               """;
    }
}