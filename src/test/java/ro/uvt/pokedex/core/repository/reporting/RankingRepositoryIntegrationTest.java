package ro.uvt.pokedex.core.repository.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataMongoTest
class RankingRepositoryIntegrationTest extends MongoIntegrationTestBase {

    @Autowired
    private RankingRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void findAllByEIssnReturnsMatchingRankings() {
        repository.save(ranking("r1", "1111-1111", "2222-2222"));
        repository.save(ranking("r2", "3333-3333", "4444-4444"));

        List<WoSRanking> results = repository.findAllByEIssn("2222-2222");

        assertEquals(1, results.size());
        assertEquals("r1", results.getFirst().getId());
    }

    private static WoSRanking ranking(String id, String issn, String eIssn) {
        WoSRanking ranking = new WoSRanking();
        ranking.setId(id);
        ranking.setIssn(issn);
        ranking.setEIssn(eIssn);
        ranking.setScore(new WoSRanking.Score());
        ranking.setWebOfScienceCategoryIndex(Map.of());
        return ranking;
    }
}
