package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;


@Service
public class ComputerScienceBookService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceBookService.class);
    private static final int LAST_SENSE_YEAR = 2023;
    private final SenseRankingRepository senseRankingRepository;

    @Autowired
    public ComputerScienceBookService(SenseRankingRepository senseRankingRepository, ReportingLookupPort lookupPort) {
        super(lookupPort);
        this.senseRankingRepository = senseRankingRepository;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());

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

        if (scoreResult.bestPoints.get() > 0 && "SENSE".equals(scoreResult.scoringSource.get())) {
            scoreResult.scoringSource.set("SCOPUS+SENSE");
            scoreResult.scoringInfo.put("matchSource", "SENSE");
            scoreResult.scoringInfo.put("sourcesConsulted", List.of("SCOPUS", "SENSE"));
        }

        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = getForumFromActivity(activity);

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



    private Optional<Score> computeSENSEScore(ScholardexForumView forum, int year) {


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
                score.setCoreRankingEquivalent("A");
            }
            case B -> {
                score.setScore(8.0);
                score.setCoreRankingEquivalent("B");
            }
            case C -> {
                score.setScore(4.0);
                score.setCoreRankingEquivalent("C");
            }
            default -> {
                // SENSE D/E/unlisted: authored/edited book = 2p (chapter becomes 1p after the "ch" halving above).
                score.setScore(2.0);
                score.setCoreRankingEquivalent("D");
            }
        }
        Map<String, Object> scoringInfo = new LinkedHashMap<>();
        scoringInfo.put("matchSource", "SENSE");
        scoringInfo.put("publisher", forum.getPublisher());
        scoringInfo.put("resolvedYear", year);
        scoringInfo.put("resolvedRank", conf.getRanking().name());
        scoringInfo.put("sourcesConsulted", List.of("SENSE"));
        setProvenance(score, "SENSE", scoringInfo);
        return Optional.of(score);
    }


    public Optional<SenseBookRanking> matchByPublisher(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return Optional.empty();
        }
        return resolvePublisher(publisher);
    }

    private final ConcurrentMap<String, Optional<SenseBookRanking>> resolutionCache = new ConcurrentHashMap<>();
    private volatile List<SenseBookRanking> allRankingsSnapshot;

    private Optional<SenseBookRanking> resolvePublisher(String publisher) {
        return resolutionCache.computeIfAbsent(publisher, this::resolvePublisherUncached);
    }

    private Optional<SenseBookRanking> resolvePublisherUncached(String publisher) {
        List<SenseBookRanking> exact = senseRankingRepository.findAllByNameIgnoreCase(publisher);
        if (!exact.isEmpty()) {
            return Optional.of(pickBestRank(exact));
        }
        String normalizedPublisher = normalizePublisherName(publisher);
        if (normalizedPublisher.isBlank()) {
            return Optional.empty();
        }
        List<SenseBookRanking> hits = new ArrayList<>();
        for (SenseBookRanking ranking : allRankings()) {
            String senseNorm = normalizePublisherName(ranking.getName());
            if (senseNorm.isBlank()) {
                continue;
            }
            if (senseNorm.equals(normalizedPublisher)
                    || normalizedPublisher.contains(senseNorm)
                    || senseNorm.contains(normalizedPublisher)) {
                hits.add(ranking);
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pickBestRank(hits));
    }

    private List<SenseBookRanking> allRankings() {
        List<SenseBookRanking> snapshot = allRankingsSnapshot;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = allRankingsSnapshot;
                if (snapshot == null) {
                    snapshot = senseRankingRepository.findAll();
                    allRankingsSnapshot = snapshot;
                }
            }
        }
        return snapshot;
    }

    private static String normalizePublisherName(String name) {
        if (name == null) {
            return "";
        }
        String lowered = name.toLowerCase(java.util.Locale.ROOT);
        String stripped = lowered
                .replaceAll("[,.&]", " ")
                .replaceAll("\\b(inc|ltd|llc|gmbh|ag|sa|co|company|corp|corporation|publications?|publishers?|publishing|press|group|international|verlag|edizioni|editorial|editions|the|usa|uk|de|nv|bv)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped;
    }

    private static SenseBookRanking pickBestRank(List<SenseBookRanking> rankings) {
        return rankings.stream()
                .min(java.util.Comparator.comparingInt(r -> r.getRanking() == null ? Integer.MAX_VALUE : r.getRanking().ordinal()))
                .orElse(rankings.getFirst());
    }

    private List<SenseBookRanking> getBookRankings(ScholardexForumView forum) {
        if (forum == null) {
            return List.of();
        }
        String publisher = forum.getPublisher();
        if (publisher == null || publisher.isBlank()) {
            return List.of();
        }
        return resolvePublisher(publisher).map(List::of).orElseGet(List::of);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.CS_SENSE;
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
