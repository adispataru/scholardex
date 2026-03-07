package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosFactConflict;

public interface WosFactConflictRepository extends MongoRepository<WosFactConflict, String> {
    Page<WosFactConflict> findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCase(
            String factType,
            String conflictReason,
            Pageable pageable
    );

    Page<WosFactConflict>
    findAllByFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndWinnerSourceVersionContainingIgnoreCaseOrFactTypeContainingIgnoreCaseAndConflictReasonContainingIgnoreCaseAndLoserSourceVersionContainingIgnoreCase(
            String factTypeWinner,
            String conflictReasonWinner,
            String winnerSourceVersion,
            String factTypeLoser,
            String conflictReasonLoser,
            String loserSourceVersion,
            Pageable pageable
    );
}
