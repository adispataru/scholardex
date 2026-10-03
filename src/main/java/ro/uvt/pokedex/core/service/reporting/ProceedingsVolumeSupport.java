package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

import java.util.Locale;

/**
 * H145 — Physics' precizare 5: "nu se includ capitolele publicate în volumele de proceedingsuri de la conferințe". A
 * paper in a conference proceedings volume is no book chapter, though Crossref and OpenAlex often type it so: a
 * conference paper by its subtype, a venue Scopus classes as conference proceedings, or a volume or series named
 * "… Proceedings …" (Springer Proceedings in Physics, AIP Conference Proceedings). The "Lecture Notes in …" family
 * is left out on purpose: Lecture Notes in Physics and in Mathematics publish monographs and lecture courses
 * ({@link LectureNotesSeriesSupport}), and the conference papers of the computer-science series carry Scopus'
 * conference-paper subtype anyway.
 */
public final class ProceedingsVolumeSupport {

    private ProceedingsVolumeSupport() {
    }

    public static boolean isProceedingsPaper(ScoringPublicationReadModel publication, ReportingLookupPort lookupPort) {
        if (publication == null) {
            return false;
        }
        if (PublicationSubtypeSupport.indicatesConferencePaper(publication)) {
            return true;
        }
        ScholardexForumView forum = lookupPort == null ? null : lookupPort.getForum(publication.getForumId());
        if (forum != null && (forum.hasAggregationType("Conference Proceeding") || namesProceedings(forum.getPublicationName()))) {
            return true;
        }
        String bookId = publication.getBookId();
        if (lookupPort != null && bookId != null && !bookId.isBlank()) {
            ScholardexBookFact book = lookupPort.getBook(bookId);
            return book != null && namesProceedings(book.getTitle());
        }
        return false;
    }

    static boolean namesProceedings(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("proceedings");
    }
}
