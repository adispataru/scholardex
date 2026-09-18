package ro.uvt.pokedex.core.repository.issn;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.issn.IssnVerification;

import java.util.List;

public interface IssnVerificationRepository extends MongoRepository<IssnVerification, String> {
    List<IssnVerification> findByStatus(IssnVerification.Status status);
}
