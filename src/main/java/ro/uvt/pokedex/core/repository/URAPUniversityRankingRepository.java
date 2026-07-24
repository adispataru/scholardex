package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;

import java.util.List;

public interface URAPUniversityRankingRepository extends MongoRepository<URAPUniversityRanking, String> {
    List<URAPUniversityRanking> findByNameIgnoreCase(String name);

    /** University picker autocomplete (H83 S4). */
    List<URAPUniversityRanking> findTop10ByNameContainingIgnoreCaseOrderByNameAsc(String fragment);
}
