package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;

import java.util.Collection;
import java.util.List;

public interface ScholardexBookFactRepository extends MongoRepository<ScholardexBookFact, String> {
    List<ScholardexBookFact> findByIdIn(Collection<String> ids);

    // H99 item 7 — wizard book search over the ~477k-row book list: title substring + exact-ISBN lookups.
    List<ScholardexBookFact> findTop20ByTitleContainingIgnoreCaseOrderByTitleAsc(String title);

    /** Word-based title search via the {@code text_scholardex_book_title} index (throws until it exists —
     *  the facade falls back to the regex scan). */
    @org.springframework.data.mongodb.repository.Query("{ $text: { $search: ?0 } }")
    List<ScholardexBookFact> searchByTitleText(String query, org.springframework.data.domain.Pageable pageable);

    List<ScholardexBookFact> findByPrintIsbnOrElectronicIsbn(String printIsbn, String electronicIsbn);
}
