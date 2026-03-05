package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;

import java.util.Optional;

@Repository
public interface GroupIndividualReportRunRepository extends MongoRepository<GroupIndividualReportRun, String> {

    Optional<GroupIndividualReportRun> findTopByGroupIdAndReportDefinitionIdOrderByCreatedAtDesc(String groupId, String reportDefinitionId);
}
