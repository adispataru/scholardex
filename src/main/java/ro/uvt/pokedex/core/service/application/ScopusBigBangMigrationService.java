package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumSearchViewRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScopusBigBangMigrationService {

    @Value("${scopus.data.file}")
    private String scopusDataFile;

    private final ScopusDataService scopusDataService;
    private final ScopusFactBuilderService scopusFactBuilderService;
    private final ScopusProjectionBuilderService scopusProjectionBuilderService;
    private final ScopusCanonicalIndexMaintenanceService scopusCanonicalIndexMaintenanceService;
    private final ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    private final ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final ScholardexPublicationBackfillService publicationBackfillService;
    private final ScopusImportEventRepository importEventRepository;
    private final ScopusPublicationFactRepository publicationFactRepository;
    private final ScopusCitationFactRepository citationFactRepository;
    private final ScopusForumFactRepository forumFactRepository;
    private final ScopusAuthorFactRepository authorFactRepository;
    private final ScopusAffiliationFactRepository affiliationFactRepository;
    private final ScopusForumSearchViewRepository forumSearchViewRepository;
    private final ScopusAuthorSearchViewRepository authorSearchViewRepository;
    private final ScopusAffiliationSearchViewRepository affiliationSearchViewRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    private final ScholardexPublicationViewRepository publicationViewRepository;

    public ScopusBigBangMigrationResult runIngestStep() {
        Instant startedAt = Instant.now();
        ImportProcessingResult publicationImport = scopusDataService.importScopusDataSync(scopusDataFile, 0, false);
        ImportProcessingResult citationImport = scopusDataService.importScopusDataCitationsSync(scopusDataFile);
        MigrationStepResult ingest = mergeImportResults("ingest", publicationImport, citationImport);
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                ingest,
                null,
                null,
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runBuildFactsStep() {
        Instant startedAt = Instant.now();
        ImportProcessingResult facts = scopusFactBuilderService.buildFactsFromImportEvents();
        affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts();
        authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts();
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts();
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                MigrationStepResult.executed("build-facts", facts),
                null,
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runBuildProjectionsStep() {
        Instant startedAt = Instant.now();
        ImportProcessingResult projections = scopusProjectionBuilderService.rebuildViews();
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                null,
                MigrationStepResult.executed("build-projections", projections),
                null,
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runEnsureIndexesStep() {
        Instant startedAt = Instant.now();
        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult indexResult =
                scopusCanonicalIndexMaintenanceService.ensureIndexes();
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                null,
                null,
                null,
                new IndexStepResult(
                        true,
                        indexResult.created().size(),
                        indexResult.present().size(),
                        indexResult.invalid().size(),
                        indexResult.errors().size(),
                        indexResult.invalid(),
                        indexResult.errors()
                ),
                buildVerificationSummary()
        );
    }

    public ScopusBigBangMigrationResult runFull() {
        Instant startedAt = Instant.now();
        ImportProcessingResult publicationImport = scopusDataService.importScopusDataSync(scopusDataFile, 0, false);
        ImportProcessingResult citationImport = scopusDataService.importScopusDataCitationsSync(scopusDataFile);
        ImportProcessingResult facts = scopusFactBuilderService.buildFactsFromImportEvents();
        affiliationCanonicalizationService.rebuildCanonicalAffiliationFactsFromScopusFacts();
        authorCanonicalizationService.rebuildCanonicalAuthorFactsFromScopusFacts();
        publicationCanonicalizationService.rebuildCanonicalPublicationFactsFromScopusFacts();
        ImportProcessingResult projections = scopusProjectionBuilderService.rebuildViews();
        ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult indexResult =
                scopusCanonicalIndexMaintenanceService.ensureIndexes();
        return new ScopusBigBangMigrationResult(
                scopusDataFile,
                startedAt,
                Instant.now(),
                mergeImportResults("ingest", publicationImport, citationImport),
                MigrationStepResult.executed("build-facts", facts),
                MigrationStepResult.executed("build-projections", projections),
                new IndexStepResult(
                        true,
                        indexResult.created().size(),
                        indexResult.present().size(),
                        indexResult.invalid().size(),
                        indexResult.errors().size(),
                        indexResult.invalid(),
                        indexResult.errors()
                ),
                buildVerificationSummary()
        );
    }

    private VerificationSummary buildVerificationSummary() {
        return new VerificationSummary(
                importEventRepository.count(),
                publicationFactRepository.count(),
                scholardexPublicationFactRepository.count(),
                citationFactRepository.count(),
                forumFactRepository.count(),
                authorFactRepository.count(),
                affiliationFactRepository.count(),
                forumSearchViewRepository.count(),
                authorSearchViewRepository.count(),
                affiliationSearchViewRepository.count(),
                scholardexSourceLinkRepository.count(),
                publicationViewRepository.count()
        );
    }

    private MigrationStepResult mergeImportResults(
            String stepName,
            ImportProcessingResult publicationImport,
            ImportProcessingResult citationImport
    ) {
        List<String> samples = new ArrayList<>();
        samples.addAll(publicationImport.getErrorsSample());
        samples.addAll(citationImport.getErrorsSample());
        return new MigrationStepResult(
                stepName,
                true,
                publicationImport.getProcessedCount() + citationImport.getProcessedCount(),
                publicationImport.getImportedCount() + citationImport.getImportedCount(),
                publicationImport.getUpdatedCount() + citationImport.getUpdatedCount(),
                publicationImport.getSkippedCount() + citationImport.getSkippedCount(),
                publicationImport.getErrorCount() + citationImport.getErrorCount(),
                null,
                samples
        );
    }

    public record ScopusBigBangMigrationResult(
            String dataFile,
            Instant startedAt,
            Instant completedAt,
            MigrationStepResult ingest,
            MigrationStepResult buildFacts,
            MigrationStepResult buildProjections,
            IndexStepResult ensureIndexes,
            VerificationSummary verification
    ) {
    }

    public record MigrationStepResult(
            String stepName,
            boolean executed,
            int processed,
            int imported,
            int updated,
            int skipped,
            int errors,
            String note,
            List<String> samples
    ) {
        static MigrationStepResult executed(String stepName, ImportProcessingResult result) {
            return new MigrationStepResult(
                    stepName,
                    true,
                    result.getProcessedCount(),
                    result.getImportedCount(),
                    result.getUpdatedCount(),
                    result.getSkippedCount(),
                    result.getErrorCount(),
                    null,
                    result.getErrorsSample()
            );
        }
    }

    public record IndexStepResult(
            boolean executed,
            int created,
            int present,
            int invalid,
            int errors,
            List<String> invalidSamples,
            List<String> errorSamples
    ) {
    }

    public record VerificationSummary(
            long importEvents,
            long publicationFacts,
            long canonicalPublicationFacts,
            long citationFacts,
            long forumFacts,
            long authorFacts,
            long affiliationFacts,
            long forumViews,
            long authorViews,
            long affiliationViews,
            long publicationSourceLinks,
            long publicationViews
    ) {
    }

    public ImportProcessingResult runPublicationIdentityBackfill() {
        return publicationBackfillService.backfillFromLegacyProjection();
    }
}
