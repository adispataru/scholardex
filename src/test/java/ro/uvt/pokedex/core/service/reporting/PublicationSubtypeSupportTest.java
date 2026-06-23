package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;

import static org.junit.jupiter.api.Assertions.*;

class PublicationSubtypeSupportTest {

    @Test
    void prefersScopusSubtypeWhenBothPresent() {
        ScoringPublication publication = publication("cp", " AR ");

        assertEquals("ar", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void fallsBackToSubtypeWhenScopusSubtypeBlank() {
        ScoringPublication publication = publication(" cp ", " ");

        assertEquals("cp", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void returnsEmptyWhenBothSubtypeFieldsMissing() {
        ScoringPublication publication = publication(null, null);

        assertEquals("", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void isSubtypeUsesResolvedSubtype() {
        ScoringPublication publication = publication("cp", "re");

        assertTrue(PublicationSubtypeSupport.isSubtype(publication, "ar", "re"));
        assertFalse(PublicationSubtypeSupport.isSubtype(publication, "cp"));
    }

    @Test
    void crosswalksOpenAlexResearchTypesToScopusCodesAndScoresThem() {
        // OpenAlex/Crossref vocabulary — must be treated as the equivalent Scopus research subtype,
        // otherwise tens of thousands of OpenAlex "article" papers silently fail the research gate.
        assertEquals("ar", PublicationSubtypeSupport.resolveSubtype(publication("article", null)));
        assertEquals("re", PublicationSubtypeSupport.resolveSubtype(publication("review", null)));
        assertEquals("ch", PublicationSubtypeSupport.resolveSubtype(publication("book-chapter", null)));
        assertEquals("cp", PublicationSubtypeSupport.resolveSubtype(publication("proceedings-article", null)));
        assertEquals("dp", PublicationSubtypeSupport.resolveSubtype(publication("dataset", null)));

        assertTrue(PublicationSubtypeSupport.isResearchContribution(publication("article", null)));
        assertTrue(PublicationSubtypeSupport.isResearchContribution(publication("book-chapter", null)));
        assertTrue(PublicationSubtypeSupport.isSubtype(publication("article", null), "ar"));
    }

    @Test
    void leavesNonScopusNonResearchOpenAlexTypesExcluded() {
        // Genuinely non-research OpenAlex types stay unmapped so they remain gated out of scoring.
        assertFalse(PublicationSubtypeSupport.isResearchContribution(publication("preprint", null)));
        assertFalse(PublicationSubtypeSupport.isResearchContribution(publication("dissertation", null)));
        assertFalse(PublicationSubtypeSupport.isResearchContribution(publication("peer-review", null)));
        assertFalse(PublicationSubtypeSupport.isResearchContribution(publication("paratext", null)));
    }

    private ScoringPublication publication(String subtype, String scopusSubtype) {
        return new ScoringPublication(
                "p1",
                "eid-1",
                "forum-1",
                "2023-01-01",
                subtype,
                scopusSubtype,
                java.util.List.of("a1"),
                1,
                "10.1000/test",
                null,
                "Test publication",
                0,
                java.util.Set.of()
        );
    }
}
