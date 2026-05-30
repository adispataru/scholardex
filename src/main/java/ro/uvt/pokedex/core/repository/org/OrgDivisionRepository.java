package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.DivisionType;
import ro.uvt.pokedex.core.model.org.OrgDivision;

import java.util.List;

@Repository
public interface OrgDivisionRepository extends MongoRepository<OrgDivision, String> {
    List<OrgDivision> findByInstitutionId(String institutionId);

    List<OrgDivision> findByInstitutionIdAndType(String institutionId, DivisionType type);

    List<OrgDivision> findByHeadUserIdsContaining(String userId);
}
