package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

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
    public Score getScore(Publication publication, Indicator indicator) {
        if (publication == null) {
            logger.warn("Received null publication");
            return createEmptyScore();
        }

        String subtype = PublicationSubtypeSupport.resolveSubtype(publication);
        if (subtype.isEmpty()) {
            logger.warn("Publication has empty subtype: {}", publication.getId());
            return createEmptyScore();
        }

        // Delegate to specialized scoring services by publication subtype.
        return switch (subtype) {
            case "ar", "re" -> journalScoringService.getScore(publication, indicator);
            case "cp" -> conferenceScoringService.getScore(publication, indicator);
            case "bk", "ch" -> bookScoringService.getScore(publication, indicator);
            default -> {
                logger.warn("Unhandled publication subtype: {}", subtype);
                yield createEmptyScore();
            }
        };
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
        Forum forum = getForumFromActivity(activity);
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
        score.setCategory(CoreConferenceRanking.Rank.NON_RANK.toString());
        score.setQuarter(WoSRanking.Quarter.NOT_FOUND.toString());
        return score;
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getDescription() {
        return """
               Combined scoring strategy for Computer Science publications.
               Delegates to appropriate specialized services:
               - Journal articles: Uses WoS quartile-based scoring
               - Conference papers: Uses CORE ranking-based scoring
               - Books: Uses SENSE publisher-based scoring
               
               For detailed scoring rules, see individual services.
               """;
    }
}
