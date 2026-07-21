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
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoreYearRangeSpec;

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
    private static final Pattern WORKSHOP_TOKEN = Pattern.compile("\\bworkshops?\\b", Pattern.CASE_INSENSITIVE);
    // H80/C1: a poster or system-demonstration track item. Scopus (`cp`) and OpenAlex (`article`) both classify these
    // as ordinary conference papers — there is NO structured signal — so the only discriminator is a self-labelling
    // title prefix ("Poster:", "Demo:", "Demonstration:", or the bare word). Strict prefix only: the loose word matches
    // real research ("Demonstration of quantum synchronization…", "Laser demonstration…") and must be avoided.
    private static final Pattern POSTER_DEMO_TITLE = Pattern.compile(
            "^\\s*(poster|demo|demonstration)\\s*([:\\-\\u2013\\u2014]|$)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BOILERPLATE_TOKENS = Set.of(
            "proceedings", "of", "the", "international", "conference", "symposium", "workshop",
            "on", "for", "and", "in", "annual", "ieee", "acm", "ifip", "euromicro", "joint"
    );
    private static final Set<String> ACRONYM_STOPWORDS = Set.of(
            "PROCEEDINGS", "INTERNATIONAL", "SYMPOSIUM", "CONFERENCE", "WORKSHOP",
            "COMPUTING", "COMPUTER", "SOFTWARE", "APPLICATIONS", "ANNUAL",
            "ON", "AND", "OF", "THE", "FOR", "IN"
    );
    private static final Set<String> INITIALISM_IGNORED_TOKENS = Set.of(
            "proceedings", "of", "the", "on", "and", "for", "in",
            "ieee", "acm", "ifip", "euromicro", "joint", "annual"
    );
    private ConferenceScoreTrace lastTraceForTests;

    @Autowired
    public ComputerScienceConferenceScoringService(
            ReportingLookupPort lookupPort,
            ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository
    ) {
        super(lookupPort);
        this.dblpEvidenceRepository = dblpEvidenceRepository;
    }

    ComputerScienceConferenceScoringService(ReportingLookupPort lookupPort) {
        this(lookupPort, null);
    }

    private final ScholardexPublicationDblpEvidenceRepository dblpEvidenceRepository;

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        if (PredatoryVenueSupport.isExcludedVenue(forum)) {
            Score excluded = createScore(initializeScoreResult()); // standard-excluded venue (WSEAS/IAENG/DAAAM): no points
            excluded.getScoringInfo().put("zeroReason", "EXCLUDED_VENUE");
            return excluded;
        }

        ScoreResult scoreResult = initializeScoreResult();
        List<Integer> allowedYears = getAllowedYearsForPublication(publication, indicator);
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(
                publication == null ? null : publication.getId(),
                publication == null ? null : PublicationSubtypeSupport.resolveSubtype(publication),
                publication == null ? null : publication.getCoverDate(),
                forum == null ? null : forum.getPublicationName(),
                allowedYears
        );

        boolean lncsBookSeriesCandidate = isLncsBookSeriesCandidate(publication, forum);
        // A paper published in a conference-proceeding forum is a conference contribution even when its
        // subtype is the generic "ar" (OpenAlex labels many proceedings papers as plain articles).
        boolean conferenceCandidate = PublicationSubtypeSupport.isSubtype(publication, "cp")
                || isConferenceProceeding(forum)
                || lncsBookSeriesCandidate;
        boolean workshop2026 = indicator != null && indicator.usesWorkshop2026Categories();
        boolean posterOrDemo = publication != null && isPosterOrDemo(publication.getTitle());
        if (conferenceCandidate) {
            Score resolvedScore = null;
            for (int year : allowedYears) {
                ConferenceScoreResolution resolution = resolveConferenceScore(publication, forum, year, trace, workshop2026, posterOrDemo);
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
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.valueOf(resolvedScore.getCoreRankingEquivalent()));
                scoreResult.bestYear.set(resolvedScore.getYear());
                // Conferences have no JCR quartile; the rank is the CORE class (shown separately). Mark the quartile
                // slot as CORE rather than leaving the NOT_FOUND default, which reads like missing data in the UI.
                scoreResult.bestQuarter.set(WoSRanking.Quarter.CORE);
                copyProvenance(resolvedScore, scoreResult);
            }
            if(scoreResult.bestPoints.get() == 0
                    && (isLncsProceedingsForum(forum) || DoiVenueSupport.isSpringerBookSeriesProceedings(publication))) {
                // Special case for LNCS chapters
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
                scoreResult.bestYear.set(LAST_CORE_YEAR);
                trace = trace.withFallbackReason(FallbackReason.LNCS_SPECIAL_CASE);
                applyTraceProvenance(scoreResult, trace, "SCOPUS");
            }
        }

        // Default D-tier ONLY for a positively-typed Conference-Proceeding forum. A "cp" paper in a Journal forum
        // is a journal special issue (scored by CS_JOURNAL — must not double-count here), and a "cp" in an
        // untyped/unknown forum is an unidentifiable venue that earns nothing (a D would be unverifiable points).
        // DBLP-known conferences are already re-stamped onto conf/X (Conference-Proceeding) forums by the dump
        // sweep; LNCS book-series candidates take the LNCS C fallback above, not this D.
        if (scoreResult.bestPoints.get() == 0 && isConferenceProceeding(forum)) {
            scoreResult.bestPoints.set(1.0);
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
            scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
            scoreResult.bestYear.set(LAST_CORE_YEAR);
            if (trace.fallbackReason() == FallbackReason.NONE) {
                trace = trace.withFallbackReason(FallbackReason.SCOPUS_FALLBACK);
            }
            applyTraceProvenance(scoreResult, trace, "SCOPUS");
        }

        logTrace(trace, scoreResult.bestPoints.get(), scoreResult.bestYear.get(), scoreResult.bestCategory.get(), scoreResult.bestQuarter.get());
        Score score = createScore(scoreResult);
        if (!conferenceCandidate) {
            // Not a conference contribution at all (journal article, book chapter, …): this indicator
            // does not cover it — the matching journal/book indicator does. Candidate zeros (a real
            // conference paper that simply resolved no rank) deliberately stay unstamped.
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
        }
        return score;
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        Domain domain = indicator.getDomain();
        ScholardexForumView forum = getForumFromActivity(activity);

        ScoreResult scoreResult = initializeScoreResult();
        if(forum.getPublicationName() == null) {
            return createScore(scoreResult);
        }
        List<Integer> allowedYears = 
                indicator.getEffectiveScoreYearRange().allowedYears(activity.getYear());
        ConferenceScoreTrace trace = ConferenceScoreTrace.forActivity(
                activity == null ? null : activity.getId(),
                forum.getPublicationName(),
                allowedYears
        );

        boolean workshop2026 = indicator != null && indicator.usesWorkshop2026Categories();
        Score resolvedScore = null;
        for (int year : allowedYears) {
            ConferenceScoreResolution resolution = resolveConferenceScore(forum, year, trace, workshop2026);
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
            scoreResult.bestCategory.set(CoreConferenceRanking.Rank.valueOf(resolvedScore.getCoreRankingEquivalent()));
            scoreResult.bestYear.set(resolvedScore.getYear());
            scoreResult.bestQuarter.set(WoSRanking.Quarter.CORE);
            copyProvenance(resolvedScore, scoreResult);
        }

        // Handle LNCS and SCOPUS cases similar to publication scoring
        if (scoreResult.bestPoints.get() == 0 && forum != null) {
            if (isLncsProceedingsForum(forum)) {
                scoreResult.bestPoints.set(2.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.C);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.LNCS);
                trace = trace.withFallbackReason(FallbackReason.LNCS_SPECIAL_CASE);
                applyTraceProvenance(scoreResult, trace, "SCOPUS");
            } else {
                scoreResult.bestPoints.set(1.0);
                scoreResult.bestCategory.set(CoreConferenceRanking.Rank.D);
                scoreResult.bestQuarter.set(WoSRanking.Quarter.SCOPUS);
                if (trace.fallbackReason() == FallbackReason.NONE) {
                    trace = trace.withFallbackReason(FallbackReason.SCOPUS_FALLBACK);
                }
                applyTraceProvenance(scoreResult, trace, "SCOPUS");
            }
            scoreResult.bestYear.set(LAST_CORE_YEAR);
        }

        logTrace(trace, scoreResult.bestPoints.get(), scoreResult.bestYear.get(), scoreResult.bestCategory.get(), scoreResult.bestQuarter.get());
        return createScore(scoreResult);
    }

    /* ------------------------------------------------------------------ */
    /*  Conference-specific scoring logic                                 */
    /* ------------------------------------------------------------------ */

    private Optional<Score> computeCOREScore(ScholardexForumView forum, int year) {
        return tryResolveCoreScore(forum, year);
    }

    public Optional<Score> tryResolveCoreScore(ScholardexForumView forum, int year) {
        return tryResolveCoreScore(null, forum, year);
    }

    public Optional<Score> tryResolveCoreScore(ScoringPublicationReadModel publication, ScholardexForumView forum, int year) {
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(null, null, null,
                forum == null ? null : forum.getPublicationName(), List.of(year));
        return resolveConferenceScore(publication, forum, year, trace).score();
    }

    public Optional<CoreConferenceRanking> matchByForumName(String publicationName) {
        if (publicationName == null || publicationName.isBlank()) {
            return Optional.empty();
        }
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(null, null, null, publicationName, List.of());
        ConferenceMatch match = resolveConferenceMatch(publicationName, trace);
        return match.resolved() ? Optional.ofNullable(match.ranking()) : Optional.empty();
    }

    ConferenceScoreTrace diagnoseConferenceMatch(String publicationName, int year) {
        ConferenceScoreTrace trace = ConferenceScoreTrace.forPublication(null, null, null, publicationName, List.of(year));
        return resolveConferenceScore(publicationName == null ? null : forum(publicationName), year, trace).trace();
    }

    ConferenceScoreTrace getLastTraceForTests() {
        return lastTraceForTests;
    }

    private ConferenceScoreResolution resolveConferenceScore(ScholardexForumView forum, int year, ConferenceScoreTrace trace) {
        return resolveConferenceScore(forum, year, trace, false);
    }

    private ConferenceScoreResolution resolveConferenceScore(ScholardexForumView forum, int year, ConferenceScoreTrace trace, boolean workshop2026) {
        ConferenceMatch match = resolveConferenceMatch(forum == null ? null : forum.getPublicationName(), trace);
        trace = match.trace();
        return scoreResolvedConference(match, year, trace, ResolutionSource.SCOPUS, workshop2026);
    }

    private ConferenceScoreResolution resolveConferenceScore(ScoringPublicationReadModel publication, ScholardexForumView forum, int year, ConferenceScoreTrace trace) {
        return resolveConferenceScore(publication, forum, year, trace, false, false);
    }

    private ConferenceScoreResolution resolveConferenceScore(ScoringPublicationReadModel publication, ScholardexForumView forum, int year, ConferenceScoreTrace trace, boolean workshop2026, boolean posterOrDemo) {
        // A DBLP-evidenced paper is matched — and workshop-detected — from its OWN booktitle-derived title
        // before the shared stream-forum name is even consulted. The forum name is whichever volume title the
        // stream forum was minted from, and since ~2021 several conferences (e.g. AINA) publish workshop and
        // main-track papers in one merged proceedings, so a stream-level name cannot classify a single paper:
        // absent per-paper proof of workshop status, the paper's own record ("AINA (5)") is the default truth.
        if (shouldConsultDblp(publication, forum)) {
            Optional<ScholardexPublicationDblpEvidence> evidence = findDblpEvidence(publication);
            if (evidence.isPresent()) {
                String dblpConferenceTitle = resolveDblpConferenceTitle(evidence.get());
                trace = trace.withDblpConsulted(true).withDblpEvidenceFound(true).withDblpConferenceTitle(dblpConferenceTitle);
                if (dblpConferenceTitle != null && !dblpConferenceTitle.isBlank()) {
                    ConferenceMatch dblpMatch = resolveConferenceMatch(dblpConferenceTitle, trace);
                    trace = dblpMatch.trace();
                    ConferenceScoreResolution dblpResolution = scoreResolvedConference(dblpMatch, year, trace, ResolutionSource.DBLP, workshop2026, posterOrDemo);
                    if (dblpResolution.score().isPresent()) {
                        return dblpResolution;
                    }
                    trace = dblpResolution.trace();
                }
            }
        }

        ConferenceMatch match = resolveConferenceMatch(forum == null ? null : forum.getPublicationName(), trace);
        trace = match.trace();
        ConferenceScoreResolution scopusResolution = scoreResolvedConference(match, year, trace, ResolutionSource.SCOPUS, workshop2026, posterOrDemo);
        if (scopusResolution.score().isPresent()) {
            return scopusResolution;
        }

        if (!shouldConsultDblp(publication, forum)) {
            return new ConferenceScoreResolution(Optional.empty(), trace);
        }
        if (!trace.dblpConsulted()) {
            trace = trace.withDblpConsulted(true).withDblpEvidenceFound(false);
        }
        return new ConferenceScoreResolution(Optional.empty(), trace);
    }

    private ConferenceScoreResolution scoreResolvedConference(ConferenceMatch match, int year, ConferenceScoreTrace trace, ResolutionSource resolutionSource, boolean workshop2026) {
        return scoreResolvedConference(match, year, trace, resolutionSource, workshop2026, false);
    }

    private ConferenceScoreResolution scoreResolvedConference(ConferenceMatch match, int year, ConferenceScoreTrace trace, ResolutionSource resolutionSource, boolean workshop2026, boolean posterOrDemo) {
        if (!match.resolved()) {
            return new ConferenceScoreResolution(Optional.empty(), trace);
        }
        CoreConferenceRanking.YearlyRanking yearlyRank = match.ranking().getClosestYear(year);
        if (yearlyRank == null) {
            return new ConferenceScoreResolution(Optional.empty(), trace.withFallbackReason(FallbackReason.NO_CLOSEST_YEAR));
        }
        Score scoreResult = new Score();
        CoreConferenceRanking.Rank parentRank = yearlyRank.getRank();
        // H80/A81: a CORE national/regional conference is category C under Informatică-2026, but was D under 2016.
        // Gated on the same per-indicator 2026 flag as the workshop relabel so FV Info 2016 stays frozen. Applied to
        // the parent rank so all downstream logic (points, workshop/poster reduction, category label) follows.
        if (parentRank == CoreConferenceRanking.Rank.National || parentRank == CoreConferenceRanking.Rank.National_Regional) {
            parentRank = workshop2026 ? CoreConferenceRanking.Rank.C : CoreConferenceRanking.Rank.D;
        }
        boolean workshopAdjusted = isWorkshopVariant(match.sourceTitle(), match.ranking());
        // H80/C1: posters + system demos of a conference get the SAME reduction as workshops (id_parA82), but that clause
        // is a 2026 rule with no 2016 counterpart we can verify — so posters reduce ONLY under 2026 indicators, keeping
        // FV Info 2016 frozen. Workshops reduce under both (their one-lower vs flat-C mapping is the only 2016↔2026 diff).
        boolean reduced = workshopAdjusted || (posterOrDemo && workshop2026);
        // Category rule. The POINT ladder is identical across standards (A*→6, A→4, B→2, C/D→1); only the reported
        // category differs. Informatica-2016: one category lower (A*→A, A→B, B→C, C→D). Informatica-2026 (id_parA82):
        // reduced items of A*/A/B conferences are category C, of C conferences category D. Poster/demo reduction only
        // ever hits the 2026 branch (gated above).
        CoreConferenceRanking.Rank effectiveRank = !reduced ? parentRank
                : (workshop2026 ? workshop2026DowngradedRank(parentRank) : workshopDowngradedRank(parentRank));
        double score = reduced ? workshopPoints(parentRank) : coreConferencePoints(parentRank);
        scoreResult.setScore(score);
        scoreResult.setCoreRankingEquivalent(effectiveRank.toString());
        scoreResult.setYear(year);
        setProvenance(scoreResult, scoringSourceLabel(resolutionSource, reduced),
                buildScoringInfo(trace, resolutionSource, year, effectiveRank, reduced));
        return new ConferenceScoreResolution(Optional.of(scoreResult),
                trace.withResolvedYear(year, effectiveRank)
                        .withWorkshopAdjusted(reduced)
                        .withResolvedSource(resolutionSource)
                        .withFallbackReason(FallbackReason.NONE));
    }

    /** Base CORE conference points (CNATDCU/INFO): A*=12, A=8, B=4, C=2, D and anything else = 1. */
    private static double coreConferencePoints(CoreConferenceRanking.Rank rank) {
        return switch (rank) {
            case A_STAR -> 12.0;
            case A -> 8.0;
            case B -> 4.0;
            case C -> 2.0;
            default -> 1.0;
        };
    }

    /** Workshop one-category downgrade (Informatica-2016): A*→A, A→B, B→C, C→D, D (and anything else) → D. */
    private static CoreConferenceRanking.Rank workshopDowngradedRank(CoreConferenceRanking.Rank parent) {
        return switch (parent) {
            case A_STAR -> CoreConferenceRanking.Rank.A;
            case A -> CoreConferenceRanking.Rank.B;
            case B -> CoreConferenceRanking.Rank.C;
            default -> CoreConferenceRanking.Rank.D; // C, D, … → D
        };
    }

    /** Workshop category mapping (Informatica-2026, id_parA82): A*, A, B all &rarr; C; C (and anything else) &rarr; D. */
    private static CoreConferenceRanking.Rank workshop2026DowngradedRank(CoreConferenceRanking.Rank parent) {
        return switch (parent) {
            case A_STAR, A, B -> CoreConferenceRanking.Rank.C;
            default -> CoreConferenceRanking.Rank.D; // C, D, … → D
        };
    }

    /** Standard workshop point ladder keyed by the PARENT conference category: A*→6, A→4, B→2, C/D→1. */
    private static double workshopPoints(CoreConferenceRanking.Rank parent) {
        return switch (parent) {
            case A_STAR -> 6.0;
            case A -> 4.0;
            case B -> 2.0;
            default -> 1.0; // C, D, … → 1
        };
    }

    private void applyTraceProvenance(ScoreResult scoreResult, ConferenceScoreTrace trace, String scoringSource) {
        scoreResult.scoringSource.set(scoringSource);
        scoreResult.scoringInfo.clear();
        scoreResult.scoringInfo.putAll(buildScoringInfo(trace, ResolutionSource.NONE, scoreResult.bestYear.get(), null, trace.workshopAdjusted()));
    }

    private String scoringSourceLabel(ResolutionSource resolutionSource, boolean workshopAdjusted) {
        return switch (resolutionSource) {
            case SCOPUS -> workshopAdjusted ? "SCOPUS+CORE(WS)" : "SCOPUS+CORE";
            case DBLP -> workshopAdjusted ? "DBLP+CORE(WS)" : "DBLP+CORE";
            case NONE -> null;
        };
    }

    private Map<String, Object> buildScoringInfo(ConferenceScoreTrace trace,
                                                 ResolutionSource resolutionSource,
                                                 Integer resolvedYear,
                                                 CoreConferenceRanking.Rank resolvedRank,
                                                 boolean workshopAdjusted) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (resolutionSource == ResolutionSource.SCOPUS) {
            info.put("matchSource", "SCOPUS");
        } else if (resolutionSource == ResolutionSource.DBLP) {
            info.put("matchSource", "DBLP");
        } else if (trace.forumTitle() != null) {
            info.put("matchSource", "SCOPUS");
        }
        if (trace.resolvedConferenceName() != null) {
            info.put("matchedTitle", trace.resolvedConferenceName());
        } else if (resolutionSource == ResolutionSource.DBLP && trace.dblpConferenceTitle() != null) {
            info.put("matchedTitle", trace.dblpConferenceTitle());
        } else if (trace.forumTitle() != null) {
            info.put("matchedTitle", trace.forumTitle());
        }
        if (trace.resolvedAcronym() != null) {
            info.put("matchedAcronym", trace.resolvedAcronym());
        }
        if (trace.resolvedConferenceId() != null) {
            info.put("matchedConferenceId", trace.resolvedConferenceId());
        }
        if (trace.resolvedLookupStrategy() != null && trace.resolvedLookupStrategy() != CoreLookupStrategy.NONE) {
            info.put("coreLookupStrategy", trace.resolvedLookupStrategy().name());
        }
        if (workshopAdjusted) {
            info.put("workshopAdjusted", true);
        }
        if (resolvedYear != null && resolvedYear > 0) {
            info.put("resolvedYear", resolvedYear);
        }
        if (resolvedRank != null) {
            info.put("resolvedRank", resolvedRank.name());
        }
        if (trace.fallbackReason() != null && trace.fallbackReason() != FallbackReason.NONE) {
            info.put("fallbackReason", trace.fallbackReason().name());
        }
        List<String> sourcesConsulted = new ArrayList<>();
        if (trace.forumTitle() != null) {
            sourcesConsulted.add("SCOPUS");
        }
        if (trace.dblpConsulted()) {
            sourcesConsulted.add("DBLP");
        }
        if (resolutionSource == ResolutionSource.SCOPUS || resolutionSource == ResolutionSource.DBLP || trace.resolvedConferenceId() != null) {
            sourcesConsulted.add("CORE");
        }
        if (!sourcesConsulted.isEmpty()) {
            info.put("sourcesConsulted", sourcesConsulted);
        }
        return info;
    }

    private boolean isWorkshopVariant(String sourceTitle, CoreConferenceRanking ranking) {
        if (sourceTitle == null || ranking == null || ranking.getName() == null) {
            return false;
        }
        return containsWorkshopToken(sourceTitle) && !containsWorkshopToken(ranking.getName());
    }

    private boolean containsWorkshopToken(String value) {
        return value != null && WORKSHOP_TOKEN.matcher(value).find();
    }

    /**
     * H80/C1: is this publication a poster or system-demonstration track item, self-labelled by its title prefix?
     * High-precision by design (strict prefix "Poster:"/"Demo:"/"Demonstration:" or the bare word) — the only signal
     * available, since neither Scopus (`cp`) nor OpenAlex (`article`) distinguishes these from full conference papers.
     */
    private static boolean isPosterOrDemo(String title) {
        return title != null && POSTER_DEMO_TITLE.matcher(title).find();
    }

    private boolean shouldConsultDblp(ScoringPublicationReadModel publication, ScholardexForumView forum) {
        // DBLP's conf/X series is authoritative for conference identity, so consult it for ANY conference
        // proceedings paper whose forum-name resolution missed — not only LNCS book-series. The LNCS
        // "Lecture Notes in …" handling is a separate book-chapter point fallback, not the DBLP gate.
        return isConferenceProceeding(forum)
                || PublicationSubtypeSupport.isSubtype(publication, "cp")
                || isLncsBookSeriesCandidate(publication, forum);
    }

    private boolean isConferenceProceeding(ScholardexForumView forum) {
        return forum != null && forum.hasAggregationType("Conference Proceeding");
    }

    /**
     * Broad "Lecture Notes …" gate ("Lecture Notes in …" or "Lecture Notes on …"), case-insensitive
     * because the source data is inconsistent about capitalisation ("in" vs "In"). Used to admit these
     * book-series papers as conference candidates; the LNCS C fallback is narrower ({@link #isLncsProceedingsForum}).
     */
    private boolean isLectureNotesSeries(ScholardexForumView forum) {
        String name = forum == null ? null : forum.getPublicationName();
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("lecture notes in ") || normalized.contains("lecture notes on ");
    }

    /**
     * LNCS-family Springer proceedings proper — "Lecture Notes in …" (LNCS, LNAI = "Lecture Notes in
     * Artificial Intelligence", LNBIP), case-insensitive. Only these earn the LNCS C fallback; the rarer
     * "Lecture Notes on …" stays a candidate but falls through to the SCOPUS D tier.
     */
    private boolean isLncsProceedingsForum(ScholardexForumView forum) {
        String name = forum == null ? null : forum.getPublicationName();
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("lecture notes in ");
    }

    private boolean isLncsBookSeriesCandidate(ScoringPublicationReadModel publication, ScholardexForumView forum) {
        boolean lncsForum = isLectureNotesSeries(forum);
        // A "cp" paper with a Springer ISBN DOI (10.1007/978…) is a conference proceeding even when the forum
        // name/aggregationType is wrong — the DOI survives. A "ch" paper, however, needs the LNCS-series forum
        // NAME: a Springer-978 DOI alone is ALSO a real Springer/Palgrave BOOK chapter (e.g. "Studies in Big
        // Data", "Palgrave Studies in Digital Business"), which is scored by CS_SENSE as a book and must not leak
        // into the conference indicator. DBLP-resolved LNCS conferences already carry a Conference-Proceeding
        // forum (handled by isConferenceProceeding), so the residual ch path only needs the LNCS forum name.
        if (PublicationSubtypeSupport.isSubtype(publication, "cp")) {
            return lncsForum || DoiVenueSupport.isSpringerBookSeriesProceedings(publication);
        }
        if (PublicationSubtypeSupport.isSubtype(publication, "ch")) {
            return lncsForum;
        }
        return false;
    }

    private Optional<ScholardexPublicationDblpEvidence> findDblpEvidence(ScoringPublicationReadModel publication) {
        if (publication == null || publication.getId() == null || dblpEvidenceRepository == null) {
            return Optional.empty();
        }
        return dblpEvidenceRepository.findByPublicationId(publication.getId());
    }

    private String resolveDblpConferenceTitle(ScholardexPublicationDblpEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        String title = null;
        if (evidence.getConferenceName() != null && !evidence.getConferenceName().isBlank()) {
            title = evidence.getConferenceName().trim();
        } else if (evidence.getBooktitle() != null && !evidence.getBooktitle().isBlank()) {
            title = evidence.getBooktitle().trim();
        }
        // H66B Phase 4b: lead with DBLP's authoritative conference acronym (the conf/X stream key, e.g. conf/iccs ->
        // ICCS). DBLP identifies the acronym precisely where our title-heuristic struggles; seeding it as the first
        // token makes the acronym a top match candidate, while the booktitle stays as fallback context.
        String acronym = dblpStreamAcronym(evidence.getSeries());
        if (acronym == null) {
            return title;
        }
        if (title == null) {
            return acronym;
        }
        // DBLP conferenceNames usually ALREADY lead with the acronym ("AINA (6)") — prepending again
        // produces "AINA AINA (6)", which defeats the decorated-acronym-only confidence check and
        // demoted real CORE conferences to the LNCS fallback. Only seed the acronym when absent.
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        String normalizedAcronym = acronym.toLowerCase(Locale.ROOT);
        if (normalizedTitle.equals(normalizedAcronym)
                || normalizedTitle.startsWith(normalizedAcronym + " ")
                || normalizedTitle.startsWith(normalizedAcronym + "(")) {
            return title;
        }
        return acronym + " " + title;
    }

    /** The DBLP conference-series acronym from the stream key: {@code conf/iccs} -> {@code ICCS}; null if not conf/X. */
    private static String dblpStreamAcronym(String series) {
        if (series == null || !series.startsWith("conf/")) {
            return null;
        }
        String stream = series.substring("conf/".length()).trim();
        return stream.isEmpty() ? null : stream.toUpperCase(java.util.Locale.ROOT);
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
            return resolveConferenceMatchByTitle(normalizedPublicationName, trace, FallbackReason.NO_ACRONYM_CANDIDATES);
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
            return resolveConferenceMatchByTitle(normalizedPublicationName, trace, FallbackReason.NO_CORE_CANDIDATES);
        }

        int bestScore = candidateConfidence.values().stream()
                .mapToInt(CandidateMatch::effectiveScore)
                .max()
                .orElse(MatchConfidence.NONE.score);
        if (bestScore < MatchConfidence.NORMALIZED_CONTAINS.score) {
            return resolveConferenceMatchByTitle(normalizedPublicationName, trace, FallbackReason.BELOW_THRESHOLD);
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
        trace = trace.withResolvedConference(winner.getId(), winner.getName(), winner.getAcronym(), winnerMatch.confidence())
                .withResolvedLookupStrategy(CoreLookupStrategy.ACRONYM);
        return new ConferenceMatch(true, winner, publicationName, trace);
    }

    private ConferenceMatch resolveConferenceMatchByTitle(String normalizedPublicationName,
                                                          ConferenceScoreTrace trace,
                                                          FallbackReason unresolvedReason) {
        trace = trace.withTitleLookupConsulted(true);
        String normalizedWithoutBoilerplate = normalizeWithoutBoilerplate(normalizedPublicationName);
        Map<String, CoreConferenceRanking> titleCandidates = new LinkedHashMap<>();
        for (CoreConferenceRanking ranking : Optional.ofNullable(lookupPort.getConferenceRankingsByNormalizedTitle(normalizedPublicationName)).orElse(List.of())) {
            titleCandidates.putIfAbsent(conferenceRankingKey(ranking), ranking);
        }
        if (!normalizedWithoutBoilerplate.isBlank() && !normalizedWithoutBoilerplate.equals(normalizedPublicationName)) {
            for (CoreConferenceRanking ranking : Optional.ofNullable(lookupPort.getConferenceRankingsByNormalizedTitle(normalizedWithoutBoilerplate)).orElse(List.of())) {
                titleCandidates.putIfAbsent(conferenceRankingKey(ranking), ranking);
            }
        }
        if (titleCandidates.isEmpty()) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(unresolvedReason));
        }

        Map<CoreConferenceRanking, CandidateMatch> candidateConfidence = new LinkedHashMap<>();
        List<CandidateSummary> candidateSummaries = new ArrayList<>(trace.candidateSummaries());
        for (CoreConferenceRanking ranking : titleCandidates.values()) {
            MatchConfidence confidence = scoreMatchConfidence(normalizedPublicationName, "", ranking);
            candidateSummaries.add(CandidateSummary.of("<TITLE>", ranking, confidence));
            if (confidence == MatchConfidence.NONE) {
                continue;
            }
            candidateConfidence.put(ranking, new CandidateMatch(new AcronymCandidate("", AcronymSource.TITLE_FALLBACK), confidence));
        }
        trace = trace.withCandidateSummaries(candidateSummaries);
        if (candidateConfidence.isEmpty()) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(unresolvedReason));
        }

        int bestScore = candidateConfidence.values().stream()
                .mapToInt(CandidateMatch::effectiveScore)
                .max()
                .orElse(MatchConfidence.NONE.score);
        if (bestScore < MatchConfidence.NORMALIZED_CONTAINS.score) {
            return ConferenceMatch.unresolved(trace.withFallbackReason(unresolvedReason));
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
        trace = trace.withResolvedConference(winner.getId(), winner.getName(), winner.getAcronym(), winnerMatch.confidence())
                .withResolvedLookupStrategy(CoreLookupStrategy.TITLE);
        return new ConferenceMatch(true, winner, normalizedPublicationName, trace);
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
        }
        registerWorkshopAcronymCandidates(candidates, publicationName);
        registerTrailingTitleCasedAcronymCandidate(candidates, publicationName);
        registerConcatenatedShortNameAcronymCandidate(candidates, publicationName);
        return new ArrayList<>(candidates.values());
    }

    /**
     * Scopus emits conference venues as {@code "Proceedings - YYYY <Full Name>, <Short Name> YYYY"}.
     * When the short name is a multi-word acronym spelled with spaces (e.g. CORE acronym {@code "BigData"}
     * appearing as {@code "Big Data"}), neither the per-token acronym lookup nor the exact normalized-title
     * lookup can match it. Concatenate the leading run of strict title-cased words in the trailing
     * comma fragment so the collapsed form ({@code "BigData"}) is offered as an acronym candidate; the
     * existing confidence scoring still requires the ranking name to match, so this cannot upgrade a
     * venue on the acronym alone.
     */
    private void registerConcatenatedShortNameAcronymCandidate(Map<String, AcronymCandidate> candidates, String publicationName) {
        if (publicationName == null) {
            return;
        }
        int lastComma = publicationName.lastIndexOf(',');
        if (lastComma < 0 || lastComma + 1 >= publicationName.length()) {
            return;
        }
        String trailingFragment = publicationName.substring(lastComma + 1).trim();
        if (trailingFragment.isBlank()) {
            return;
        }
        StringBuilder concatenated = new StringBuilder();
        int wordCount = 0;
        for (String token : trailingFragment.split("\\s+")) {
            String stripped = token.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
            if (!isStrictTitleCaseWord(stripped)) {
                break;
            }
            concatenated.append(stripped);
            wordCount++;
        }
        if (wordCount < 2 || concatenated.length() > 12) {
            return;
        }
        registerCandidate(candidates, normalizeAcronymToken(concatenated.toString()), AcronymSource.LAST_COMMA_FRAGMENT);
    }

    private void registerWorkshopAcronymCandidates(Map<String, AcronymCandidate> candidates, String publicationName) {
        if (publicationName == null || publicationName.isBlank()) {
            return;
        }
        String[] tokens = publicationName.trim().split("\\s+");
        for (int index = 0; index < tokens.length - 1; index++) {
            String normalizedToken = normalizeAcronymToken(tokens[index]);
            if (!isTitleCasedAcronymLikeToken(tokens[index], normalizedToken)) {
                continue;
            }
            String normalizedNext = normalizeComparableToken(tokens[index + 1].replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", ""));
            if (!normalizedNext.equals("workshop")) {
                continue;
            }
            registerCandidate(candidates, normalizedToken, AcronymSource.LAST_COMMA_FRAGMENT);
        }
    }

    private void registerTrailingTitleCasedAcronymCandidate(Map<String, AcronymCandidate> candidates, String publicationName) {
        if (publicationName == null || publicationName.isBlank()) {
            return;
        }
        String[] tokens = publicationName.trim().split("\\s+");
        int trailingIndex = findTrailingSignificantTokenIndex(tokens);
        if (trailingIndex < 0) {
            return;
        }
        String trailingToken = tokens[trailingIndex];
        String normalizedTrailingToken = normalizeAcronymToken(trailingToken);
        if (!isTitleCasedAcronymLikeToken(trailingToken, normalizedTrailingToken)) {
            return;
        }
        if (!matchesInitialsPattern(tokens, trailingIndex, normalizedTrailingToken)
                && !isTitleCasedAcronymBeforeIgnorableSuffix(tokens, trailingIndex, normalizedTrailingToken)) {
            return;
        }
        registerCandidate(candidates, normalizedTrailingToken, AcronymSource.LAST_COMMA_FRAGMENT);
    }

    private boolean isTitleCasedAcronymBeforeIgnorableSuffix(String[] tokens, int trailingIndex, String normalizedTrailingToken) {
        if (normalizedTrailingToken == null || normalizedTrailingToken.length() < 3 || normalizedTrailingToken.length() > 8) {
            return false;
        }
        if (tokens == null || trailingIndex < 0 || trailingIndex >= tokens.length - 1) {
            return false;
        }
        boolean foundIgnorableSuffix = false;
        for (int index = trailingIndex + 1; index < tokens.length; index++) {
            String normalized = normalizeAcronymToken(tokens[index]);
            if (normalized.isBlank()) {
                continue;
            }
            if (isIgnorableAcronymTailToken(normalized)) {
                foundIgnorableSuffix = true;
                continue;
            }
            if (!isIgnorableAcronymSuffixToken(normalized.toLowerCase(Locale.ROOT))) {
                return false;
            }
            foundIgnorableSuffix = true;
        }
        return foundIgnorableSuffix;
    }

    private int findTrailingSignificantTokenIndex(String[] tokens) {
        for (int index = tokens.length - 1; index >= 0; index--) {
            String normalized = normalizeAcronymToken(tokens[index]);
            if (normalized.isBlank()) {
                continue;
            }
            if (isIgnorableAcronymTailToken(normalized)) {
                continue;
            }
            if (isIgnorableAcronymSuffixToken(normalized.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return index;
        }
        return -1;
    }

    private boolean isTitleCasedAcronymLikeToken(String originalToken, String normalizedToken) {
        if (normalizedToken == null
                || normalizedToken.length() < 3
                || normalizedToken.length() > 10
                || ACRONYM_STOPWORDS.contains(normalizedToken)
                || originalToken == null
                || originalToken.isBlank()) {
            return false;
        }
        String stripped = originalToken.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
        if (stripped.isBlank()) {
            return false;
        }
        if (stripped.contains("-")) {
            return isHyphenatedTitleCasedToken(stripped);
        }
        return isStrictTitleCaseWord(stripped);
    }

    private boolean isHyphenatedTitleCasedToken(String stripped) {
        String[] parts = stripped.split("-");
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (!isStrictTitleCaseWord(part)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStrictTitleCaseWord(String word) {
        if (word == null || word.isBlank() || !Character.isUpperCase(word.charAt(0))) {
            return false;
        }
        boolean hasUppercase = false;
        boolean hasLowercase = false;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (Character.isUpperCase(ch)) {
                hasUppercase = true;
                if (i > 0) {
                    return false;
                }
            }
            if (Character.isLowerCase(ch)) {
                hasLowercase = true;
            }
            if (i > 0 && Character.isLetter(ch) && !Character.isLowerCase(ch)) {
                return false;
            }
        }
        return hasUppercase && hasLowercase;
    }

    private boolean matchesInitialsPattern(String[] tokens, int trailingIndex, String normalizedTrailingToken) {
        StringBuilder initials = new StringBuilder();
        for (int index = 0; index < trailingIndex; index++) {
            String normalized = normalizeInitialismSourceToken(tokens[index]);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            initials.append(Character.toUpperCase(normalized.charAt(0)));
        }
        return !initials.isEmpty() && normalizedTrailingToken.equals(initials.toString());
    }

    private String normalizeInitialismSourceToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token
                .replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (YEAR_TOKEN.matcher(normalized).find()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (ORDINAL_TOKEN.matcher(normalized.toUpperCase(Locale.ROOT)).matches()) {
            return null;
        }
        if (INITIALISM_IGNORED_TOKENS.contains(normalized)) {
            return null;
        }
        return normalized;
    }

    private void registerCandidate(Map<String, AcronymCandidate> candidates, String normalized, AcronymSource source) {
        registerExactCandidate(candidates, normalized, source);
        String collapsed = normalized == null ? "" : normalized.replace("-", "");
        if (!collapsed.isBlank() && !collapsed.equals(normalized)) {
            registerExactCandidate(candidates, collapsed, source);
        }
    }

    private void registerExactCandidate(Map<String, AcronymCandidate> candidates, String normalized, AcronymSource source) {
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
        if (normalizedToken == null
                || normalizedToken.length() < 2
                || normalizedToken.length() > 10
                || normalizedToken.chars().allMatch(Character::isDigit)
                || ORDINAL_TOKEN.matcher(normalizedToken).matches()
                || ACRONYM_STOPWORDS.contains(normalizedToken)
                || originalToken == null
                || originalToken.isBlank()) {
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
        boolean exactAcronymMatch = isExactAcronymMatch(acronymCandidate, ranking);
        if (exactAcronymMatch && normalizedPublicationName.equals(acronymCandidate.toLowerCase(Locale.ROOT))) {
            return MatchConfidence.EXACT_ACRONYM_ONLY;
        }
        boolean exactDecoratedAcronymMatch = exactAcronymMatch && (
                isDecoratedAcronymOnlyTitle(normalizedPublicationName, acronymCandidate)
                        || isSplitDecoratedAcronymOnlyTitle(normalizedPublicationName, acronymCandidate)
        );
        if (exactDecoratedAcronymMatch) {
            return MatchConfidence.EXACT_ACRONYM_DECORATED;
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
        if (exactAcronymMatch && hasStrongTokenOverlap(publicationTokens, rankingTokens)) {
            return MatchConfidence.EXACT_ACRONYM_STRONG_TOKEN_OVERLAP;
        }
        return MatchConfidence.NONE;
    }

    private String conferenceRankingKey(CoreConferenceRanking ranking) {
        if (ranking == null) {
            return "";
        }
        if (ranking.getId() != null && !ranking.getId().isBlank()) {
            return ranking.getId();
        }
        return String.join("|",
                Objects.toString(ranking.getAcronym(), ""),
                Objects.toString(ranking.getName(), ""));
    }

    private boolean isDecoratedAcronymOnlyTitle(String normalizedPublicationName, String acronymCandidate) {
        if (normalizedPublicationName == null || acronymCandidate == null) {
            return false;
        }
        String normalizedAcronym = acronymCandidate.toLowerCase(Locale.ROOT);
        if (normalizedPublicationName.equals(normalizedAcronym)) {
            return false;
        }
        String[] tokens = normalizedPublicationName.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        if (!tokens[0].equals(normalizedAcronym)) {
            return false;
        }
        for (int i = 1; i < tokens.length; i++) {
            if (!isIgnorableAcronymSuffixToken(tokens[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean isSplitDecoratedAcronymOnlyTitle(String normalizedPublicationName, String acronymCandidate) {
        if (normalizedPublicationName == null || acronymCandidate == null) {
            return false;
        }
        String[] tokens = normalizedPublicationName.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        String normalizedAcronym = acronymCandidate.toLowerCase(Locale.ROOT);
        StringBuilder mergedAcronym = new StringBuilder();
        boolean suffixSection = false;
        for (String token : tokens) {
            if (isIgnorableAcronymSuffixToken(token)) {
                suffixSection = true;
                continue;
            }
            if (suffixSection) {
                return false;
            }
            mergedAcronym.append(token);
            if (!normalizedAcronym.startsWith(mergedAcronym.toString())) {
                return false;
            }
        }
        if (!suffixSection) {
            return false;
        }
        return normalizedAcronym.equals(mergedAcronym.toString());
    }

    private boolean isIgnorableAcronymSuffixToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.chars().allMatch(Character::isDigit)) {
            return true;
        }
        if (normalized.matches("[ivxlcdm]+")) {
            return true;
        }
        if (ORDINAL_TOKEN.matcher(normalized.toUpperCase(Locale.ROOT)).matches()) {
            return true;
        }
        return normalized.equals("vol")
                || normalized.equals("volume")
                || normalized.equals("part")
                || normalized.equals("track")
                || normalized.equals("workshop")
                || normalized.equals("workshops")
                || normalized.equals("proceedings");
    }

    private String normalizeVenueName(String value) {
        return ConferenceTitleNormalizationSupport.normalizeVenueName(value);
    }

    private String normalizeWithoutBoilerplate(String normalizedValue) {
        return ConferenceTitleNormalizationSupport.normalizeWithoutBoilerplate(normalizedValue);
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
        if (normalized.endsWith("s")
                && normalized.length() > 4
                && !normalized.endsWith("ss")
                && !normalized.endsWith("us")
                && !normalized.endsWith("is")) {
            if (normalized.endsWith("es") && isDroppableEsPlural(normalized)) {
                return normalized.substring(0, normalized.length() - 2);
            }
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isDroppableEsPlural(String normalized) {
        return !normalized.endsWith("ses")
                && !normalized.endsWith("xes")
                && !normalized.endsWith("zes")
                && !normalized.endsWith("ches")
                && !normalized.endsWith("shes");
    }

    private boolean isIgnorableAcronymTailToken(String normalized) {
        return normalized.chars().allMatch(Character::isDigit)
                || ORDINAL_TOKEN.matcher(normalized).matches();
    }

    private void logTrace(ConferenceScoreTrace trace, Double points, Integer year, CoreConferenceRanking.Rank category, WoSRanking.Quarter quarter) {
        lastTraceForTests = trace;
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("CS_CONFERENCE_TRACE context={} itemId={} subtype={} coverDate={} forumTitle={} normalizedTitle={} allowedYears={} acronymCandidates={} candidateSummaries={} resolvedConferenceId={} resolvedConferenceName={} resolvedAcronym={} winnerConfidence={} resolvedYear={} resolvedRank={} fallbackReason={} dblpConsulted={} dblpEvidenceFound={} dblpConferenceTitle={} titleLookupConsulted={} resolvedLookupStrategy={} workshopAdjusted={} resolvedSource={} finalPoints={} finalYear={} finalCategory={} finalQuarter={}",
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
                trace.dblpConsulted(),
                trace.dblpEvidenceFound(),
                trace.dblpConferenceTitle(),
                trace.titleLookupConsulted(),
                trace.resolvedLookupStrategy(),
                trace.workshopAdjusted(),
                trace.resolvedSource(),
                points,
                year,
                category,
                quarter);
    }

    private ScholardexForumView forum(String publicationName) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setPublicationName(publicationName);
        return forum;
    }

    private record ConferenceMatch(boolean resolved, CoreConferenceRanking ranking, String sourceTitle, ConferenceScoreTrace trace) {
        static ConferenceMatch unresolved(ConferenceScoreTrace trace) {
            return new ConferenceMatch(false, null, null, trace);
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
        LAST_COMMA_FRAGMENT(2),
        TITLE_FALLBACK(0);

        private final int priority;

        AcronymSource(int priority) {
            this.priority = priority;
        }
    }

    private record ConferenceScoreResolution(Optional<Score> score, ConferenceScoreTrace trace) {
    }

    enum ResolutionSource {
        NONE,
        SCOPUS,
        DBLP
    }

    enum CoreLookupStrategy {
        NONE,
        ACRONYM,
        TITLE
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
            FallbackReason fallbackReason,
            boolean dblpConsulted,
            boolean dblpEvidenceFound,
            String dblpConferenceTitle,
            boolean titleLookupConsulted,
            CoreLookupStrategy resolvedLookupStrategy,
            boolean workshopAdjusted,
            ResolutionSource resolvedSource
    ) {
        static ConferenceScoreTrace forPublication(String itemId, String subtype, String coverDate, String forumTitle, List<Integer> allowedYears) {
            return new ConferenceScoreTrace("publication", itemId, subtype, coverDate, forumTitle, null, List.copyOf(allowedYears), List.of(), List.of(), null, null, null, MatchConfidence.NONE, null, null, FallbackReason.NONE, false, false, null, false, CoreLookupStrategy.NONE, false, ResolutionSource.NONE);
        }

        static ConferenceScoreTrace forActivity(String itemId, String forumTitle, List<Integer> allowedYears) {
            return new ConferenceScoreTrace("activity", itemId, null, null, forumTitle, null, List.copyOf(allowedYears), List.of(), List.of(), null, null, null, MatchConfidence.NONE, null, null, FallbackReason.NONE, false, false, null, false, CoreLookupStrategy.NONE, false, ResolutionSource.NONE);
        }

        ConferenceScoreTrace withNormalizedPublicationName(String value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, value, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withAcronymCandidates(List<String> values) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, List.copyOf(values), candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withCandidateSummaries(List<CandidateSummary> values) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, List.copyOf(values), resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withResolvedConference(String id, String name, String acronym, MatchConfidence confidence) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, id, name, acronym, confidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withResolvedYear(Integer year, CoreConferenceRanking.Rank rank) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, year, rank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withWinnerConfidence(MatchConfidence confidence) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, confidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withFallbackReason(FallbackReason reason) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, reason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withDblpConsulted(boolean value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, value, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withDblpEvidenceFound(boolean value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, value, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withDblpConferenceTitle(String value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, value, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withTitleLookupConsulted(boolean value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, value, resolvedLookupStrategy, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withResolvedLookupStrategy(CoreLookupStrategy value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, value, workshopAdjusted, resolvedSource);
        }

        ConferenceScoreTrace withWorkshopAdjusted(boolean value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, value, resolvedSource);
        }

        ConferenceScoreTrace withResolvedSource(ResolutionSource value) {
            return new ConferenceScoreTrace(context, itemId, subtype, coverDate, forumTitle, normalizedPublicationName, allowedYears, acronymCandidates, candidateSummaries, resolvedConferenceId, resolvedConferenceName, resolvedAcronym, winnerConfidence, resolvedYear, resolvedRank, fallbackReason, dblpConsulted, dblpEvidenceFound, dblpConferenceTitle, titleLookupConsulted, resolvedLookupStrategy, workshopAdjusted, value);
        }
    }

    private enum MatchConfidence {
        NONE(0),
        NORMALIZED_CONTAINS(1),
        EXACT_ACRONYM_ONLY(1),
        EXACT_ACRONYM_DECORATED(2),
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
    public ScoringStrategy strategy() {
        return ScoringStrategy.CS_CONFERENCE;
    }

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
