package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;

import java.util.Collection;
import java.util.List;

public interface ScholardexBookFactRepository extends MongoRepository<ScholardexBookFact, String> {
    List<ScholardexBookFact> findByIdIn(Collection<String> ids);

    // H99 item 7 — wizard book search over the ~477k-row book list: title substring + exact-ISBN lookups.
    List<ScholardexBookFact> findTop20ByTitleContainingIgnoreCaseOrderByTitleAsc(String title);

    List<ScholardexBookFact> findByPrintIsbnOrElectronicIsbn(String printIsbn, String electronicIsbn);
}
