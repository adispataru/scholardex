package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Projects a user's confirmed publications into {@link PublicationSnapshotItem}s for the
 * {@code journal-publications} and {@code conference-publications} binding roles.
 *
 * Filters by forum aggregation type so journal-role rows only contain journal publications and
 * conference-role rows only contain conference publications. Scores are computed via the same
 * {@link ScientificProductionService} the live indicator-result pipeline uses.
 */
@Component
public class PublicationRowProjector {

    private static final Logger LOG = LoggerFactory.getLogger(PublicationRowProjector.class);

    public static final String ROLE_JOURNAL = "journal-publications";
    public static final String ROLE_CONFERENCE = "conference-publications";
    /** Recent-subset publications role: contributes a separate total (e.g. Srecent) and marks which
     *  publications are recent; its rows are not rendered separately (the main role lists them). */
    public static final String ROLE_JOURNAL_RECENT = "journal-publications-recent";

    private final EffectiveAuthorshipReadService authorshipRead;
    private final ScholardexProjectionReadService projectionRead;
    private final ScientificProductionService scoring;

    public PublicationRowProjector(EffectiveAuthorshipReadService authorshipRead,
                                   ScholardexProjectionReadService projectionRead,
                                   ScientificProductionService scoring) {
        this.authorshipRead = authorshipRead;
        this.projectionRead = projectionRead;
        this.scoring = scoring;
    }

    public List<PublicationSnapshotItem> project(String userEmail, Indicator indicator, String roleKey) {
        if (!ROLE_JOURNAL.equals(roleKey) && !ROLE_CONFERENCE.equals(roleKey)) {
            return List.of();
        }
        return projectInternal(userEmail, indicator, roleKey);
    }

    /** Like {@link #project} but accepts any role tag — used when an Indicatorul I block is sourced
     *  from a publication indicator and the rows need full publication metadata for the
     *  description column. */
    public List<PublicationSnapshotItem> projectAllScored(String userEmail, Indicator indicator, String roleTag) {
        return projectInternal(userEmail, indicator, roleTag);
    }

    private List<PublicationSnapshotItem> projectInternal(String userEmail, Indicator indicator, String roleKey) {
        List<ScholardexPublicationView> publications = authorshipRead.findConfirmedPublicationsForScoring(userEmail);
        if (publications.isEmpty()) {
            return List.of();
        }

        Map<String, ScholardexForumView> forumMap = forumsByIdIn(
                publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet()));

        Map<String, Score> scores = scoring.calculateScientificProductionScore(
                publications.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                indicator);

        Map<String, String> authorNames = resolveAuthorNames(publications);

        List<PublicationSnapshotItem> out = new ArrayList<>();
        for (ScholardexPublicationView pub : publications) {
            Score score = scores.get(pub.getTitle());
            if (score == null) continue;

            ScholardexForumView forum = forumMap.get(pub.getForum());

            PublicationSnapshotItem item = new PublicationSnapshotItem();
            item.setRoleKey(roleKey);
            item.setItemKey(pub.getId() != null ? pub.getId() : pub.getTitle());
            item.setTitle(pub.getTitle());
            item.setAuthors(formatAuthors(pub.getAuthors(), authorNames));
            item.setForumName(forum != null ? forum.getPublicationName() : null);
            item.setVolumeInfo(pub.getVolume());
            item.setYear(PersistenceYearSupport.extractYear(pub.getCoverDate(), pub.getId(), LOG).orElse(null));
            item.setForumCategoryLetter(CategoryLetterMapper.toPublicationTemplateLetter(score.getCoreRankingEquivalent()));
            item.setAuthorCount(pub.getAuthorCount() > 0 ? pub.getAuthorCount()
                    : (pub.getAuthors() != null ? pub.getAuthors().size() : 0));
            item.setIsWorkshopDaNu(isWorkshopAdjusted(score) ? "DA" : "NU");
            item.setScore(score.getAuthorScore());
            // Capture the per-row score for downstream consumers (e.g. activity blocks fed by a
            // publication indicator). Fall back to forum-level score for strategies that only
            // populate `score` (book scoring fills only score; authorScore comes from the
            // indicator's MVEL formula evaluated in ScientificProductionService).
            double authorScore = score.getAuthorScore();
            item.setAuthorScore(authorScore > 0 ? authorScore : score.getScore());
            item.setForumScore(score.getScore());
            out.add(item);
        }
        return out;
    }

    private Map<String, ScholardexForumView> forumsByIdIn(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return projectionRead.findForumsByIdIn(ids).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, f -> f, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, String> resolveAuthorNames(List<ScholardexPublicationView> publications) {
        Set<String> ids = new HashSet<>();
        for (ScholardexPublicationView p : publications) {
            if (p.getAuthors() != null) ids.addAll(p.getAuthors());
        }
        if (ids.isEmpty()) return Map.of();
        return projectionRead.findAuthorsByIdIn(ids).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, ScholardexAuthorView::getName, (a, b) -> a));
    }

    private String formatAuthors(List<String> authorIds, Map<String, String> nameById) {
        if (authorIds == null || authorIds.isEmpty()) return "";
        List<String> names = new ArrayList<>(authorIds.size());
        for (String id : authorIds) {
            String n = nameById.get(id);
            if (n != null && !n.isBlank()) names.add(n);
        }
        return String.join(", ", names);
    }

    private boolean isWorkshopAdjusted(Score score) {
        Object v = score.getScoringInfo() != null ? score.getScoringInfo().get("workshopAdjusted") : null;
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(v.toString());
    }
}
