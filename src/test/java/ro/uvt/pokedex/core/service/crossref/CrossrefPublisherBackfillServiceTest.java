package ro.uvt.pokedex.core.service.crossref;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The publisher backfill (Florin's perspectiva-D holes): one Crossref probe per publisher-less venue,
 * written to the book row / series forum; venues that already carry a publisher, journal forums, and
 * dry runs stay untouched.
 */
@ExtendWith(MockitoExtension.class)
class CrossrefPublisherBackfillServiceTest {

    @Mock private MongoTemplate mongoTemplate;
    @Mock private ScholardexBookFactRepository bookFactRepository;
    @Mock private ScholardexForumFactRepository forumFactRepository;
    @Mock private CrossrefClient crossrefClient;

    @InjectMocks private CrossrefPublisherBackfillService service;

    private static ScholardexPublicationFact pub(String doi, String bookId, String forumId) {
        ScholardexPublicationFact pub = new ScholardexPublicationFact();
        pub.setId("spub_" + (bookId != null ? bookId : forumId));
        pub.setDoi(doi);
        pub.setBookId(bookId);
        pub.setForumId(forumId);
        return pub;
    }

    @Test
    void fillsEmptyBookRowsAndSeriesForumsFromCrossref() {
        when(mongoTemplate.find(any(Query.class), eq(ScholardexPublicationFact.class))).thenReturn(List.of(
                pub("10.4018/978-1-5225-9866-4.ch031", "book-igi", null),
                pub("10.1007/978-1-4471-6452-4_11", null, "forum-ccn")));

        ScholardexBookFact igi = new ScholardexBookFact();
        igi.setId("book-igi");
        when(bookFactRepository.findById("book-igi")).thenReturn(Optional.of(igi));
        ScholardexForumFact ccn = new ScholardexForumFact();
        ccn.setId("forum-ccn");
        ccn.setAggregationType("Book Series");
        when(forumFactRepository.findById("forum-ccn")).thenReturn(Optional.of(ccn));

        when(crossrefClient.publisher("10.4018/978-1-5225-9866-4.ch031")).thenReturn(Optional.of("IGI Global"));
        when(crossrefClient.publisher("10.1007/978-1-4471-6452-4_11")).thenReturn(Optional.of("Springer London"));

        var result = service.sweep(false, 0);

        assertEquals(2, result.getImportedCount());
        verify(bookFactRepository).save(igi);
        assertEquals("IGI Global", igi.getPublisher());
        verify(forumFactRepository).save(ccn);
        assertEquals("Springer London", ccn.getPublisher());
    }

    @Test
    void skipsFilledVenuesJournalForumsAndWritesNothingOnDryRun() {
        when(mongoTemplate.find(any(Query.class), eq(ScholardexPublicationFact.class))).thenReturn(List.of(
                pub("10.1/filled", "book-filled", null),
                pub("10.1/journal", null, "forum-journal"),
                pub("10.1/dry", "book-dry", null)));

        ScholardexBookFact filled = new ScholardexBookFact();
        filled.setId("book-filled");
        filled.setPublisher("Already There");
        when(bookFactRepository.findById("book-filled")).thenReturn(Optional.of(filled));
        ScholardexForumFact journal = new ScholardexForumFact();
        journal.setId("forum-journal");
        journal.setAggregationType("Journal");
        when(forumFactRepository.findById("forum-journal")).thenReturn(Optional.of(journal));
        ScholardexBookFact dry = new ScholardexBookFact();
        dry.setId("book-dry");
        when(bookFactRepository.findById("book-dry")).thenReturn(Optional.of(dry));
        lenient().when(crossrefClient.publisher("10.1/dry")).thenReturn(Optional.of("Some Press"));

        var result = service.sweep(true, 0);

        // Only the empty book row is probed; nothing is saved on a dry run.
        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        verify(bookFactRepository, never()).save(any());
        verify(forumFactRepository, never()).save(any());
    }
}
