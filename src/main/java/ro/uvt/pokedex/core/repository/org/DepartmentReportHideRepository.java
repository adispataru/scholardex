package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.DepartmentReportHide;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepartmentReportHideRepository extends MongoRepository<DepartmentReportHide, String> {

    List<DepartmentReportHide> findByDepartmentId(String departmentId);

    List<DepartmentReportHide> findByDepartmentIdIn(Iterable<String> departmentIds);

    Optional<DepartmentReportHide> findByDepartmentIdAndReportId(String departmentId, String reportId);

    void deleteByDepartmentIdAndReportId(String departmentId, String reportId);

    void deleteByReportId(String reportId);
}
