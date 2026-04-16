package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicationAuthorshipDecisionRepository extends MongoRepository<PublicationAuthorshipDecision, String> {
    Optional<PublicationAuthorshipDecision> findByUserEmailAndPublicationId(String userEmail, String publicationId);
    List<PublicationAuthorshipDecision> findByUserEmailOrderByUpdatedAtDesc(String userEmail);
    List<PublicationAuthorshipDecision> findByUserEmailAndPublicationIdIn(String userEmail, Collection<String> publicationIds);
    long deleteByUserEmailAndPublicationId(String userEmail, String publicationId);
}
