package ro.uvt.pokedex.core.repository.doaj;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;

import java.util.Collection;
import java.util.List;

public interface DoajJournalFactRepository extends MongoRepository<DoajJournalFact, String> {
    List<DoajJournalFact> findByIdIn(Collection<String> ids);
}
