package ro.uvt.pokedex.core.service.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;

/**
 * Psihologie 2026 (Comisia 28) I11 scorer — Web of Science Core Collection membership, nothing else.
 * An item scores a flat 1.0 when its forum was indexed in SCIE, SSCI, AHCI or ESCI in the item's own
 * year (the standard lists all four under "Web of Science Core Collection"); the first matching edition
 * is exposed as {@code category}. No WoS category or domain restriction applies: a citation counts
 * wherever in WoS the citing journal sits.
 *
 * <p>The value is <b>indicative</b>. It is our citation graph restricted to WoS-indexed journals, not the
 * official "Citing Articles without self-citations" figure from the WoS citation report; the proceedings
 * (CPCI) and book (BKCI) indexes are not covered by our membership data, so it tends to undercount.</p>
 */
@Service
public class WosIndexedScoringService extends AbstractForumScoringService {

    private static final Logger logger = LoggerFactory.getLogger(WosIndexedScoringService.class);

    public WosIndexedScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.WOS_INDEXED;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null || publication.getForumId() == null || publication.getForumId().isBlank()) {
            return score;
        }
        int year = PersistenceYearSupport
                .extractYear(publication.getCoverDate(), publication.getId(), logger)
                .orElseGet(lookupPort::maxAvailableYear);
        String edition = firstEdition(publication.getForumId(), year);
        if (edition == null) {
            score.getScoringInfo().put("zeroReason", "NOT_WOS_INDEXED");
            return score;
        }
        score.setScore(1.0);
        score.setYear(year);
        score.setCoreRankingEquivalent(edition); // reaches the formula as `category`
        score.setScoringSource(strategy().name());
        return score;
    }

    /** The first WoS edition the forum belongs to in {@code year}, most common editions first; null when none. */
    private String firstEdition(String forumId, int year) {
        if (lookupPort.isForumInSsci(forumId, year)) return "SSCI";
        if (lookupPort.isForumInScie(forumId, year)) return "SCIE";
        if (lookupPort.isForumInEsci(forumId, year)) return "ESCI";
        if (lookupPort.isForumInAhci(forumId, year)) return "AHCI";
        return null;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // membership is asked of publications (the citing papers), not of activities
    }

    @Override
    public String getDescription() {
        return "Web of Science Core Collection membership (SCIE/SSCI/AHCI/ESCI) in the item's year: S = 1, "
                + "the edition as `category`. Indicative; used for the Psihologie 2026 WoS citation count.\n";
    }
}
