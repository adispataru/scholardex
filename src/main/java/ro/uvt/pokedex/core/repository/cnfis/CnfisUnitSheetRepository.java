package ro.uvt.pokedex.core.repository.cnfis;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisUnitSheet;

import java.util.List;

public interface CnfisUnitSheetRepository extends MongoRepository<CnfisUnitSheet, String> {
    List<CnfisUnitSheet> findByUnitKindAndUnitIdAndReportingYearOrderByCreatedAtDesc(
            CnfisUnitSheet.UnitKind unitKind, String unitId, int reportingYear);
}
