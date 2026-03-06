package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

public interface WosRankingViewRepository extends MongoRepository<WosRankingView, String> {
}
