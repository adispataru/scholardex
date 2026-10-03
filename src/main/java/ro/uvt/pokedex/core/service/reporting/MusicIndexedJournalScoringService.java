package ro.uvt.pokedex.core.service.reporting;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.List;
import java.util.Set;

/**
 * OM 3.019/2025, Comisia 35, domain Music, CS 2.1 — "studiu sau articol publicat într-o revistă de specialitate
 * indexată în baze de date internaționale [the 16 of the list] sau în volumele unor manifestări științifice
 * indexate în baze de date internaționale": 15 points each, whatever the database.
 *
 * <p>The platform knows four of the sixteen: Scopus, the Web of Science editions (the list names AHCI and ESCI;
 * the other editions count as databases "of the related domains", which the list admits for interdisciplinary
 * work), DOAJ and ERIH PLUS. A journal article or review in a venue indexed in one of them, and a proceedings
 * paper in a volume indexed in Scopus or in the WoS conference index, returns S = 1 with the database as
 * {@code category}; the formula gives the points ({@code 15 * S}). H142 slice 4: a journal article or review
 * outside those four counts when the journal is on the title list of another database of the list — Cambridge
 * Core, CEEOL, EBSCO, JSTOR, Oxford Academic, Project MUSE, ProQuest, RILM, Sciendo, Taylor &amp; Francis
 * ({@link JournalDatabases#MUSIC}), memberships of the forum like DOAJ's. Anything else returns 0 with zeroReason
 * {@code NOT_INDEXED}.</p>
 */
@Service
public class MusicIndexedJournalScoringService extends AbstractWoSForumScoringService {

    private static final Set<String> WOS_EDITIONS = Set.of("SCIE", "SSCI", "AHCI", "ESCI");

    public MusicIndexedJournalScoringService(ReportingLookupPort lookupPort) {
        super(lookupPort);
    }

    @Override
    public ScoringStrategy strategy() {
        return ScoringStrategy.MUSIC_INDEXED_JOURNAL;
    }

    @Override
    public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) {
        Score score = new Score();
        if (publication == null) {
            return score;
        }
        boolean article = isArticleOrReview(publication);
        boolean proceedings = PublicationSubtypeSupport.isSubtype(publication, "cp");
        if (!article && !proceedings) {
            score.getScoringInfo().put("zeroReason", "VENUE_TYPE_MISMATCH");
            return score;
        }
        String forumId = publication.getForumId();
        Set<String> databases = forumId == null || forumId.isBlank() ? Set.of()
                : lookupPort.getForumIndexingDatabases(forumId);
        String database = null;
        if (databases.stream().anyMatch(WOS_EDITIONS::contains)
                || (proceedings && forumId != null && lookupPort.isForumCpciIndexed(forumId))) {
            database = "WOS";
        } else if (databases.contains("SCOPUS")) {
            database = "SCOPUS";
        } else if (article && databases.contains("ERIH")) {
            database = "ERIH PLUS";
        } else if (article && databases.contains("DOAJ")) {
            database = "DOAJ";
        }
        if (database == null && article) {
            database = databases.stream().filter(JournalDatabases.MUSIC::contains).sorted()
                    .map(JournalDatabases::label).findFirst().orElse(null);
        }
        if (database == null) {
            score.getScoringInfo().put("zeroReason", "NOT_INDEXED");
            return score;
        }
        List<Integer> years = getAllowedYearsForPublication(publication, indicator);
        score.setScore(1.0);
        score.setCoreRankingEquivalent(database); // reaches the formula as `category`
        score.setScoringSource(strategy().name());
        score.setYear(years.isEmpty() ? 0 : years.getFirst());
        return score;
    }

    @Override
    public Score getScore(ActivityInstance activity, Indicator indicator) {
        return new Score(); // papers are publication-shaped; declared ones have their own activity type
    }

    @Override
    public String getDescription() {
        return "Comisia 35 (2026, Music) CS 2.1: a journal article or review in a venue indexed in Scopus, Web of "
                + "Science, ERIH PLUS or DOAJ, or covered by the title list of another database of the list (Cambridge "
                + "Core, CEEOL, EBSCO, JSTOR, Oxford Academic, Project MUSE, ProQuest, RILM, Sciendo, Taylor & Francis), "
                + "or a proceedings paper in a volume indexed in Scopus or the WoS conference index: S = 1, "
                + "the database as category; the formula gives 15 points.\n";
    }
}
