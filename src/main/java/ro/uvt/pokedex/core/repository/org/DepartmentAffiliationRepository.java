package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.DepartmentAffiliation;

import java.util.List;

@Repository
public interface DepartmentAffiliationRepository extends MongoRepository<DepartmentAffiliation, String> {
    List<DepartmentAffiliation> findByUserIdAndValidToIsNull(String userId);

    List<DepartmentAffiliation> findByDepartmentIdAndValidToIsNull(String departmentId);

    List<DepartmentAffiliation> findByUserIdInAndValidToIsNull(Iterable<String> userIds);
}
