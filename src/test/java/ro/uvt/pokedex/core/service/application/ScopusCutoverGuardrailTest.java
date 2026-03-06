package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                Path.of("src/main/java/ro/uvt/pokedex/core/service/application/AdminCatalogFacade.java")
        );

        for (Path file : guardedFiles) {
            String content = Files.readString(file);
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
    }
}
