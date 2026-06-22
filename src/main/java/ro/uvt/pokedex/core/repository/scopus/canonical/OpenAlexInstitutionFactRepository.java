package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;

/**
 * H75 S1.0 — repository for the OpenAlex institution source facts the V2 affiliation builder derives the ROR
 * backbone from.
 */
public interface OpenAlexInstitutionFactRepository extends MongoRepository<OpenAlexInstitutionFact, String> {
}
