package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;

import java.util.List;

@Repository
public interface OrgUnitReportRefreshEventRepository extends MongoRepository<OrgUnitReportRefreshEvent, String> {

    List<OrgUnitReportRefreshEvent> findTop20ByUnitTypeAndUnitIdAndReportDefinitionIdOrderByCreatedAtDesc(
            OrgUnitReportRefreshEvent.UnitType unitType, String unitId, String reportDefinitionId);
}
