package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceFact;

/**
 * H79 — repository for the OpenAlex source (venue) APC facts, derived offline from the works dumps and
 * consumed by the forum-membership projection to emit the {@code database='OPENALEX'} fee-journal signal.
 */
public interface OpenAlexSourceFactRepository extends MongoRepository<OpenAlexSourceFact, String> {
}
