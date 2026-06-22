package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;

public record UserPublicationsViewModel(
        List<ScholardexPublicationView> publications,
        int hIndex,
        Map<String, ScholardexAuthorView> authorMap,
        Map<String, ScholardexForumView> forumMap,
        Map<String, PublicationAuthorshipReviewState> authorshipReviewStateByPublicationId,
        Map<String, SuspiciousAuthorshipState> suspiciousAuthorshipByPublicationId,
        int pendingReviewCount,
        int suspiciousPendingCount,
        int recommendedPendingCount,
        int numCitations,
        ScholardexAuthorView profileAuthor,
        List<ScholardexAffiliationView> affiliations,
        // H67: source-attributed h-index breakdown (scholardex/graphTotal/scopusVenue/wosVenue). hIndex above stays the
        // Scholardex (citedByCount) value for back-compat; scopusVenue/wosVenue are the new indicative numbers.
        ro.uvt.pokedex.core.service.application.HIndexCalculator.HIndexBreakdown hIndices
) {
}
