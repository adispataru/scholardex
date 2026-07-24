package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexPublicationFactRepository extends MongoRepository<ScholardexPublicationFact, String> {
    long countByCreatedAtAfter(Instant after);
    Optional<ScholardexPublicationFact> findByEid(String eid);
    Optional<ScholardexPublicationFact> findByWosId(String wosId);
    Optional<ScholardexPublicationFact> findByGoogleScholarId(String googleScholarId);
    Optional<ScholardexPublicationFact> findByUserSourceId(String userSourceId);
    List<ScholardexPublicationFact> findBySourceBatchId(String sourceBatchId);
    List<ScholardexPublicationFact> findAllByDoiNormalized(String doiNormalized);
    List<ScholardexPublicationFact> findAllByDoiNormalizedIn(Collection<String> doiNormalizedValues);
    List<ScholardexPublicationFact> findAllByEidIn(Collection<String> eids);
    List<ScholardexPublicationFact> findByForumId(String forumId);
    List<ScholardexPublicationFact> findAllByTitleNormalized(String titleNormalized);
    List<ScholardexPublicationFact> findByAuthorIdsContains(String authorId);
}
