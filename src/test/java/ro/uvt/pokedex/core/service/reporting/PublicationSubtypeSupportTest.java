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
