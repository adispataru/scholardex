package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholardexAuthorFactRepository extends MongoRepository<ScholardexAuthorFact, String> {
    Optional<ScholardexAuthorFact> findByScopusAuthorIdsContains(String scopusAuthorId);
    // NOT Optional: an ORCID / OpenAlex id can transiently sit on several author records (the duplicate situation
    // the author-reconcile resolves), so these must tolerate multiplicity rather than throw "non unique result".
    List<ScholardexAuthorFact> findByOrcidIdsContains(String orcid);
    List<ScholardexAuthorFact> findByOpenAlexAuthorIdsContains(String openAlexAuthorId);
    List<ScholardexAuthorFact> findBySourceBatchId(String sourceBatchId);
    List<ScholardexAuthorFact> findByScopusAuthorIdsIn(Collection<String> scopusAuthorIds);
    List<ScholardexAuthorFact> findByIdIn(Collection<String> ids);
    List<ScholardexAuthorFact> findAllByNameNormalizedContaining(String nameNormalized);
    List<ScholardexAuthorFact> findAllByAffiliationIdsContaining(String affiliationId);
}
