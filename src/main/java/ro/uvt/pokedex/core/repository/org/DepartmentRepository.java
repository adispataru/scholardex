package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.Department;

import java.util.List;

@Repository
public interface DepartmentRepository extends MongoRepository<Department, String> {
    List<Department> findByDivisionId(String divisionId);

    List<Department> findByDivisionIdIn(Iterable<String> divisionIds);

    List<Department> findByInstitutionId(String institutionId);

    List<Department> findByIdIn(Iterable<String> ids);

    List<Department> findByHeadUserIdsContaining(String userId);
}
