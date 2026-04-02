package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.wos.WosImportEvent;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosImportEventRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.service.application.IncrementalUpdateUploadFacade.WosUploadSourceType;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WosIncrementalFollowUpService {

    private final WosImportEventRepository wosImportEventRepository;
    private final WosMetricFactRepository wosMetricFactRepository;
    private final WosCategoryFactRepository wosCategoryFactRepository;
    private final WosFactBuilderService wosFactBuilderService;
    private final WosProjectionBuilderService wosProjectionBuilderService;

    public ImportProcessingResult enrichCategoryRankings(
            WosUploadSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        WosFollowUpScope scope = requireScope(sourceType, sourceFile, sourceVersion);
        return wosFactBuilderService.enrichMissingCategoryRankingFieldsForSource(
                scope.sourceType(),
                scope.sourceFile(),
                scope.sourceVersion()
        );
    }

    public ImportProcessingResult rebuildProjections(
            WosUploadSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        WosFollowUpScope scope = requireScope(sourceType, sourceFile, sourceVersion);
        List<ro.uvt.pokedex.core.model.reporting.wos.WosMetricFact> lineageMetricFacts =
                wosMetricFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                        scope.sourceType(),
                        scope.sourceFile(),
                        scope.sourceVersion()
                );
        List<ro.uvt.pokedex.core.model.reporting.wos.WosCategoryFact> lineageCategoryFacts =
                wosCategoryFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                        scope.sourceType(),
                        scope.sourceFile(),
                        scope.sourceVersion()
                );
        Set<String> affectedJournalIds = resolveAffectedJournalIds(scope);
        return wosProjectionBuilderService.rebuildWosProjectionsForScope(
                affectedJournalIds,
                lineageMetricFacts,
                lineageCategoryFacts
        );
    }

    private WosFollowUpScope requireScope(
            WosUploadSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
        if (sourceType == null || sourceFile == null || sourceFile.isBlank() || sourceVersion == null || sourceVersion.isBlank()) {
            throw new IllegalArgumentException("WoS upload-scoped follow-up requires the stored source type, file, and version.");
        }
        WosSourceType modelSourceType = toModelSourceType(sourceType);
        List<WosImportEvent> importEvents = wosImportEventRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                modelSourceType,
                sourceFile,
                sourceVersion
        );
        if (importEvents.isEmpty()) {
            throw new IllegalArgumentException("No uploaded WoS lineage was found for the stored file context. Upload the WoS file again before running follow-up maintenance.");
        }
        return new WosFollowUpScope(modelSourceType, sourceFile, sourceVersion);
    }

    private Set<String> resolveAffectedJournalIds(WosFollowUpScope scope) {
        Set<String> affectedJournalIds = new LinkedHashSet<>();
        wosMetricFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                        scope.sourceType(),
                        scope.sourceFile(),
                        scope.sourceVersion()
                ).stream()
                .map(fact -> fact.getJournalId())
                .filter(journalId -> journalId != null && !journalId.isBlank())
                .forEach(affectedJournalIds::add);
        wosCategoryFactRepository.findAllBySourceTypeAndSourceFileAndSourceVersion(
                        scope.sourceType(),
                        scope.sourceFile(),
                        scope.sourceVersion()
                ).stream()
                .map(fact -> fact.getJournalId())
                .filter(journalId -> journalId != null && !journalId.isBlank())
                .forEach(affectedJournalIds::add);
        return affectedJournalIds;
    }

    private WosSourceType toModelSourceType(WosUploadSourceType sourceType) {
        return switch (sourceType) {
            case OFFICIAL_JSON -> WosSourceType.OFFICIAL_WOS_EXTRACT;
            case GOVERNMENT_EXCEL -> WosSourceType.GOV_AIS_RIS;
        };
    }

    private record WosFollowUpScope(
            WosSourceType sourceType,
            String sourceFile,
            String sourceVersion
    ) {
    }
}
