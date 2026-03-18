package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.repository.scopus.*;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ScopusDataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopusDataService service = new ScopusDataService(
            mock(ScopusPublicationRepository.class),
            mock(ScopusCitationRepository.class),
            mock(ScopusAffiliationRepository.class),
            mock(ScopusAuthorRepository.class),
            mock(ScopusForumRepository.class),
            mock(ScopusFundingRepository.class),
            mock(CacheService.class),
            mock(ScopusImportEventRepository.class),
            mock(ScopusImportEventIngestionService.class),
            mock(ScopusCanonicalMaterializationService.class)
    );

    @Test
    void createPublicationFromJsonThrowsOnMissingRequiredEid() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", "Test");

        assertThrows(IntegrationException.class, () -> service.createPublicationFromJson(node));
    }

    @Test
    void createPublicationFromJsonParsesRequiredFields() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eid", "2-s2.0-123");
        node.put("title", "Test title");
        node.put("author_count", 1);
        node.put("citedby_count", 0);
        node.put("openaccess", 0);

        assertEquals("2-s2.0-123", service.createPublicationFromJson(node).getEid());
    }
}
