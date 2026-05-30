package ro.uvt.pokedex.core.repository.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.org.Membership;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipRepository extends MongoRepository<Membership, String> {
    List<Membership> findByGroupIdAndValidToIsNull(String groupId);

    List<Membership> findByUserIdAndValidToIsNull(String userId);

    List<Membership> findByGroupIdInAndValidToIsNull(Iterable<String> groupIds);

    Optional<Membership> findByGroupIdAndUserIdAndValidToIsNull(String groupId, String userId);
}
