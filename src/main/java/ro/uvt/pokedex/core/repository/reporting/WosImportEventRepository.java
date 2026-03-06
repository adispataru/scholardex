package ro.uvt.pokedex.core.repository.reporting;

import org.springframework.data.mongodb.repository.MongoRepository;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;

import java.util.List;
import java.util.Optional;

public interface WosImportEventRepository extends MongoRepository<WosImportEvent, String> {
    Optional<WosImportEvent> findBySourceTypeAndSourceFileAndSourceVersionAndSourceRowItem(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion,
            String sourceRowItem
    );

    List<WosImportEvent> findAllBySourceTypeAndSourceFileAndSourceVersion(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion
    );
}
