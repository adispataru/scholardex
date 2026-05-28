package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportImportSession;

import java.util.List;

@Repository
public interface ReportImportSessionRepository extends MongoRepository<ReportImportSession, String> {

    List<ReportImportSession> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<ReportImportSession> findByUserEmailAndReportDefinitionIdOrderByCreatedAtDesc(String userEmail, String reportDefinitionId);
}
