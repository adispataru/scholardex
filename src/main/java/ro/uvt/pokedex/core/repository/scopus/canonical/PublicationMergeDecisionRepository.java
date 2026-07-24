package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;

import java.util.List;
import java.util.Optional;

public interface PublicationMergeDecisionRepository extends MongoRepository<PublicationMergeDecision, String> {
    Optional<PublicationMergeDecision> findByPairKey(String pairKey);
    List<PublicationMergeDecision> findByStatus(PublicationMergeDecision.Status status);
    List<PublicationMergeDecision> findByStatusOrderByUpdatedAtDesc(PublicationMergeDecision.Status status);
    List<PublicationMergeDecision> findAllByOrderByUpdatedAtDesc();
}
