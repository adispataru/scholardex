package ro.uvt.pokedex.core.repository.cnfis;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;

import java.util.List;

public interface CnfisSheetSnapshotRepository extends MongoRepository<CnfisSheetSnapshot, String> {
    List<CnfisSheetSnapshot> findByUserEmailAndReportingYearOrderByCreatedAtDesc(String userEmail, int reportingYear);
    List<CnfisSheetSnapshot> findByUserEmailInAndReportingYear(java.util.Collection<String> userEmails, int reportingYear);
    List<CnfisSheetSnapshot> findByLockedByUnitSheetId(String unitSheetId);
}
