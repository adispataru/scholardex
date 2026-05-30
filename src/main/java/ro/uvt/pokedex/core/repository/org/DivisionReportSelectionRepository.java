package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.DivisionReportSelection;

import java.util.List;
import java.util.Optional;

@Repository
public interface DivisionReportSelectionRepository extends MongoRepository<DivisionReportSelection, String> {

    List<DivisionReportSelection> findByDivisionId(String divisionId);

    List<DivisionReportSelection> findByDivisionIdIn(Iterable<String> divisionIds);

    Optional<DivisionReportSelection> findByDivisionIdAndReportId(String divisionId, String reportId);

    void deleteByDivisionIdAndReportId(String divisionId, String reportId);

    void deleteByReportId(String reportId);
}
