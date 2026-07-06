package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserIndividualReportRunRepository extends MongoRepository<UserIndividualReportRun, String> {

    Optional<UserIndividualReportRun> findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(String userEmail, String reportDefinitionId);

    /** Latest run strictly before {@code before} — the baseline lookup for org-unit delta views. */
    Optional<UserIndividualReportRun> findTopByUserEmailAndReportDefinitionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            String userEmail, String reportDefinitionId, java.time.Instant before);

    List<UserIndividualReportRun> findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(String userEmail, String reportDefinitionId);

    long deleteByUserEmail(String userEmail);
}
