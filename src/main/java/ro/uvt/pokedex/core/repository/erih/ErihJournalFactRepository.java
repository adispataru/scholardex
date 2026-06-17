package ro.uvt.pokedex.core.repository.erih;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;

import java.util.Collection;
import java.util.List;

public interface ErihJournalFactRepository extends MongoRepository<ErihJournalFact, String> {
    List<ErihJournalFact> findByIdIn(Collection<String> ids);
}
