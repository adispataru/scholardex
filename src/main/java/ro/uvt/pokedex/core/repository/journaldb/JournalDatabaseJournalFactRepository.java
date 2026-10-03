package ro.uvt.pokedex.core.repository.journaldb;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.journaldb.JournalDatabaseJournalFact;

public interface JournalDatabaseJournalFactRepository extends MongoRepository<JournalDatabaseJournalFact, String> {

    long countByDatabase(String database);

    void deleteByDatabase(String database);
}
