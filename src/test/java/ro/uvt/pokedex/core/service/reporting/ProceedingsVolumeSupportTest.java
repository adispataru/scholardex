package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H145 — Physics' precizare 5: a paper in a conference proceedings volume is no book chapter. */
class ProceedingsVolumeSupportTest {

    private final ReportingLookupPort lookup = mock(ReportingLookupPort.class);

    private static ScoringPublication chapter(String forumId, String bookId, String scopusSubtype) {
        return new ScoringPublication("p", null, forumId, bookId, "2023-01-01", "ch", scopusSubtype, List.of("a"), 2,
                null, null, "A paper", 0, Set.of(), 0, 0, 0, List.of(), null);
    }

    private ScholardexForumView forum(String id, String name, String aggregationType) {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId(id);
        forum.setPublicationName(name);
        if (aggregationType != null) {
            forum.setAggregationType(aggregationType);
        }
        when(lookup.getForum(id)).thenReturn(forum);
        return forum;
    }

    @Test
    void aConferencePaperByItsSubtypeVenueClassOrName() {
        assertTrue(ProceedingsVolumeSupport.isProceedingsPaper(chapter(null, null, "cp"), lookup), "Scopus says conference paper");
        forum("f-conf", "Some conference volume", "Conference Proceeding");
        assertTrue(ProceedingsVolumeSupport.isProceedingsPaper(chapter("f-conf", null, null), lookup));
        forum("f-spp", "Springer Proceedings in Physics", "Book Series");
        assertTrue(ProceedingsVolumeSupport.isProceedingsPaper(chapter("f-spp", null, null), lookup));
        ScholardexBookFact book = new ScholardexBookFact();
        book.setTitle("Proceedings of the 12th Conference on Plasma Physics");
        when(lookup.getBook("b-1")).thenReturn(book);
        assertTrue(ProceedingsVolumeSupport.isProceedingsPaper(chapter(null, "b-1", null), lookup));
    }

    @Test
    void aChapterOfAMonographSeriesStaysAChapter() {
        forum("f-lnp", "Lecture Notes in Physics", "Book Series");
        assertFalse(ProceedingsVolumeSupport.isProceedingsPaper(chapter("f-lnp", null, null), lookup),
                "Lecture Notes in Physics publishes monographs and lecture courses");
        forum("f-book", "Handbook of Solid State Physics", "Book");
        assertFalse(ProceedingsVolumeSupport.isProceedingsPaper(chapter("f-book", null, null), lookup));
    }
}
