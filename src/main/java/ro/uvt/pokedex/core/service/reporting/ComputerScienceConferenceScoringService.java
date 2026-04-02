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
    private static final Pattern ORDINAL_TOKEN = Pattern.compile("^\\d+(ST|ND|RD|TH)$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BOILERPLATE_TOKENS = Set.of(
            "proceedings", "of", "the", "international", "conference", "symposium", "workshop",
            "on", "for", "and", "in", "annual", "ieee", "acm", "ifip", "euromicro", "joint"
    );
    private static final Set<String> ACRONYM_STOPWORDS = Set.of(
            "PROCEEDINGS", "INTERNATIONAL", "SYMPOSIUM", "CONFERENCE", "WORKSHOP",
            "COMPUTING", "COMPUTER", "SOFTWARE", "APPLICATIONS", "ANNUAL",
            "ON", "AND", "OF", "THE", "FOR", "IN"
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
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(
                publication == null ? null : publication.getId(),
                publication == null ? null : PublicationSubtypeSupport.resolveSubtype(publication),
                publication == null ? null : publication.getCoverDate(),
                forum == null ? null : forum.getPublicationName(),
                allowedYears
        );

        if (PublicationSubtypeSupport.isSubtype(publication, "cp")) {
            Score resolvedScore = null;
            for (int year : allowedYears) {
                ConferenceScoreResolution resolution = resolveConferenceScore(forum, year, trace);
                trace = resolution.trace();
                if (resolution.score().isPresent()) {
                    Score candidate = resolution.score().get();
                    if (resolvedScore == null || candidate.getScore() > resolvedScore.getScore()) {
                        resolvedScore = candidate;
                    }
                }
            }
            if (resolvedScore != null) {
                scoreResult.bestPoints.set(resolvedScore.getScore());
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.valueOf(resolvedScore.getCategory()));
                scoreResult.bestYear.set(resolvedScore.getYear());
            }
            if(forum != null && forum.getPublicationName().contains("Lecture Notes in ")) {
                // Special case for LNCS chapters
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
                scoreResult.bestYear.set(LAST_CORE_YEAR);
                trace = trace.withFallbackReason(FallbackReason.LNCS_SPECIAL_CASE);
            }
        }

        // Default scoring for SCOPUS-indexed conferences
        if (scoreResult.bestPoints.get() == 0 && forum != null) {
            scoreResult.bestPoints.set(1.0);
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
            scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            scoreResult.bestYear.set(LAST_CORE_YEAR);
            if (trace.fallbackReason() == FallbackReason.NONE) {
                trace = trace.withFallbackReason(FallbackReason.SCOPUS_FALLBACK);
            }
        }

        logTrace(trace, scoreResult.bestPoints.get(), scoreResult.bestYear.get(), scoreResult.bestCategory.get(), scoreResult.bestQuarter.get());
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
        ConferenceScoreTrace trace = ConferenceScoreTrace.forActivity(
                activity == null ? null : activity.getId(),
                forum.getPublicationName(),
                allowedYears
        );

        Score resolvedScore = null;
        for (int year : allowedYears) {
            ConferenceScoreResolution resolution = resolveConferenceScore(forum, year, trace);
            if (resolution.score().isPresent()) {
                Score candidate = resolution.score().get();
                if (resolvedScore == null || candidate.getScore() > resolvedScore.getScore()) {
                    resolvedScore = candidate;
                }
            }
            trace = resolution.trace();
        }
        if (resolvedScore != null) {
            scoreResult.bestPoints.set(resolvedScore.getScore());
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.valueOf(resolvedScore.getCategory()));
            scoreResult.bestYear.set(resolvedScore.getYear());
        }

        // Handle LNCS and SCOPUS cases similar to publication scoring
        if (scoreResult.bestPoints.get() == 0 && forum != null) {
            if (forum.getPublicationName().contains("Lecture Notes in ")) {
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
                trace = trace.withFallbackReason(FallbackReason.LNCS_SPECIAL_CASE);
            } else {
                scoreResult.bestPoints.set(1.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
                if (trace.fallbackReason() == FallbackReason.NONE) {
                    trace = trace.withFallbackReason(FallbackReason.SCOPUS_FALLBACK);
                }
            }
            scoreResult.bestYear.set(LAST_CORE_YEAR);
        }

        logTrace(trace, scoreResult.bestPoints.get(), scoreResult.bestYear.get(), scoreResult.bestCategory.get(), scoreResult.bestQuarter.get());
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  Conference-specific scoring logic                                 */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeCOREScore(Forum forum, int year) {
        return tryResolveCoreScore(forum, year);
    }

    public Optional<Score> tryResolveCoreScore(Forum forum, int year) {
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(null, null, null,
                forum == null ? null : forum.getPublicationName(), List.of(year));
        return resolveConferenceScore(forum, year, trace).score();
    }

    ConferenceScoreTrace diagnoseConferenceMatch(String publicationName, int year) {
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(null, null, null, publicationName, List.of(year));
        return resolveConferenceScore(publicationName == null ? null : forum(publicationName), year, trace).trace();
    }

    private ConferenceScoreResolution resolveConferenceScore(Forum forum, int year, ConferenceScoreTrace trace) {
        ConferenceMatch match = resolveConferenceMatch(forum == null ? null : forum.getPublicationName(), trace);
        trace = match.trace();
        if (!match.resolved()) {
            return new ConferenceScoreResolution(Optional.empty(), trace);
        }

        CoreConferenceRanking.YearlyRanking yearlyRank = match.ranking().getClosestYear(year);
        if (yearlyRank == null) {
            return new ConferenceScoreResolution(Optional.empty(), trace.withFallbackReason(FallbackReason.NO_CLOSEST_YEAR));
        }

        Score scoreResult = new Score();
        double score = switch (yearlyRank.getRank()) {
            case A_STAR -> 12.0;
            case A -> 8.0;
            case B -> 4.0;
            case C -> 2.0;
            default -> 1.0;
        };
        scoreResult.setScore(score);
        scoreResult.setCategory(getCategory(score).toString());
        scoreResult.setYear(year);
        return new ConferenceScoreResolution(Optional.of(scoreResult), trace.withResolvedYear(year, yearlyRank.getRank()));
    }

    private ConferenceMatch resolveConferenceMatch(String publicationName, ConferenceScoreTrace trace) {
        if (publicationName == null || publicationName.isBlank()) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(FallbackReason.NO_FORUM_NAME));
        }
        String normalizedPublicationName = normalizeVenueName(publicationName);
        if (normalizedPublicationName.isBlank()) {
            return ConferenceMatch.unresolved(trace.withNormalizedPublicationName(normalizedPublicationName)
                    .withFallbackReason(FallbackReason.NO_FORUM_NAME));
        }

        List<AcronymCandidate> acronymCandidates = extractAcronymCandidates(publicationName);
        trace = trace.withNormalizedPublicationName(normalizedPublicationName)
                .withAcronymCandidates(acronymCandidates.stream().map(AcronymCandidate::value).toList());
        if (acronymCandidates.isEmpty()) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(FallbackReason.NO_ACRONYM_CANDIDATES));
        }

        Map<CoreConferenceRanking, CandidateMatch> candidateConfidence = new LinkedHashMap<>();
        List<CandidateSummary> candidateSummaries = new ArrayList<>();
        for (AcronymCandidate acronymCandidate : acronymCandidates) {
            List<CoreConferenceRanking> confRankings = Optional.ofNullable(lookupPort.getConferenceRankings(acronymCandidate.value()))
                    .orElse(List.of());
            if (confRankings.isEmpty()) {
                candidateSummaries.add(CandidateSummary.unmatched(acronymCandidate.value()));
            }
            for (CoreConferenceRanking ranking : confRankings) {
                MatchConfidence confidence = scoreMatchConfidence(normalizedPublicationName, acronymCandidate.value(), ranking);
                candidateSummaries.add(CandidateSummary.of(acronymCandidate.value(), ranking, confidence));
                if (confidence == MatchConfidence.NONE) {
                    continue;
                }
                CandidateMatch existing = candidateConfidence.get(ranking);
                CandidateMatch proposed = new CandidateMatch(acronymCandidate, confidence);
                if (existing == null || proposed.outranks(existing)) {
                    candidateConfidence.put(ranking, proposed);
                }
            }
        }
        trace = trace.withCandidateSummaries(candidateSummaries);

        if (candidateConfidence.isEmpty()) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(FallbackReason.NO_CORE_CANDIDATES));
        }

        int bestScore = candidateConfidence.values().stream()
                .mapToInt(CandidateMatch::effectiveScore)
                .max()
                .orElse(MatchConfidence.NONE.score);
        if (bestScore < MatchConfidence.NORMALIZED_CONTAINS.score) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(FallbackReason.BELOW_THRESHOLD));
        }

        List<CoreConferenceRanking> winners = candidateConfidence.entrySet().stream()
                .filter(entry -> entry.getValue().effectiveScore() == bestScore)
                .map(Map.Entry::getKey)
                .toList();
        if (winners.size() != 1) {
            MatchConfidence topConfidence = candidateConfidence.values().stream()
                    .map(CandidateMatch::confidence)
                    .max(Comparator.comparingInt(value -> value.score))
                    .orElse(MatchConfidence.NONE);
            return ConferenceMatch.unresolved(trace.withWinnerConfidence(topConfidence)
                    .withFallbackReason(FallbackReason.AMBIGUOUS_WINNERS));
        }

        CoreConferenceRanking winner = winners.getFirst();
        CandidateMatch winnerMatch = candidateConfidence.get(winner);
        trace = trace.withResolvedConference(winner.getId(), winner.getName(), winner.getAcronym(), winnerMatch.confidence());
        return new ConferenceMatch(true, winner, trace);
    }

    private List<AcronymCandidate> extractAcronymCandidates(String publicationName) {
        Map<String, AcronymCandidate> candidates = new LinkedHashMap<>();
        String[] fragments = publicationName.split(",");
        for (int fragmentIndex = 0; fragmentIndex < fragments.length; fragmentIndex++) {
            String fragment = fragments[fragmentIndex];
            String trimmed = fragment == null ? "" : fragment.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            AcronymSource source = fragmentIndex == fragments.length - 1
                    ? AcronymSource.LAST_COMMA_FRAGMENT
                    : AcronymSource.GENERAL;
            String[] tokens = trimmed.split("\\s+");
            for (String token : tokens) {
                String normalized = normalizeAcronymToken(token);
                if (isLikelyAcronymToken(token, normalized)) {
                    registerCandidate(candidates, normalized, source);
                }
            }
            if (tokens.length > 0) {
                String leading = normalizeAcronymToken(tokens[0]);
                if (isLikelyAcronymToken(tokens[0], leading)) {
                    registerCandidate(candidates, leading, source);
                }
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private void registerCandidate(Map<String, AcronymCandidate> candidates, String normalized, AcronymSource source) {
        AcronymCandidate existing = candidates.get(normalized);
        AcronymCandidate proposed = new AcronymCandidate(normalized, source);
        if (existing == null || proposed.source().priority > existing.source().priority) {
            candidates.put(normalized, proposed);
        }
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

    private boolean isLikelyAcronymToken(String originalToken, String normalizedToken) {
        if (normalizedToken == null || normalizedToken.length() < 2) {
            return false;
        }
        if (normalizedToken.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (ORDINAL_TOKEN.matcher(normalizedToken).matches()) {
            return false;
        }
        if (ACRONYM_STOPWORDS.contains(normalizedToken)) {
            return false;
        }
        if (normalizedToken.length() > 10) {
            return false;
        }
        if (originalToken == null || originalToken.isBlank()) {
            return false;
        }
        boolean hasLowercase = originalToken.chars().anyMatch(Character::isLowerCase);
        boolean hasUppercase = originalToken.chars().anyMatch(Character::isUpperCase);
        if (hasUppercase && !hasLowercase) {
            return true;
        }
        if (hasUppercase && hasLowercase) {
            int uppercaseCount = (int) originalToken.chars().filter(Character::isUpperCase).count();
            return uppercaseCount >= 2;
        }
        return false;
    }

    private MatchConfidence scoreMatchConfidence(String normalizedPublicationName, String acronymCandidate, CoreConferenceRanking ranking) {
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
        if (isExactAcronymMatch(acronymCandidate, ranking)
                && hasStrongTokenOverlap(publicationTokens, rankingTokens)) {
            return MatchConfidence.EXACT_ACRONYM_STRONG_TOKEN_OVERLAP;
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
                .map(this::normalizeComparableToken)
                .filter(token -> token.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isExactAcronymMatch(String acronymCandidate, CoreConferenceRanking ranking) {
        return acronymCandidate != null
                && ranking != null
                && ranking.getAcronym() != null
                && acronymCandidate.equalsIgnoreCase(ranking.getAcronym());
    }

    private boolean hasStrongTokenOverlap(Set<String> publicationTokens, Set<String> rankingTokens) {
        if (publicationTokens.isEmpty() || rankingTokens.isEmpty()) {
            return false;
        }
        Set<String> overlap = new LinkedHashSet<>(publicationTokens);
        overlap.retainAll(rankingTokens);
        if (overlap.isEmpty()) {
            return false;
        }
        int overlapCount = overlap.size();
        int rankingSize = rankingTokens.size();
        int publicationSize = publicationTokens.size();
        return overlapCount >= Math.min(3, rankingSize)
                && overlapCount * 1.0 / rankingSize >= 0.75
                && overlapCount * 1.0 / publicationSize >= 0.5;
    }

    private String normalizeComparableToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() <= 3) {
            return normalized;
        }
        if (normalized.endsWith("ies") && normalized.length() > 4) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("es")
                && normalized.length() > 4
                && !normalized.endsWith("ses")
                && !normalized.endsWith("xes")
                && !normalized.endsWith("zes")
                && !normalized.endsWith("ches")
                && !normalized.endsWith("shes")) {
            return normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.endsWith("s")
                && !normalized.endsWith("ss")
                && !normalized.endsWith("us")
                && !normalized.endsWith("is")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void logTrace(ConferenceScoreTrace trace, Double points, Integer year, CoreConferenceRanking.Rank category, WoSRanking.Quarter quarter) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("CS_CONFERENCE_TRACE context={} itemId={} subtype={} coverDate={} forumTitle={} normalizedTitle={} allowedYears={} acronymCandidates={} candidateSummaries={} resolvedConferenceId={} resolvedConferenceName={} resolvedAcronym={} winnerConfidence={} resolvedYear={} resolvedRank={} fallbackReason={} finalPoints={} finalYear={} finalCategory={} finalQuarter={}",
                trace.context(),
                trace.itemId(),
                trace.subtype(),
                trace.coverDate(),
                trace.forumTitle(),
                trace.normalizedPublicationName(),
                trace.allowedYears(),
                trace.acronymCandidates(),
                trace.candidateSummaries(),
                trace.resolvedConferenceId(),
                trace.resolvedConferenceName(),
                trace.resolvedAcronym(),
                trace.winnerConfidence(),
                trace.resolvedYear(),
                trace.resolvedRank(),
                trace.fallbackReason(),
                points,
                year,
                category,
                quarter);
    }

    private Forum forum(String publicationName) {
        Forum forum = new Forum();
        forum.setPublicationName(publicationName);
        return forum;
    }

    private record ConferenceMatch(boolean resolved, CoreConferenceRanking ranking, ConferenceScoreTrace trace) {
        static ConferenceMatch unresolved(ConferenceScoreTrace trace) {
            return new ConferenceMatch(false, null, trace);
        }
    }

    private record CandidateMatch(AcronymCandidate acronymCandidate, MatchConfidence confidence) {
        int effectiveScore() {
            return confidence.score + acronymCandidate.source().priority;
        }

        boolean outranks(CandidateMatch other) {
            if (effectiveScore() != other.effectiveScore()) {
                return effectiveScore() > other.effectiveScore();
            }
            return confidence.score > other.confidence.score;
        }
    }

    private record AcronymCandidate(String value, AcronymSource source) {
    }

    private enum AcronymSource {
        GENERAL(0),
        LAST_COMMA_FRAGMENT(2);

        private final int priority;

        AcronymSource(int priority) {
            this.priority = priority;
        }
    }

    private record ConferenceScoreResolution(Optional<Score> score, ConferenceScoreTrace trace) {
    }

    enum FallbackReason {
        NONE,
        NO_FORUM_NAME,
        NO_ACRONYM_CANDIDATES,
        NO_CORE_CANDIDATES,
        BELOW_THRESHOLD,
        AMBIGUOUS_WINNERS,
        NO_CLOSEST_YEAR,
        LNCS_SPECIAL_CASE,
        SCOPUS_FALLBACK
    }

    record CandidateSummary(String acronymCandidate, String rankingId, String rankingName, String rankingAcronym, MatchConfidence confidence) {
        static CandidateSummary of(String acronymCandidate, CoreConferenceRanking ranking, MatchConfidence confidence) {
            return new CandidateSummary(acronymCandidate, ranking.getId(), ranking.getName(), ranking.getAcronym(), confidence);
        }

        static CandidateSummary unmatched(String acronymCandidate) {
            return new CandidateSummary(acronymCandidate, null, null, null, MatchConfidence.NONE);
        }
    }

    record ConferenceScoreTrace(
            String context,
            String itemId,
            String subtype,
            String coverDate,
            String forumTitle,
            String normalizedPublicationName,
            List<Integer> allowedYears,
            List<String> acronymCandidates,
            List<CandidateSummary> candidateSummaries,
            String resolvedConferenceId,
            String resolvedConferenceName,
            String resolvedAcronym,
            MatchConfidence winnerConfidence,
            Integer resolvedYear,
            CoreConferenceRanking.Rank resolvedRank,
            FallbackReason fallbackReason
    ) {
        static ConferenceScoreTrace forPublication(String itemId, String subtype, String coverDate, String forumTitle, List<Integer> allowedYears) {
            return new ConferenceScoreTrace("publication", itemId, subtype, coverDate, forumTitle, null, List.copyOf(allowedYears), List.of(), List.of(), null, null, null, MatchConfidence.NONE, null, null, FallbackReason.NONE);
        }

        static ConferenceScoreTrace forActivity(String itemId, String forumTitle, List<Integer> allowedYears) {
            return new ConferenceScoreTrace("activity", itemId, null, null, forumTitle, null, List.copyOf(allowedYears), List.of(), List.of(), null, null, null, MatchConfidence.NONE, null, null, FallbackReason.NONE);
        }

        ConferenceScoreTrace withNormalizedPublicationName(String value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, value, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason);
        }

        ConferenceScoreTrace withAcronymCandidates(List<String> values) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, List.copyOf(values), candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason);
        }

        ConferenceScoreTrace withCandidateSummaries(List<CandidateSummary> values) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, List.copyOf(values), resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason);
        }

        ConferenceScoreTrace withResolvedConference(String id, String name, String acronym, MatchConfidence confidence) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, id, name, acronym, confidence, resolvedYear, resolvedRank, fallbackReason);
        }

        ConferenceScoreTrace withResolvedYear(Integer year, CoreConferenceRanking.Rank rank) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, year, rank, fallbackReason);
        }

        ConferenceScoreTrace withWinnerConfidence(MatchConfidence confidence) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, confidence, resolvedYear, resolvedRank, fallbackReason);
        }

        ConferenceScoreTrace withFallbackReason(FallbackReason reason) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, reason);
        }
    }

    private enum MatchConfidence {
        NONE(0),
        NORMALIZED_CONTAINS(1),
        TOKEN_SUPERSET(2),
        TOKEN_SET_EQUAL(3),
        EXACT_ACRONYM_STRONG_TOKEN_OVERLAP(4),
        NORMALIZED_NAME_WITHOUT_BOILERPLATE(4),
        EXACT_NORMALIZED_NAME(5);

        private final int score;

        MatchConfidence(int score) {
            this.score = score;
        }

        static MatchConfidence fromScore(int score) {
            for (MatchConfidence value : values()) {
                if (value.score == score) {
                    return value;
                }
            }
            return NONE;
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
