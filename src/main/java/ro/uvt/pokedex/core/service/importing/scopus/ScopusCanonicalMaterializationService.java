package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

@Service
@RequiredArgsConstructor
public class ScopusCanonicalMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(ScopusCanonicalMaterializationService.class);

    private final ScopusFactBuilderService factBuilderService;
    private final ScopusProjectionBuilderService projectionBuilderService;

    public void rebuildFactsAndViews(String trigger) {
        rebuildFactsAndViews(trigger, null);
    }

    public void rebuildFactsAndViews(String trigger, String batchId) {
        ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents(batchId);
        ImportProcessingResult projectionResult = projectionBuilderService.rebuildViews();
        log.info("Scopus canonical materialization complete: trigger={}, batchId={}, factProcessed={}, factErrors={}, projectionProcessed={}, projectionErrors={}",
                trigger,
                batchId,
                factResult.getProcessedCount(),
                factResult.getErrorCount(),
                projectionResult.getProcessedCount(),
                projectionResult.getErrorCount());
    }
}
