package ro.uvt.pokedex.core.repository.cnfis;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetHeader;

import java.util.Optional;

public interface CnfisSheetHeaderRepository extends MongoRepository<CnfisSheetHeader, String> {
    Optional<CnfisSheetHeader> findByUserEmailAndReportingYear(String userEmail, int reportingYear);
}
