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
    private final ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    private final ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final ScholardexCitationCanonicalizationService citationCanonicalizationService;
    private final ScopusProjectionBuilderService projectionBuilderService;

    public void rebuildFactsAndViews(String trigger) {
        rebuildFactsAndViews(trigger, null, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId) {
        rebuildFactsAndViews(trigger, batchId, CanonicalBuildOptions.defaults());
    }

    public void rebuildFactsAndViews(String trigger, String batchId, CanonicalBuildOptions canonicalOptions) {
        ImportProcessingResult factResult = factBuilderService.buildFactsFromImportEvents(batchId);
        CanonicalBuildOptions effectiveOptions = canonicalOptions == null ? CanonicalBuildOptions.defaults() : canonicalOptions;
        ImportProcessingResult canonicalAffiliationResult = affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalAuthorResult = authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalPublicationResult = publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult canonicalCitationResult = citationCanonicalizationService.rebuildCanonicalCitationFactsFromScopusFacts(effectiveOptions);
        ImportProcessingResult projectionResult = projectionBuilderService.rebuildViews();
        log.info("Scopus canonical materialization complete: trigger={}, batchId={}, factProcessed={}, factErrors={}, canonicalAffiliationProcessed={}, canonicalAffiliationErrors={}, canonicalAffiliationBatches={}, canonicalAuthorProcessed={}, canonicalAuthorErrors={}, canonicalAuthorBatches={}, canonicalPublicationProcessed={}, canonicalPublicationErrors={}, canonicalPublicationBatches={}, canonicalCitationProcessed={}, canonicalCitationErrors={}, canonicalCitationBatches={}, projectionProcessed={}, projectionErrors={}",
                trigger,
                batchId,
                factResult.getProcessedCount(),
                factResult.getErrorCount(),
                canonicalAffiliationResult.getProcessedCount(),
                canonicalAffiliationResult.getErrorCount(),
                canonicalAffiliationResult.getBatchesProcessed(),
                canonicalAuthorResult.getProcessedCount(),
                canonicalAuthorResult.getErrorCount(),
                canonicalAuthorResult.getBatchesProcessed(),
                canonicalPublicationResult.getProcessedCount(),
                canonicalPublicationResult.getErrorCount(),
                canonicalPublicationResult.getBatchesProcessed(),
                canonicalCitationResult.getProcessedCount(),
                canonicalCitationResult.getErrorCount(),
                canonicalCitationResult.getBatchesProcessed(),
                projectionResult.getProcessedCount(),
                projectionResult.getErrorCount());
    }
}
