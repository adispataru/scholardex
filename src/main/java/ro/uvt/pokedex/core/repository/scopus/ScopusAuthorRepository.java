package ro.uvt.pokedex.core.repository.scopus;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;

import java.util.Collection;
import java.util.List;

public interface ScopusAuthorRepository extends MongoRepository<Author, String> {
    List<Author> findAllByAffiliationsContaining(Affiliation af);
    List<Author> findByIdIn(Collection<String> authorId);

    List<Author> findAllByNameContainingIgnoreCase(String authorName);
}
