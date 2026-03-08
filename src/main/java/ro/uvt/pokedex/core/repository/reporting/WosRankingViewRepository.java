package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

import java.util.Optional;

public interface WosRankingViewRepository extends MongoRepository<WosRankingView, String> {
    Optional<WosRankingView> findFirstByIssnNorm(String issnNorm);

    Optional<WosRankingView> findFirstByeIssnNorm(String eIssnNorm);

    Optional<WosRankingView> findFirstByAlternativeIssnsNormContains(String alternativeIssnNorm);
}
