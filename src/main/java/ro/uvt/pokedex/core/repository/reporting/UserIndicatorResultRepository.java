package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;

import java.util.Optional;

@Repository
public interface UserIndicatorResultRepository extends MongoRepository<UserIndicatorResult, String> {

    Optional<UserIndicatorResult> findByUserEmailAndIndicatorIdAndMode(String userEmail, String indicatorId, UserIndicatorResult.Mode mode);
}
