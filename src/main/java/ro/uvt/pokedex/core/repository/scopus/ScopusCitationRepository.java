package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Optional;

public interface ScopusCitationRepository extends MongoRepository<Citation, String> {


    List<Citation> findAllByCitedIdIn(List<String> pubIds);

    Optional<Citation> findByCitedIdAndCitingId(String cited, String citing);

    List<Citation> findAllByCitedId(String id);
    long countAllByCitedId(String id);
}
