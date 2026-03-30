package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EntityApiRouteGuardrailTest {

    @Test
    void activeEntityApiControllersDoNotReintroduceLegacyScopusRouteMappings() throws Exception {
        List<Path> guardedFiles = List.of(
                Path.of("src/main/java/ro/uvt/pokedex/core/controller/EntityAuthorApiController.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/controller/EntityForumApiController.java"),
                Path.of("src/main/java/ro/uvt/pokedex/core/controller/EntityAffiliationApiController.java")
        );

        for (Path file : guardedFiles) {
            String content = Files.readString(file);
            assertFalse(content.contains("@RequestMapping(\"/api/scopus\")"),
                    "Legacy /api/scopus controller mapping found in " + file);
            assertFalse(content.contains("\"/api/scopus"),
                    "Legacy /api/scopus route fragment found in " + file);
        }
    }
}
