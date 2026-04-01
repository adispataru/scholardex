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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scoring service that evaluates Computer Science conferences based on CORE rankings.
 */
@Service
public class ComputerScienceConferenceScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceConferenceScoringService.class);
    private static final int LAST_CORE_YEAR = 2023;
    private static final Pattern ORDINAL_PREFIX = Pattern.compile("^\\d+(st|nd|rd|th)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_TOKEN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Set<String> BOILERPLATE_TOKENS = Set.of(
            "proceedings", "of", "the", "international", "conference", "symposium", "workshop",
            "on", "for", "and", "in", "annual", "ieee", "acm", "ifip", "euromicro", "joint"
    );

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
        return tryResolveCoreScore(forum, year);
    }

    public Optional<Score> tryResolveCoreScore(Forum forum, int year) {
        ConferenceMatch match = resolveConferenceMatch(forum == null ? null : forum.getPublicationName());
        if (!match.resolved()) {
            return Optional.empty();
        }
        Score scoreResult = new Score();
        List<Double> scores = new ArrayList<>();
        Optional<CoreConferenceRanking.YearlyRanking> yearlyRankOptional = Optional.ofNullable(match.ranking().getClosestYear(year));
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

    private ConferenceMatch resolveConferenceMatch(String publicationName) {
        if (publicationName == null || publicationName.isBlank()) {
            return ConferenceMatch.unresolved();
        }
        String normalizedPublicationName = normalizeVenueName(publicationName);
        if (normalizedPublicationName.isBlank()) {
            return ConferenceMatch.unresolved();
        }

        Map<CoreConferenceRanking, MatchConfidence> candidateConfidence = new LinkedHashMap<>();
        for (String acronymCandidate : extractAcronymCandidates(publicationName)) {
            List<CoreConferenceRanking> confRankings = Optional.ofNullable(lookupPort.getConferenceRankings(acronymCandidate))
                    .orElse(List.of());
            for (CoreConferenceRanking ranking : confRankings) {
                MatchConfidence confidence = scoreMatchConfidence(normalizedPublicationName, ranking);
                if (confidence == MatchConfidence.NONE) {
                    continue;
                }
                MatchConfidence existing = candidateConfidence.get(ranking);
                if (existing == null || confidence.score > existing.score) {
                    candidateConfidence.put(ranking, confidence);
                }
            }
        }

        if (candidateConfidence.isEmpty()) {
            return ConferenceMatch.unresolved();
        }

        int bestScore = candidateConfidence.values().stream()
                .mapToInt(confidence -> confidence.score)
                .max()
                .orElse(MatchConfidence.NONE.score);
        if (bestScore < MatchConfidence.NORMALIZED_CONTAINS.score) {
            return ConferenceMatch.unresolved();
        }

        List<CoreConferenceRanking> winners = candidateConfidence.entrySet().stream()
                .filter(entry -> entry.getValue().score == bestScore)
                .map(Map.Entry::getKey)
                .toList();
        if (winners.size() != 1) {
            return ConferenceMatch.unresolved();
        }

        return new ConferenceMatch(true, winners.getFirst());
    }

    private List<String> extractAcronymCandidates(String publicationName) {
        Set<String> candidates = new LinkedHashSet<>();
        String[] fragments = publicationName.split(",");
        for (String fragment : fragments) {
            String trimmed = fragment == null ? "" : fragment.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            for (String token : tokens) {
                String normalized = normalizeAcronymToken(token);
                if (normalized.length() >= 2) {
                    candidates.add(normalized);
                }
            }
            if (tokens.length > 0) {
                String leading = normalizeAcronymToken(tokens[0]);
                if (leading.length() >= 2) {
                    candidates.add(leading);
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private String normalizeAcronymToken(String token) {
        if (token == null) {
            return "";
        }
        return token
                .replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "")
                .replaceAll("[^A-Za-z0-9-]", "")
                .toUpperCase(Locale.ROOT);
    }

    private MatchConfidence scoreMatchConfidence(String normalizedPublicationName, CoreConferenceRanking ranking) {
        String rankingName = normalizeVenueName(ranking.getName());
        if (rankingName.isBlank()) {
            return MatchConfidence.NONE;
        }
        if (normalizedPublicationName.equals(rankingName)) {
            return MatchConfidence.EXACT_NORMALIZED_NAME;
        }

        String normalizedWithoutBoilerplate = normalizeWithoutBoilerplate(normalizedPublicationName);
        String rankingWithoutBoilerplate = normalizeWithoutBoilerplate(rankingName);
        if (!normalizedWithoutBoilerplate.isBlank() && normalizedWithoutBoilerplate.equals(rankingWithoutBoilerplate)) {
            return MatchConfidence.NORMALIZED_NAME_WITHOUT_BOILERPLATE;
        }

        if (normalizedPublicationName.contains(rankingName) || rankingName.contains(normalizedPublicationName)) {
            return MatchConfidence.NORMALIZED_CONTAINS;
        }

        Set<String> publicationTokens = significantTokens(normalizedWithoutBoilerplate);
        Set<String> rankingTokens = significantTokens(rankingWithoutBoilerplate);
        if (!publicationTokens.isEmpty() && publicationTokens.equals(rankingTokens)) {
            return MatchConfidence.TOKEN_SET_EQUAL;
        }
        if (!publicationTokens.isEmpty() && !rankingTokens.isEmpty() && publicationTokens.containsAll(rankingTokens)) {
            return MatchConfidence.TOKEN_SUPERSET;
        }
        return MatchConfidence.NONE;
    }

    private String normalizeVenueName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replace('\u00A0', ' ')
                .replaceAll("[()/:\\-]", " ")
                .replaceAll("[,.;]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        normalized = ORDINAL_PREFIX.matcher(normalized).replaceFirst("");
        normalized = YEAR_TOKEN.matcher(normalized).replaceAll(" ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String normalizeWithoutBoilerplate(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return "";
        }
        return Arrays.stream(normalizedValue.split("\\s+"))
                .filter(token -> !BOILERPLATE_TOKENS.contains(token))
                .collect(Collectors.joining(" "))
                .trim();
    }

    private Set<String> significantTokens(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(normalizedValue.split("\\s+"))
                .filter(token -> token.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record ConferenceMatch(boolean resolved, CoreConferenceRanking ranking) {
        static ConferenceMatch unresolved() {
            return new ConferenceMatch(false, null);
        }
    }

    private enum MatchConfidence {
        NONE(0),
        NORMALIZED_CONTAINS(1),
        TOKEN_SUPERSET(2),
        TOKEN_SET_EQUAL(3),
        NORMALIZED_NAME_WITHOUT_BOILERPLATE(4),
        EXACT_NORMALIZED_NAME(5);

        private final int score;

        MatchConfidence(int score) {
            this.score = score;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return """
                Scoring strategy for CNATDCU's Computer Science domain.(Categories based on CORE)
                A* = 12p
                A = 8p
                B = 4p
                C = 2p
                D = 1p
                LNCS = C = 2p
                SCOPUS = D = 1p
                """;
    }
}
