package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusCanonicalMaterializationService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopusDataServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopusImportEventIngestionService importEventIngestionService = mock(ScopusImportEventIngestionService.class);
    private final ScopusDataService service = new ScopusDataService(
            mock(ScopusImportEventRepository.class),
            importEventIngestionService,
            mock(ScopusCanonicalMaterializationService.class)
    );

    @Test
    void importUploadedScopusDataSyncUsesUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-1");
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "title": ["Test title"],
                  "source_id": ["SRC-1"],
                  "publicationName": ["Forum A"],
                  "author_count": [1],
                  "citedby_count": [0],
                  "openaccess": [0]
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(eventCaptor.capture());
        assertEquals(ScopusImportEntityType.PUBLICATION, eventCaptor.getValue().getEntityType());
        assertEquals("SCOPUS_JSON_UPLOAD", eventCaptor.getValue().getSource());
        assertEquals("2-s2.0-123", eventCaptor.getValue().getSourceRecordId());
        assertEquals("upload-publication-0", eventCaptor.getValue().getCorrelationId());
        assertEquals("json-object", eventCaptor.getValue().getPayloadFormat());
    }

    @Test
    void importUploadedScopusDataSyncAcceptsRowIndexedObjectPayload() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-1");
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": {"0": "2-s2.0-123"},
                  "title": {"0": "Test title"},
                  "source_id": {"0": "SRC-1"},
                  "publicationName": {"0": "Forum A"},
                  "author_count": {"0": 1},
                  "citedby_count": {"0": 0},
                  "openaccess": {"0": 0}
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(eventCaptor.capture());
        assertEquals("2-s2.0-123", eventCaptor.getValue().getSourceRecordId());
        assertEquals("SCOPUS_JSON_UPLOAD", eventCaptor.getValue().getSource());
    }

    @Test
    void importUploadedScopusDataCitationsSyncUsesUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            event.setId("evt-" + event.getSourceRecordId());
            return event;
        });
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "citing articles": {
                    "0": [
                      {"eid": "2-s2.0-999", "title": "Citing title", "source_id": "SRC-2"}
                    ]
                  }
                }
                """;

        var result = uploadService.importUploadedScopusDataCitationsSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getImportedCount());
        ArgumentCaptor<ScopusImportEvent> eventCaptor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).insert(eventCaptor.capture());
        List<ScopusImportEvent> events = eventCaptor.getAllValues();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> "SCOPUS_JSON_UPLOAD".equals(event.getSource())));
        assertTrue(events.stream().anyMatch(event ->
                event.getEntityType() == ScopusImportEntityType.PUBLICATION && "2-s2.0-999".equals(event.getSourceRecordId())));
        assertTrue(events.stream().anyMatch(event ->
                event.getEntityType() == ScopusImportEntityType.CITATION && "2-s2.0-123->2-s2.0-999".equals(event.getSourceRecordId())));
    }

    @Test
    void importUploadedScopusDataSyncSkipsDuplicatesUnderUploadSourceLabel() {
        ScopusImportEventRepository repository = mock(ScopusImportEventRepository.class);
        when(repository.insert(any(ScopusImportEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        ScopusDataService uploadService = serviceWithRealIngestion(repository);

        String payload = """
                {
                  "eid": ["2-s2.0-123"],
                  "title": ["Test title"],
                  "source_id": ["SRC-1"],
                  "publicationName": ["Forum A"]
                }
                """;

        var result = uploadService.importUploadedScopusDataSync("scopus.json", "upload-batch-1", payload.getBytes());

        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getImportedCount());
    }

    @Test
    void importUploadedScopusDataSyncThrowsClearErrorOnMalformedJson() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.importUploadedScopusDataSync("scopus.json", "upload-batch-1", "{bad".getBytes())
        );

        assertTrue(error.getMessage().contains("Failed to parse uploaded Scopus JSON file."));
    }

    private ScopusDataService serviceWithRealIngestion(ScopusImportEventRepository repository) {
        return new ScopusDataService(
                repository,
                new ScopusImportEventIngestionService(repository, objectMapper, null),
                mock(ScopusCanonicalMaterializationService.class)
        );
    }
}
