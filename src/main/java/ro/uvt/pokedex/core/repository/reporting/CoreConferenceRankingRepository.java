package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;

import java.util.List;

public interface CoreConferenceRankingRepository extends MongoRepository<CoreConferenceRanking, String> {
    // Custom query methods can be added here
    List<CoreConferenceRanking> findAllByName(String name);
    List<CoreConferenceRanking> findAllByAcronym(String acronym);
    List<CoreConferenceRanking> findAllByAcronymIgnoreCase(String acronym);
    List<CoreConferenceRanking> findAllByNameAndAcronym(String name, String acronym);


}
