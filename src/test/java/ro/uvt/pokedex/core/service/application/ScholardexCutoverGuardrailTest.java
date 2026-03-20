package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScholardexCutoverGuardrailTest {

    @Test
    void projectionBackedReadFacadesDoNotCallLegacyScopusRepositories() throws Exception {
        List<Path> guardedFiles = List.of(
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/UserPublicationFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/AdminInstitutionReportFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/GroupExportFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/GroupReportFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/GroupCnfisExportFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/UserRankingFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/AdminCatalogFacade.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java")
        );

        for (Path file : guardedFiles) {
            String content = Files.readString(file);
            assertNoLegacyScopusRepositoryUsage(content, file);
        }
    }

    @Test
    void schedulerTaskFlowUsesCanonicalPipelineHooks() throws Exception {
        Path schedulerFile = Path.of("src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java");
        String content = Files.readString(schedulerFile);
        assertNoLegacyScopusRepositoryUsage(content, schedulerFile);
        assertTrue(content.contains("importEventIngestionService.ingest("),
                "Scheduler must publish canonical import events.");
        assertTrue(content.contains("canonicalMaterializationService.rebuildFactsAndViews("),
                "Scheduler must trigger canonical facts/views materialization.");
    }

    @Test
    void canonicalScopusDataServiceEntrypointsDoNotDirectlyWriteLegacyEntities() throws Exception {
        Path dataServiceFile = Path.of("src/main/java/ro/uvt/pokedex/core/service/importing/ScopusDataService.java");
        String content = Files.readString(dataServiceFile);

        String publicationIngestSync = methodSlice(
                content,
                "public ImportProcessingResult importScopusDataSync(",
                "@Async(\"taskExecutor\")\n    public void importScopusDataCitations("
        );
        assertTrue(publicationIngestSync.contains("importEventIngestionService.ingest("),
                "Publication canonical ingest entrypoint must emit import events.");
        assertFalse(publicationIngestSync.contains("publicationRepository."),
                "Publication canonical ingest entrypoint must not write legacy publication repository.");
        assertFalse(publicationIngestSync.contains("authorRepository."),
                "Publication canonical ingest entrypoint must not write legacy author repository.");
        assertFalse(publicationIngestSync.contains("affiliationRepository."),
                "Publication canonical ingest entrypoint must not write legacy affiliation repository.");
        assertFalse(publicationIngestSync.contains("venueRepository."),
                "Publication canonical ingest entrypoint must not write legacy forum repository.");
        assertFalse(publicationIngestSync.contains("fundingRepository."),
                "Publication canonical ingest entrypoint must not write legacy funding repository.");

        String citationIngestSync = methodSlice(
                content,
                "public ImportProcessingResult importScopusDataCitationsSync(",
                "private Map<String, List<JsonNode>> extractCitationsFromJson("
        );
        assertTrue(citationIngestSync.contains("processCitations("),
                "Citation canonical ingest entrypoint must delegate to canonical citation processing.");
        assertFalse(citationIngestSync.contains("citationRepository."),
                "Citation canonical ingest entrypoint must not write legacy citation repository.");

        String processCitations = methodSlice(
                content,
                "private void processCitations(",
                "private void applyIngestionOutcome("
        );
        assertTrue(
                processCitations.contains("importEventIngestionService.ingest(")
                        || processCitations.contains("importEventIngestionService.ingestBatch("),
                "Citation processing must emit canonical import events (single or batched)."
        );
        assertTrue(processCitations.contains("ScopusImportEntityType.PUBLICATION"),
                "Citation processing must emit publication events for citing items.");
        assertFalse(processCitations.contains("payload.put(\"citingItem\""),
                "Citation processing must keep citation payload edge-only (no citingItem blob).");
        assertFalse(processCitations.contains("citationRepository."),
                "Citation processing must not write legacy citation repository.");
    }

    @Test
    void wosEnrichmentWritesAreRoutedThroughLinkerService() throws Exception {
        Path userReportFacade = Path.of("src/main/java/ro/uvt/pokedex/core/service/application/UserReportFacade.java");
        String userReportContent = Files.readString(userReportFacade);
        assertTrue(userReportContent.contains("publicationEnrichmentLinkerService.linkWosEnrichment("),
                "User report flow must route WoS enrichment through PublicationEnrichmentLinkerService.");
        assertFalse(userReportContent.contains("savePublicationView("),
                "User report flow must not directly persist publication enrichment.");

        Path groupCnfisFacade = Path.of("src/main/java/ro/uvt/pokedex/core/service/application/GroupCnfisExportFacade.java");
        String groupCnfisContent = Files.readString(groupCnfisFacade);
        assertTrue(groupCnfisContent.contains("publicationEnrichmentLinkerService.linkWosEnrichment("),
                "Group CNFIS flow must route WoS enrichment through PublicationEnrichmentLinkerService.");
        assertFalse(groupCnfisContent.contains("savePublicationView("),
                "Group CNFIS flow must not directly persist publication enrichment.");
    }

    @Test
    void projectionReadServiceUsesCanonicalEdgeBackedTraversals() throws Exception {
        Path readServiceFile = Path.of("src/main/java/ro/uvt/pokedex/core/service/application/ScholardexProjectionReadService.java");
        String content = Files.readString(readServiceFile);

        assertTrue(content.contains("canonicalAuthorshipFactRepository.findByAuthorIdIn"),
                "Author -> publication traversal must remain edge-backed via authorship facts.");
        assertTrue(content.contains("canonicalAuthorAffiliationFactRepository.findByAffiliationId"),
                "Affiliation -> author traversal must remain edge-backed via author-affiliation facts.");
        assertFalse(content.contains("findByAuthorIdsContaining"),
                "Author -> publication traversal must not regress to publication-view author arrays.");
        assertFalse(content.contains("findByAffiliationIdsContaining"),
                "Affiliation traversal must not regress to publication-view affiliation arrays.");
    }

    @Test
    void schedulerCitationPayloadRemainsEdgeOnly() throws Exception {
        Path schedulerFile = Path.of("src/main/java/ro/uvt/pokedex/core/service/scopus/ScopusUpdateScheduler.java");
        String content = Files.readString(schedulerFile);
        assertTrue(content.contains("ScopusImportEntityType.PUBLICATION"),
                "Scheduler citations flow must still emit publication events for citing records.");
        assertTrue(content.contains("ScopusImportEntityType.CITATION"),
                "Scheduler citations flow must emit citation edge events.");
        assertFalse(content.contains("citationPayload.put(\"citingItem\""),
                "Scheduler citation payload must remain edge-only.");
    }

    @Test
    void serviceApplicationLayerHasNoMongoReadAdapters() throws Exception {
        // After H22.6 all Mongo read adapters were deleted. This guardrail has two purposes:
        //
        // 1. "class Mongo" check — prevents re-introduction of Mongo*ReadPort adapter classes.
        //    Active services that use MongoTemplate for writes or for rankings not yet migrated
        //    (e.g. ScholardexEdgeWriterService, CoreRankingQueryService) are not named "Mongo*"
        //    and are therefore unaffected.
        //
        // 2. "Mongo callers" check — prevents stale Javadoc hints that reference a Mongo caller
        //    path that no longer exists (the exact pattern that survived the H22.6 cleanup).
        Path serviceRoot = Path.of("src/main/java/ro/uvt/pokedex/core/service/application");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(serviceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            if (content.contains("class Mongo")) {
                                violations.add("Mongo adapter class declaration in: " + file);
                            }
                            if (content.contains("Mongo callers")) {
                                violations.add("Stale 'Mongo callers' Javadoc hint in: " + file);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertTrue(violations.isEmpty(),
                "Service application layer must not contain Mongo read adapter classes or stale Javadoc:\n"
                        + String.join("\n", violations));
    }

    @Test
    void h22_4CutoverUsesPostgresStartupGuardWithoutRuntimeReadStoreToggle() throws Exception {
        String appProps = new String(
                Files.readAllBytes(Path.of("src/main/resources/application.properties")),
                StandardCharsets.ISO_8859_1
        );
        assertFalse(appProps.contains("app.reporting.read-store"),
                "Post-cutover runtime must not expose app.reporting.read-store toggle for migrated surfaces.");

        String guardContent = Files.readString(Path.of("src/main/java/ro/uvt/pokedex/core/service/application/PostgresReadCutoverGuard.java"));
        assertTrue(guardContent.contains("spring.datasource.url"),
                "Postgres cutover guard must activate with Postgres datasource presence.");
        assertTrue(guardContent.contains("projection_checkpoint"),
                "Postgres cutover must verify projection checkpoint state at startup.");
        assertTrue(guardContent.contains("slice_name IN ('wos', 'scopus')"),
                "Postgres cutover guard must enforce first-wave all-or-nothing readiness.");
    }

    private void assertNoLegacyScopusRepositoryUsage(String content, Path file) {
        assertFalse(content.contains("scopusPublicationRepository."),
                "Legacy publication repository call found in " + file);
        assertFalse(content.contains("scopusCitationRepository."),
                "Legacy citation repository call found in " + file);
        assertFalse(content.contains("scopusAuthorRepository."),
                "Legacy author repository call found in " + file);
        assertFalse(content.contains("scopusForumRepository."),
                "Legacy forum repository call found in " + file);
        assertFalse(content.contains("scopusAffiliationRepository."),
                "Legacy affiliation repository call found in " + file);
    }

    private String methodSlice(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker);
        assertTrue(start >= 0, "Missing start marker: " + startMarker);
        assertTrue(end > start, "Missing end marker after start marker: " + endMarker);
        return content.substring(start, end);
    }
}
