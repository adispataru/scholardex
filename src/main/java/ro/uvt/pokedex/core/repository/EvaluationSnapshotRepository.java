package ro.uvt.pokedex.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ro.uvt.pokedex.core.model.evaluation.EvaluationSnapshot;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationSnapshotRepository extends MongoRepository<EvaluationSnapshot, String> {

    List<EvaluationSnapshot> findByUserEmailAndReportIdOrderByCreatedAtDesc(String userEmail, String reportId);

    long countByUserEmailAndReportId(String userEmail, String reportId);

    Optional<EvaluationSnapshot> findByIdAndUserEmail(String id, String userEmail);
}
