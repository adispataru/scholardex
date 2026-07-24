package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.UniversityRanking;

import java.util.List;

public interface UniversityRankingRepository extends MongoRepository<UniversityRanking, String> {
    long countBySource(String source);
    List<UniversityRanking> findByNameIgnoreCase(String name);
    List<UniversityRanking> findBySourceAndNameIgnoreCase(String source, String name);

    /** University picker autocomplete (H83 S4) — returns per-(source,name) docs; the facade groups. */
    List<UniversityRanking> findTop20ByNameContainingIgnoreCaseOrderByNameAsc(String fragment);
}
