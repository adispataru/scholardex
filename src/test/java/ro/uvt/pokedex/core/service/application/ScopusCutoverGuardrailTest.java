package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScopusCutoverGuardrailTest {

    @Test
    void projectionBackedReadFacadesDoNotCallLegacyScopusRepositories() throws Exception {
        List<Path> guardedFiles = List.of(
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/AdminScopusFacade.java"),
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
        assertTrue(processCitations.contains("importEventIngestionService.ingest("),
                "Citation processing must emit canonical import events.");
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
        Path readServiceFile = Path.of("src/main/java/ro/uvt/pokedex/core/service/application/ScopusProjectionReadService.java");
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
