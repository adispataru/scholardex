package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.WoSRanking;

import java.util.List;

public interface RankingRepository extends MongoRepository<WoSRanking, String> {
    // Custom query methods can be added here
    List<WoSRanking> findAllByIssn(String issn);
    List<WoSRanking> findAllByeIssn(String eIssn);


    List<WoSRanking> findAllById(String s);
    List<WoSRanking>  findAllByWebOfScienceCategoryIndex(String webOfScienceCategoryIndex);
}
