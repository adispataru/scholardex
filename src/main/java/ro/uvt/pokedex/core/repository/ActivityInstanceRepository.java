package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;

import java.util.List;

public interface ActivityInstanceRepository extends MongoRepository<ActivityInstance, String> {
    List<ActivityInstance> findAllByResearcherId(String researcherId);

    /** H142 — the import keys a person already has, to skip what an import already brought. */
    List<ActivityInstance> findAllByResearcherIdAndImportKeyIn(String researcherId, java.util.Collection<String> importKeys);

    /** H143 — declared books whose request for a publisher category is in one of these states (heads decide them). */
    List<ActivityInstance> findByPublisherClaim_StatusIn(
            java.util.Collection<ro.uvt.pokedex.core.model.activities.PublisherClaim.Status> statuses);

    /** H142 slice 3 — every record that names an artistic event (the experts' queue is built from them). */
    @org.springframework.data.mongodb.repository.Query("{'referenceFields.EVENT_NAME': {$exists: true, $nin: [null, '']}}")
    List<ActivityInstance> findAllNamingAnEvent();
}
