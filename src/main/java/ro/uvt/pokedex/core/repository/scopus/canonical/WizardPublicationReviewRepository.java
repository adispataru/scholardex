package ro.uvt.pokedex.core.repository.scopus.canonical;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.scopus.canonical.WizardPublicationReview;

import java.util.Collection;
import java.util.List;

public interface WizardPublicationReviewRepository extends MongoRepository<WizardPublicationReview, String> {
    List<WizardPublicationReview> findByStatusIn(Collection<WizardPublicationReview.Status> statuses);
}
