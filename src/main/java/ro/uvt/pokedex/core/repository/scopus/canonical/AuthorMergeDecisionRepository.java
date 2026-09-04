package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.AuthorMergeDecision;

/** H103 — durable explicit author merges; rows are few (tens), re-apply iterates them all. */
public interface AuthorMergeDecisionRepository extends MongoRepository<AuthorMergeDecision, String> {
}
