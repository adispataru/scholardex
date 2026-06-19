package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexWorkDoi;

import java.util.Collection;
import java.util.List;

public interface OpenAlexWorkDoiRepository extends MongoRepository<OpenAlexWorkDoi, String> {
    List<OpenAlexWorkDoi> findByIdIn(Collection<String> ids);
}
