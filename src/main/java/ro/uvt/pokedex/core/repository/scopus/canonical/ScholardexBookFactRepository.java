package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact;

import java.util.Collection;
import java.util.List;

public interface ScholardexBookFactRepository extends MongoRepository<ScholardexBookFact, String> {
    List<ScholardexBookFact> findByIdIn(Collection<String> ids);
}
