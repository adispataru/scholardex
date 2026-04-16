package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.UserIndividualReportRun;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserIndividualReportRunRepository extends MongoRepository<UserIndividualReportRun, String> {

    Optional<UserIndividualReportRun> findTopByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(String userEmail, String reportDefinitionId);

    List<UserIndividualReportRun> findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(String userEmail, String reportDefinitionId);
}
