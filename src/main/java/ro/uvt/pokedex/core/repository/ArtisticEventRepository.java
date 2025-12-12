package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.ArtisticEvent;

import java.util.List;

public interface ArtisticEventRepository extends MongoRepository<ArtisticEvent, String> {
    List<ArtisticEvent> findAllByNameIgnoreCase(String s);
}
