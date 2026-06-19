package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;
import java.util.Optional;

public interface ScholardexForumFactRepository extends MongoRepository<ScholardexForumFact, String> {
    List<ScholardexForumFact> findByWosForumIdsContaining(String wosForumId);

    /** H66B Phase 4a Stage 3: resolve a publication's host venue to its canonical forum (openAlexIds is unique). */
    Optional<ScholardexForumFact> findByOpenAlexIdsContaining(String openAlexVenueId);

    /** H66B Phase 4b: resolve a DBLP conference-series stream key (conf/X) to its canonical forum (dblpIds is unique). */
    Optional<ScholardexForumFact> findByDblpIdsContaining(String dblpStreamKey);
}
