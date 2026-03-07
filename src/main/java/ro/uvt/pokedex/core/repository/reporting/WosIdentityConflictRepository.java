package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosIdentityConflict;

public interface WosIdentityConflictRepository extends MongoRepository<WosIdentityConflict, String> {
    Page<WosIdentityConflict> findAllBySourceVersionContainingIgnoreCaseAndSourceFileContainingIgnoreCaseAndConflictTypeContainingIgnoreCase(
            String sourceVersion,
            String sourceFile,
            String conflictType,
            Pageable pageable
    );
}
