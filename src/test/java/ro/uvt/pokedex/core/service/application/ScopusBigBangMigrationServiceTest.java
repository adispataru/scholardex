package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.ScopusDataService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAffiliationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexAuthorCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCanonicalBuildCheckpointService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexCitationCanonicalizationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusBigBangMigrationServiceTest {

    @Mock private ScopusDataService scopusDataService;
    @Mock private ScopusFactBuilderService scopusFactBuilderService;
    @Mock private ScopusProjectionBuilderService scopusProjectionBuilderService;
    @Mock private ScopusCanonicalIndexMaintenanceService indexMaintenanceService;
    @Mock private ScholardexAffiliationCanonicalizationService affiliationCanonicalizationService;
    @Mock private ScholardexAuthorCanonicalizationService authorCanonicalizationService;
    @Mock private ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    @Mock private ScholardexCitationCanonicalizationService citationCanonicalizationService;
    @Mock private ScholardexCanonicalBuildCheckpointService canonicalBuildCheckpointService;
    @Mock private ScholardexSourceLinkService sourceLinkService;
    @Mock private ScholardexEdgeReconciliationService edgeReconciliationService;
    @Mock private ScopusImportEventRepository importEventRepository;
    @Mock private ScopusPublicationFactRepository publicationFactRepository;
    @Mock private ScopusCitationFactRepository citationFactRepository;
    @Mock private ScopusForumFactRepository forumFactRepository;
    @Mock private ScopusAuthorFactRepository authorFactRepository;
    @Mock private ScopusAffiliationFactRepository affiliationFactRepository;
    @Mock private ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    @Mock private ScholardexCitationFactRepository scholardexCitationFactRepository;
    @Mock private ScholardexSourceLinkRepository scholardexSourceLinkRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private MongoTemplate mongoTemplate;

    private ScopusBigBangMigrationService service;

    @BeforeEach
    void setUp() {
        service = new ScopusBigBangMigrationService(
                scopusDataService,
                scopusFactBuilderService,
                scopusProjectionBuilderService,
                indexMaintenanceService,
                affiliationCanonicalizationService,
                authorCanonicalizationService,
                publicationCanonicalizationService,
                citationCanonicalizationService,
                canonicalBuildCheckpointService,
                sourceLinkService,
                edgeReconciliationService,
                importEventRepository,
                publicationFactRepository,
                citationFactRepository,
                forumFactRepository,
                authorFactRepository,
                affiliationFactRepository,
                scholardexPublicationFactRepository,
                scholardexCitationFactRepository,
                scholardexSourceLinkRepository,
                jdbcTemplate,
                mongoTemplate
        );
        ReflectionTestUtils.setField(service, "scopusDataFile", "/tmp/scopus.json");
    }

    @Test
    void runFullAggregatesStepAndVerificationSummaries() {
        ImportProcessingResult publications = result(10, 4, 0, 6, 0);
        ImportProcessingResult citations = result(8, 3, 0, 5, 0);
        ImportProcessingResult facts = result(18, 9, 5, 4, 0);
        ImportProcessingResult views = result(20, 20, 0, 0, 0);

        when(scopusDataService.importScopusDataSync("/tmp/scopus.json", 0, false)).thenReturn(publications);
        when(scopusDataService.importScopusDataCitationsSync("/tmp/scopus.json")).thenReturn(citations);
        when(scopusFactBuilderService.buildFactsFromImportEvents()).thenReturn(facts);
        when(scopusProjectionBuilderService.rebuildViews()).thenReturn(views);
        when(indexMaintenanceService.ensureIndexes()).thenReturn(
                new ScopusCanonicalIndexMaintenanceService.ScopusCanonicalIndexEnsureResult(
                        List.of("c1"), List.of("p1"), List.of(), List.of()
                )
        );
        when(importEventRepository.count()).thenReturn(100L);
        when(publicationFactRepository.count()).thenReturn(50L);
        when(citationFactRepository.count()).thenReturn(80L);
        when(scholardexPublicationFactRepository.count()).thenReturn(55L);
        when(scholardexCitationFactRepository.count()).thenReturn(78L);
        when(forumFactRepository.count()).thenReturn(10L);
        when(authorFactRepository.count()).thenReturn(40L);
        when(affiliationFactRepository.count()).thenReturn(12L);
        when(scholardexSourceLinkRepository.count()).thenReturn(77L);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class)
        )).thenReturn(50L);

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult full = service.runFull();

        assertEquals(18, full.ingest().processed());
        assertEquals(7, full.ingest().imported());
        assertEquals(1, full.ensureIndexes().created());
        assertEquals(1, full.ensureIndexes().present());
        assertEquals(100L, full.verification().importEvents());
        assertEquals(78L, full.verification().canonicalCitationFacts());
        assertEquals(50L, full.verification().publicationViews());
    }

    @Test
    void incrementalUploadProjectionStepUsesBatchScopedProjectionBuilder() {
        ImportProcessingResult projections = result(3, 2, 1, 0, 0);
        when(scopusProjectionBuilderService.rebuildViewsForBatch("upload-batch-7")).thenReturn(projections);
        stubVerificationSummary();

        ScopusBigBangMigrationService.ScopusBigBangMigrationResult run = service.runIncrementalUploadProjectionStep("upload-batch-7");

        assertEquals(3, run.buildProjections().processed());
        verify(scopusProjectionBuilderService).rebuildViewsForBatch("upload-batch-7");
        verify(scopusProjectionBuilderService, never()).rebuildViews();
    }

    @Test
    void incrementalUploadEdgeReconcileUsesBatchScopedEdgeService() {
        ImportProcessingResult edgeResult = result(2, 0, 1, 1, 0);
        when(edgeReconciliationService.reconcileEdges("upload-batch-7")).thenReturn(edgeResult);

        ImportProcessingResult run = service.runIncrementalUploadEdgeReconcileStep("upload-batch-7");

        assertEquals(1, run.getUpdatedCount());
        verify(edgeReconciliationService).reconcileEdges("upload-batch-7");
    }

    private ImportProcessingResult result(int processed, int imported, int updated, int skipped, int errors) {
        ImportProcessingResult result = new ImportProcessingResult(10);
        for (int i = 0; i < processed; i++) {
            result.markProcessed();
        }
        for (int i = 0; i < imported; i++) {
            result.markImported();
        }
        for (int i = 0; i < updated; i++) {
            result.markUpdated();
        }
        for (int i = 0; i < skipped; i++) {
            result.markSkipped("s" + i);
        }
        for (int i = 0; i < errors; i++) {
            result.markError("e" + i);
        }
        return result;
    }

    private void stubVerificationSummary() {
        when(importEventRepository.count()).thenReturn(0L);
        when(publicationFactRepository.count()).thenReturn(0L);
        when(citationFactRepository.count()).thenReturn(0L);
        when(scholardexPublicationFactRepository.count()).thenReturn(0L);
        when(scholardexCitationFactRepository.count()).thenReturn(0L);
        when(forumFactRepository.count()).thenReturn(0L);
        when(authorFactRepository.count()).thenReturn(0L);
        when(affiliationFactRepository.count()).thenReturn(0L);
        when(scholardexSourceLinkRepository.count()).thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class)
        )).thenReturn(0L);
    }
}
