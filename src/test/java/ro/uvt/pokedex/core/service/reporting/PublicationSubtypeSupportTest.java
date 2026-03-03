package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.Publication;

import static org.junit.jupiter.api.Assertions.*;

class PublicationSubtypeSupportTest {

    @Test
    void prefersScopusSubtypeWhenBothPresent() {
        Publication publication = new Publication();
        publication.setScopusSubtype(" AR ");
        publication.setSubtype("cp");

        assertEquals("ar", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void fallsBackToSubtypeWhenScopusSubtypeBlank() {
        Publication publication = new Publication();
        publication.setScopusSubtype(" ");
        publication.setSubtype(" cp ");

        assertEquals("cp", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void returnsEmptyWhenBothSubtypeFieldsMissing() {
        Publication publication = new Publication();
        publication.setScopusSubtype(null);
        publication.setSubtype(null);

        assertEquals("", PublicationSubtypeSupport.resolveSubtype(publication));
    }

    @Test
    void isSubtypeUsesResolvedSubtype() {
        Publication publication = new Publication();
        publication.setScopusSubtype("re");
        publication.setSubtype("cp");

        assertTrue(PublicationSubtypeSupport.isSubtype(publication, "ar", "re"));
        assertFalse(PublicationSubtypeSupport.isSubtype(publication, "cp"));
    }
}
