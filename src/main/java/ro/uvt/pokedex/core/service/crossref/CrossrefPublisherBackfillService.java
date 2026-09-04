package ro.uvt.pokedex.core.service.crossref;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publisher backfill for book/chapter venues (Florin's perspectiva-D holes, 2026-09-02). Measured
 * against prod: of 9,755 ch/bk publications, only FIVE resolved a publisher through their book row —
 * 2,163 distinct observed-minted books (created from Scopus publication payloads, which carry no
 * publisher) and 433 series forums are publisher-less, so the SENSE/A2/MBL book scorers starve and the
 * chapters vanish from perspectiva D (IGI's 10.4018 chapters, Springer's Computer Communications and
 * Networks series, the Wiley volume).
 *
 * <p>One Crossref probe per VENUE (a representative member publication's DOI → {@code message.publisher}),
 * written onto the book row or the series forum. Book rows are read straight from Mongo at scoring time,
 * so those take effect on the next report refresh; forum writes reach scoring after a projection refresh
 * (the nightly rebuild covers it).</p>
 */
@Service
@RequiredArgsConstructor
public class CrossrefPublisherBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CrossrefPublisherBackfillService.class);

    private final MongoTemplate mongoTemplate;
    private final ScholardexBookFactRepository bookFactRepository;
    private final ScholardexForumFactRepository forumFactRepository;
    private final CrossrefClient crossrefClient;

    public ImportProcessingResult sweep(boolean dryRun, int limit) {
        ImportProcessingResult result = new ImportProcessingResult(20);

        // Representative DOI per publisher-less venue, first member pub with a DOI wins.
        Map<String, String> doiByBookId = new LinkedHashMap<>();
        Map<String, String> doiByForumId = new LinkedHashMap<>();
        Query chapters = new Query(new Criteria().orOperator(
                Criteria.where("scopusSubtype").in("ch", "bk"),
                Criteria.where("subtype").in("book-chapter", "book", "ch", "bk")));
        chapters.fields().include("doi", "bookId", "forumId");
        for (ScholardexPublicationFact pub : mongoTemplate.find(chapters, ScholardexPublicationFact.class)) {
            if (pub.getDoi() == null || pub.getDoi().isBlank()) {
                continue;
            }
            if (pub.getBookId() != null && !pub.getBookId().isBlank()) {
                doiByBookId.putIfAbsent(pub.getBookId(), pub.getDoi());
            } else if (pub.getForumId() != null && !pub.getForumId().isBlank()) {
                doiByForumId.putIfAbsent(pub.getForumId(), pub.getDoi());
            }
        }

        int probes = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : doiByBookId.entrySet()) {
            ScholardexBookFact book = bookFactRepository.findById(entry.getKey()).orElse(null);
            if (book == null || !isBlank(book.getPublisher())) {
                continue;
            }
            if (limit > 0 && probes >= limit) {
                break;
            }
            probes++;
            result.markProcessed();
            String publisher = crossrefClient.publisher(entry.getValue()).orElse(null);
            if (publisher == null) {
                result.markSkipped("book " + book.getId() + ": no Crossref publisher for " + entry.getValue());
                continue;
            }
            if (dryRun) {
                log.info("Publisher backfill dry-run: book={} doi={} publisher={}", book.getId(), entry.getValue(), publisher);
            } else {
                book.setPublisher(publisher);
                book.setUpdatedAt(now);
                bookFactRepository.save(book);
            }
            result.markImported();
        }

        for (Map.Entry<String, String> entry : doiByForumId.entrySet()) {
            ScholardexForumFact forum = forumFactRepository.findById(entry.getKey()).orElse(null);
            // Only series/book-typed forums: a journal's publisher is Scopus-supplied and not this sweep's business.
            if (forum == null || !isBlank(forum.getPublisher()) || !isBookish(forum.getAggregationType())) {
                continue;
            }
            if (limit > 0 && probes >= limit) {
                break;
            }
            probes++;
            result.markProcessed();
            String publisher = crossrefClient.publisher(entry.getValue()).orElse(null);
            if (publisher == null) {
                result.markSkipped("forum " + forum.getId() + ": no Crossref publisher for " + entry.getValue());
                continue;
            }
            if (dryRun) {
                log.info("Publisher backfill dry-run: forum={} ({}) doi={} publisher={}",
                        forum.getId(), forum.getName(), entry.getValue(), publisher);
            } else {
                forum.setPublisher(publisher);
                forum.setUpdatedAt(now);
                forumFactRepository.save(forum);
            }
            result.markImported();
        }

        log.info("Crossref publisher backfill ({}): probes={} filled={} unresolved={}",
                dryRun ? "dry-run" : "apply", result.getProcessedCount(), result.getImportedCount(),
                result.getProcessedCount() - result.getImportedCount());
        return result;
    }

    private static boolean isBookish(String aggregationType) {
        return "Book".equalsIgnoreCase(aggregationType) || "Book Series".equalsIgnoreCase(aggregationType);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
