package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Projects citations into {@link CitationSnapshotItem} tiles for the
 * {@code citations-per-publication} binding role: one tile per cited publication that has at
 * least one scored citing entry in scope. Mirrors the join the legacy {@code handleCitationsWorkbook}
 * does, but batches lookups so a render is a small handful of round-trips regardless of pub count.
 */
@Component
public class CitationRowProjector {

    private static final Logger LOG = LoggerFactory.getLogger(CitationRowProjector.class);

    public static final String ROLE_KEY = "citations-per-publication";

    private final UserService userService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final EffectiveAuthorshipReadService authorshipRead;
    private final ScholardexProjectionReadService projectionRead;
    private final ScientificProductionService scoring;

    public CitationRowProjector(UserService userService,
                                ResearcherAuthorLookupService researcherAuthorLookupService,
                                EffectiveAuthorshipReadService authorshipRead,
                                ScholardexProjectionReadService projectionRead,
                                ScientificProductionService scoring) {
        this.userService = userService;
        this.researcherAuthorLookupService = researcherAuthorLookupService;
        this.authorshipRead = authorshipRead;
        this.projectionRead = projectionRead;
        this.scoring = scoring;
    }

    public List<CitationSnapshotItem> project(String userEmail, Indicator indicator, String roleKey) {
        if (!ROLE_KEY.equals(roleKey)) {
            return List.of();
        }

        // H61: self-citation exclusion mode — NONE / candidate-only / any-coauthor of the cited publication.
        ro.uvt.pokedex.core.model.reporting.scoring.SelfCitationPolicy citationPolicy =
                indicator.getCitationExclusionPolicy();
        Set<String> selfAuthorIds = resolveSelfAuthorIds(userEmail);

        List<ScholardexPublicationView> citedPubs = authorshipRead.findConfirmedPublicationsForScoring(userEmail);
        if (citedPubs.isEmpty()) return List.of();

        List<String> citedIds = citedPubs.stream()
                .map(ScholardexPublicationView::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (citedIds.isEmpty()) return List.of();

        List<ScholardexCitationView> allCitations = projectionRead.findAllCitationsByCitedIdIn(citedIds);
        if (allCitations.isEmpty()) return List.of();

        Map<String, List<String>> citingIdsByCited = new HashMap<>();
        for (ScholardexCitationView c : allCitations) {
            citingIdsByCited.computeIfAbsent(c.getCitedId(), k -> new ArrayList<>()).add(c.getCitingId());
        }

        Set<String> allCitingIds = allCitations.stream()
                .map(ScholardexCitationView::getCitingId)
                .collect(Collectors.toSet());
        Map<String, ScholardexPublicationView> citingPubById = allCitingIds.isEmpty()
                ? Map.of()
                : projectionRead.findAllPublicationsByIdIn(allCitingIds).stream()
                        .collect(Collectors.toMap(ScholardexPublicationView::getId, p -> p, (a, b) -> a));

        Set<String> allForumIds = new HashSet<>();
        citedPubs.forEach(p -> { if (p.getForum() != null) allForumIds.add(p.getForum()); });
        citingPubById.values().forEach(p -> { if (p.getForum() != null) allForumIds.add(p.getForum()); });
        Map<String, ScholardexForumView> forumById = forumsByIdIn(allForumIds);

        Set<String> allCitingAuthorIds = new HashSet<>();
        citingPubById.values().forEach(p -> {
            if (p.getAuthors() != null) allCitingAuthorIds.addAll(p.getAuthors());
        });
        Map<String, String> nameById = authorsByIdIn(allCitingAuthorIds);

        List<CitationSnapshotItem> out = new ArrayList<>();
        for (ScholardexPublicationView cited : citedPubs) {
            List<String> citingIds = citingIdsByCited.getOrDefault(cited.getId(), Collections.emptyList());
            if (citingIds.isEmpty()) continue;

            List<ScholardexPublicationView> citingForThisPub = new ArrayList<>();
            for (String id : citingIds) {
                ScholardexPublicationView p = citingPubById.get(id);
                if (p != null) citingForThisPub.add(p);
            }
            if (citingForThisPub.isEmpty()) continue;

            // H61: shared helper with the score path so both sites exclude the identical citation set.
            Set<String> exclusionAuthorIds = ro.uvt.pokedex.core.service.application
                    .ReportScopedIndicatorScoringSupport.citationExclusionAuthorIds(citationPolicy, cited, selfAuthorIds);

            Map<String, Score> citScores = scoring.calculateScientificImpactScore(
                    cited.toScoringPublication(),
                    citingForThisPub.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                    indicator);

            List<CitationSnapshotItem.CitingPublication> innerRows = new ArrayList<>();
            double tileScore = 0.0;
            for (ScholardexPublicationView citing : citingForThisPub) {
                if (!exclusionAuthorIds.isEmpty() && hasOverlap(citing.getAuthors(), exclusionAuthorIds)) continue;
                Score score = citScores.get(citing.getTitle());
                if (score == null) continue;

                CitationSnapshotItem.CitingPublication row = new CitationSnapshotItem.CitingPublication();
                row.setTitle(citing.getTitle());
                row.setAuthors(formatAuthors(citing.getAuthors(), nameById));
                ScholardexForumView f = forumById.get(citing.getForum());
                row.setForumName(f != null ? f.getPublicationName() : null);
                row.setVolumeInfo(citing.getVolume());
                row.setYear(PersistenceYearSupport.extractYear(citing.getCoverDate(), citing.getId(), LOG).orElse(null));
                row.setIsWorkshopDaNu(isWorkshopAdjusted(score) ? "DA" : "NU");
                row.setForumCategoryLetter(CategoryLetterMapper.toPublicationTemplateLetter(score.getCoreRankingEquivalent()));
                // Per-citation breakdown shows raw category points (matches the file's column J,
                // which is not author-divided). The tile total sums the author-divided authorScore.
                row.setScore(score.getScore());
                tileScore += score.getAuthorScore();
                innerRows.add(row);
            }

            if (innerRows.isEmpty()) continue;

            CitationSnapshotItem tile = new CitationSnapshotItem();
            tile.setRoleKey(roleKey);
            tile.setItemKey(cited.getId() != null ? cited.getId() : cited.getTitle());
            tile.setPublicationTitle(cited.getTitle());
            ScholardexForumView citedForum = forumById.get(cited.getForum());
            tile.setPublicationForumName(citedForum != null ? citedForum.getPublicationName() : null);
            tile.setPublicationYear(PersistenceYearSupport.extractYear(cited.getCoverDate(), cited.getId(), LOG).orElse(null));
            tile.setPublicationAuthorCount(cited.getAuthorCount() > 0 ? cited.getAuthorCount()
                    : (cited.getAuthors() != null ? cited.getAuthors().size() : 0));
            tile.setCitingPublications(innerRows);
            tile.setScore(tileScore);
            out.add(tile);
        }
        return out;
    }

    private Map<String, ScholardexForumView> forumsByIdIn(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return projectionRead.findForumsByIdIn(ids).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, f -> f, (a, b) -> a));
    }

    private Map<String, String> authorsByIdIn(Collection<String> ids) {
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

    /**
     * Resolves the user's lookup keys (Scopus/WoS/primary Scholardex IDs) to canonical Scholardex
     * author IDs — the form that appears in {@code ScholardexPublicationView.authors}. Without this
     * resolution step the lookup keys would never match the canonical IDs and {@code excludeSelf}
     * would silently keep self-citations.
     */
    private Set<String> resolveSelfAuthorIds(String userEmail) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty() || userOpt.get().getResearcherProfile() == null) {
            return new HashSet<>();
        }
        List<String> lookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(userOpt.get().getResearcherProfile());
        if (lookupKeys.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> ids = new HashSet<>();
        projectionRead.findAuthorsByIdIn(lookupKeys).forEach(a -> {
            if (a.getId() != null) ids.add(a.getId());
        });
        // Also include the lookup keys themselves so DOIs/Scopus/WoS-encoded author refs in the
        // publications' author lists still match without an extra round-trip.
        ids.addAll(lookupKeys);
        return ids;
    }

    private boolean isWorkshopAdjusted(Score score) {
        Object v = score.getScoringInfo() != null ? score.getScoringInfo().get("workshopAdjusted") : null;
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(v.toString());
    }

    private boolean hasOverlap(List<String> ids, Set<String> selfAuthorIds) {
        if (ids == null || selfAuthorIds.isEmpty()) return false;
        for (String id : ids) {
            if (selfAuthorIds.contains(id)) return true;
        }
        return false;
    }
}
