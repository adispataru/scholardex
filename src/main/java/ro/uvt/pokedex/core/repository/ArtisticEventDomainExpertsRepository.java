package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.ArtisticEventDomainExperts;

public interface ArtisticEventDomainExpertsRepository extends MongoRepository<ArtisticEventDomainExperts, String> {
}
