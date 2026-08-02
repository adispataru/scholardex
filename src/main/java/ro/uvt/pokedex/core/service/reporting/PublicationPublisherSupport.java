package ro.uvt.pokedex.core.service.reporting;

import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;

/**
 * The publisher of a book/chapter publication, resolved the one way the platform resolves it.
 *
 * <p>H66B M7: a book/chapter resolves its publisher from the book registry
 * ({@code scholardex.book_facts}) via {@code bookId}; otherwise — or if the book is unlisted — it
 * falls back to the forum's publisher. With observed-book minting the bookId path resolves for every
 * book venue. Extracted from {@code FeaaBookScoringService} when H98 needed the same answer for the
 * physics WoS-Master-Book-List gate: a second implementation of one rule is how the Lecture-Notes
 * double count happened.
 */
public final class PublicationPublisherSupport {

    private PublicationPublisherSupport() {
    }

    public static String resolvePublisher(ScoringPublicationReadModel publication, ReportingLookupPort lookupPort) {
        if (publication == null || lookupPort == null) {
            return null;
        }
        String bookId = publication.getBookId();
        if (bookId != null && !bookId.isBlank()) {
            ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact book = lookupPort.getBook(bookId);
            if (book != null) {
                return book.getPublisher();
            }
        }
        ScholardexForumView forum = lookupPort.getForum(publication.getForumId());
        return forum != null ? forum.getPublisher() : null;
    }
}
