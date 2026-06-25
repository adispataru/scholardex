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

    /** LNCS-family series ("Lecture Notes in …": LNCS/LNAI/LNBIP) — conference proceedings, not a book venue. */
    private static boolean isLectureNotesSeries(ScholardexForumView forum) {
        String name = forum == null ? null : forum.getPublicationName();
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("lecture notes in ");
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        if (PredatoryVenueSupport.isExcludedVenue(forum)) {
            return createScore(initializeScoreResult()); // standard-excluded venue (WSEAS/IAENG/DAAAM): no points
        }

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = List.of(LAST_SENSE_YEAR);

        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        // LNCS-family proceedings (LNCS/LNAI/LNBIP, often subtype "ch") are conference papers, not books — they
        // are scored by the conference scorer. Excluding them here stops the same paper being counted as both a
        // book chapter (perspective d) and a conference (perspective b). The forum-name signal is precise (a
        // Springer ISBN DOI alone would also catch real Springer monographs, which ARE books). Once the DBLP
        // sweep re-stamps such a paper onto its conf/X forum, the Conference-Proceeding guard keeps it out too.
        boolean conferenceForum = forum != null && forum.hasAggregationType("Conference Proceeding");
        if (("ch".equals(subtype) || "bk".equals(subtype)) && !isLectureNotesSeries(forum) && !conferenceForum) {
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
            // The A/B/C/D rank is a SENSE publisher category (own point scale), not a CORE class — mark the
            // quartile slot SENSE so the UI shows "SENSE · A" and doesn't read it as CORE A or a NOT_FOUND default.
            scoreResult.bestQuarter.set(ro.uvt.pokedex.core.model.WoSRanking.Quarter.SENSE);
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
        // Book points by SENSE publisher rank, per Standarde-minimale-Info.pdf perspective d) item i) ("Cărți de
        // autor/editate și capitole publicate în edituri de categoria SENSE"): an authored/edited BOOK scores
        // A=16, B=8, C=4, D/E/unlisted=2 per volume. A CHAPTER is one rank lower — A=8, B=4, C=2, D/E=1 — which
        // the caller produces by halving these book points for subtype "ch". These are SENSE publisher categories,
        // NOT the CORE conference scale (a SENSE-A book = 16p, above CORE A* = 12p) — do not conflate the labels.
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
            if (publisherMatchesSenseName(normalizedPublisher, senseNorm)) {
                hits.add(ranking);
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pickBestRank(hits));
    }

    /**
     * Whether the SENSE publisher name appears in the forum publisher as a WHOLE-WORD phrase (mirrors the puncte
     * classifier's anchored name match). This replaces the former bidirectional character-substring test, which
     * over-matched short names — e.g. SENSE "ios" was found inside "bios scientific" — while still catching real
     * matches like "wiley" inside "john wiley sons" or "springer" inside "springer science business media". Names
     * shorter than 3 chars only match on full equality, to avoid spurious 1–2 letter hits.
     */
    private static boolean publisherMatchesSenseName(String normalizedPublisher, String senseName) {
        if (senseName.equals(normalizedPublisher)) {
            return true;
        }
        if (senseName.length() < 3) {
            return false;
        }
        return (" " + normalizedPublisher + " ").contains(" " + senseName + " ");
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
