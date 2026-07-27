package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicationVenueClaimRepository extends MongoRepository<PublicationVenueClaim, String> {
    Optional<PublicationVenueClaim> findByPublicationId(String publicationId);
    List<PublicationVenueClaim> findByPublicationIdIn(Collection<String> publicationIds);
    List<PublicationVenueClaim> findByStatus(PublicationVenueClaim.Status status);
    List<PublicationVenueClaim> findByStatusOrderByUpdatedAtDesc(PublicationVenueClaim.Status status);
    List<PublicationVenueClaim> findAllByOrderByUpdatedAtDesc();
}
