package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.util.List;

public interface RankingRepository extends MongoRepository<WoSRanking, String> {
    // Custom query methods can be added here
    List<WoSRanking> findAllByIssn(String issn);

    @Query("{ 'eIssn': ?0 }")
    List<WoSRanking> findAllByEIssn(String eIssn);

    List<WoSRanking> findAllById(Iterable<String> ids);
    List<WoSRanking>  findAllByWebOfScienceCategoryIndex(String webOfScienceCategoryIndex);
}
