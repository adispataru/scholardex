package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.SenseBookRanking;

import java.util.List;

public interface SenseRankingRepository extends MongoRepository<SenseBookRanking, String> {
    // Custom query methods can be added here
    List<SenseBookRanking> findAllByName(String name);
    List<SenseBookRanking> findAllByNameIgnoreCase(String name);


}
