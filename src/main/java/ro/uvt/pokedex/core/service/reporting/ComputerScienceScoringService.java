package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

/**
 * Combined scoring service that delegates to appropriate specialized services based on publication type.
 */
@Service
public class ComputerScienceScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(ComputerScienceScoringService.class);

    private final ComputerScienceJournalScoringService journalScoringService;
    private final ComputerScienceConferenceScoringService conferenceScoringService;
    private final ComputerScienceBookService bookScoringService;

    @Autowired
    public ComputerScienceScoringService(
            ComputerScienceJournalScoringService journalScoringService,
            ComputerScienceConferenceScoringService conferenceScoringService,
            ComputerScienceBookService bookScoringService,
            ReportingLookupPort lookupPort) {
        super(lookupPort);
        this.journalScoringService = journalScoringService;
        this.conferenceScoringService = conferenceScoringService;
        this.bookScoringService = bookScoringService;
    }

    /* ------------------------------------------------------------------ */
    /*  PUBLICATION-based scoring                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        if (publication == null) {
            logger.warn("Received null publication");
            return createEmptyScore();
        }

        if (!PublicationSubtypeSupport.isResearchContribution(publication)) {
            logger.debug("Skipping non-research subtype '{}' for publication {}",
                    PublicationSubtypeSupport.resolveSubtype(publication), publication.getId());
            return createEmptyScore();
        }

        ScholardexForumView forum = publication.getForumId() == null ? null : lookupPort.getForum(publication.getForumId());
        if (forum != null && forum.getAggregationType() != null) {
            return switch (forum.getAggregationType()) {
                case "Journal" -> journalScoringService.getScore(publication, indicator);
                case "Conference Proceeding" -> conferenceScoringService.getScore(publication, indicator);
                // Book/Book-Series forums: a "cp" paper is a conference proceeding (e.g. LNCS), otherwise fall to
                // the subtype switch — which keeps journal output and DROPS books/chapters (they are not part of
                // this A*/A/B journal+conference framework; they belong to the SENSE book indicator, CS_SENSE).
                // The ch-conference-try below is restricted to the Lecture-Notes family — exactly the series
                // the book scorer excludes (LectureNotesSeriesSupport's partition: family → conference side,
                // other series → book side). Widening it would double-count SENSE-scored chapters.
                case "Book", "Book Series" -> scoreBySubtype(publication, indicator,
                        LectureNotesSeriesSupport.isLectureNotesSeries(forum));
                default -> scoreBySubtype(publication, indicator, false);
            };
        }

        return scoreBySubtype(publication, indicator, false);
    }

    private Score scoreBySubtype(ScoringPublicationReadModel publication, Indicator indicator, boolean lectureNotesVenue) {
        if (publication == null) {
            return createEmptyScore();
        }

        // H99 item 2: Scopus labels proceedings papers in book-series venues (LNCS/LNDECT/CCIS) "ch",
        // and the scopus-first crosswalk masks the Crossref "conference-paper" type on the same pub — so
        // the intended cp escape below never fired for a not-yet-restamped book-series forum, and the
        // combined CS strategy dropped to NON_RANK a paper the CS_CONFERENCE indicator ranked B on the
        // same page (Florin's 44-vs-36 top-A*/A/B mismatch). Either vocabulary saying "conference paper"
        // routes to the conference scorer, which resolves the actual venue (DBLP/Crossref/CORE) itself.
        if (PublicationSubtypeSupport.indicatesConferencePaper(publication)) {
            return conferenceScoringService.getScore(publication, indicator);
        }

        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        if (subtype.isEmpty()) {
            logger.warn("Publication has empty subtype: {}", publication.getId());
            return createEmptyScore();
        }

        // H99 follow-up (Florin's AINA-2026 chapters): Springer registers many proceedings volumes as BOOK
        // CHAPTERS — both vocabularies say "ch", so the conference-paper escape above cannot fire — yet the
        // Crossref volume title / DBLP evidence can still name the actual conference. On a LECTURE-NOTES
        // series (the family the book scorer excludes, so the handoff is a clean partition) give the
        // conference machinery a shot and accept its verdict ONLY on a positive identification (CORE
        // conference id, resolved acronym, or a DBLP/volume match). Unidentified papers yield nothing here —
        // and being family-gated out of the book scorer too, they stay where they were before this route.
        if (lectureNotesVenue && "ch".equals(subtype)) {
            Score conference = conferenceScoringService.getScore(publication, indicator);
            if (identifiedConference(conference)) {
                return conference;
            }
            return createEmptyScore();
        }

        // Delegate to specialized scoring services by publication subtype — the type is the authoritative
        // discriminator (mirrors the puncte/clas.c type switch). Only journals and conferences are dispatched:
        // this is the A*/A/B journal+conference framework (Info_B / Info_C). Books/chapters ("ch"/"bk") are NOT
        // scored here — they belong to the SENSE book indicator (Info_D_i, strategy CS_SENSE); routing them
        // through the book scorer here double-counted them into Info_B. Short surveys and data papers are journal output.
        return switch (subtype) {
            case "ar", "re", "sh", "dp" -> journalScoringService.getScore(publication, indicator);
            case "cp" -> conferenceScoringService.getScore(publication, indicator);
            default -> createEmptyScore();
        };
    }

    /** A conference score counts only when the scorer NAMED a conference — not a series floor or forum-title echo. */
    private static boolean identifiedConference(Score score) {
        if (score == null || score.getScoringInfo() == null) {
            return false;
        }
        var info = score.getScoringInfo();
        return info.get("matchedConferenceId") != null
                || info.get("matchedAcronym") != null
                || "DBLP".equals(info.get("matchSource"));
    }

    /* ------------------------------------------------------------------ */
    /*  ACTIVITY-based scoring                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        if (activity == null) {
            logger.warn("Received null activity");
            return createEmptyScore();
        }

        // For activities, we need to determine the type from the forum
        ScholardexForumView forum = getForumFromActivity(activity);
        if (forum == null) {
            logger.warn("Could not find forum for activity: {}", activity.getId());
            return createEmptyScore();
        }

        // Delegate based on forum aggregation type.
        return switch (forum.getAggregationType()) {
            case "Journal" -> journalScoringService.getScore(activity, indicator);
            case "Conference Proceeding" -> conferenceScoringService.getScore(activity, indicator);
            case "Book", "Book Series" -> bookScoringService.getScore(activity, indicator);
            default -> {
                logger.warn("Unhandled forum type: {}", forum.getAggregationType());
                yield createEmptyScore();
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /*  Helper methods                                                    */
    /* ------------------------------------------------------------------ */

    private Score createEmptyScore() {
        Score score = new Score();
        score.setScore(0.0);
        score.setYear(0);
        score.setCoreRankingEquivalent(CoreConferenceRanking.Rank.NON_RANK.toString());
        score.setQuarter(WoSRanking.Quarter.NOT_FOUND.toString());
        return score;
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.CS;
    }

    @Override
    public String getDescription() {
        return """
               Combined scoring strategy for Computer Science publications.
               Delegates to appropriate specialized services:
               - Journal articles: Uses WoS quartile-based scoring
               - Conference papers: Uses CORE ranking-based scoring
               - Other publication types are ignored in combined CS publication scoring

               For detailed scoring rules, see individual services.
               """;
    }
}
