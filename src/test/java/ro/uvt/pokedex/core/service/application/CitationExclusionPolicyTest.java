package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.scoring.SelfCitationPolicy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H61: the shared exclusion-set helper that both citation filter sites (score path + display path) call, so they
 * exclude the identical set. The conceptual change is the comparison set per policy.
 */
class CitationExclusionPolicyTest {

    private static ScholardexPublicationView cited(String... authorIds) {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setAuthors(List.of(authorIds));
        return p;
    }

    private final Set<String> candidate = Set.of("candidate");

    @Test
    void noneExcludesNothing() {
        assertTrue(ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.NONE, cited("candidate", "coauthorX"), candidate)
                .isEmpty());
    }

    @Test
    void candidateOnlyUsesTheCandidateIds() {
        assertEquals(candidate, ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.CANDIDATE_ONLY, cited("candidate", "coauthorX"), candidate));
    }

    @Test
    void anyCoauthorUsesTheCitedPublicationAuthorSetAndWidensCandidateOnly() {
        ScholardexPublicationView cited = cited("candidate", "coauthorX");

        Set<String> candidateOnly = ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.CANDIDATE_ONLY, cited, candidate);
        Set<String> anyCoauthor = ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.ANY_COAUTHOR, cited, candidate);

        // A citing paper that shares only the NON-candidate co-author is excluded under ANY_COAUTHOR but kept under
        // CANDIDATE_ONLY — the whole point of H61.
        assertFalse(candidateOnly.contains("coauthorX"));
        assertTrue(anyCoauthor.contains("coauthorX"));
        assertTrue(anyCoauthor.containsAll(candidateOnly), "ANY_COAUTHOR ⊇ CANDIDATE_ONLY");
    }

    @Test
    void emptyOrNullAuthorListsAreSafeNoOps() {
        assertTrue(ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.ANY_COAUTHOR, null, candidate).isEmpty());
        assertTrue(ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.ANY_COAUTHOR, cited(), candidate).isEmpty());
        assertTrue(ReportScopedIndicatorScoringSupport
                .citationExclusionAuthorIds(SelfCitationPolicy.CANDIDATE_ONLY, cited("candidate"), null).isEmpty());
    }
}
