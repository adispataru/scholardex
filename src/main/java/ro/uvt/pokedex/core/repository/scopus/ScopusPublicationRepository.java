package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;

public interface ScopusPublicationRepository extends MongoRepository<Publication, String> {
    Optional<Publication> findByEid(String eid);
    Optional<Publication> findByDoi(String doi);
    List<Publication> findAllByAuthorsContaining(String author);
    List<Publication> findAllByAffiliationsContaining(String affiliation);
    List<Publication> findAllByEidIn(List<String> ids);
    List<Publication> findAllByIdIn(List<String> ids);
//    @Query("{ 'authors': { $in: ?0 } }")
    List<Publication> findAllByAuthorsIn(List<String> author);
    List<Publication> findByTitleContains(String title);
    List<Publication> findByTitleContainsOrderByCoverDateDesc(String title);

    List<Publication> findAllByAuthorsInAndTitleContains(List<String> authorIds, String paperTitle);

    Optional<Publication> findTopByAuthorsContainsOrderByCoverDateDesc(String authorScopusId);
}
