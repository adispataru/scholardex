package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScopusImportEventIngestionServiceTest {

    @Mock
    private ScopusImportEventRepository repository;

    private ScopusImportEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new ScopusImportEventIngestionService(repository, new ObjectMapper(), null, null);
    }

    @Test
    void ingestCreatesEventWhenPayloadHashDoesNotExist() {
        ScopusImportEventIngestionService.EventIngestionOutcome outcome = service.ingest(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "2-s2.0-123",
                "b1",
                "c1",
                "json-object",
                Map.of("eid", "2-s2.0-123", "title", "Sample")
        );

        assertTrue(outcome.imported());
        assertFalse(outcome.error());
        ArgumentCaptor<ScopusImportEvent> captor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(captor.capture());
        ScopusImportEvent saved = captor.getValue();
        assertNotNull(saved.getPayloadHash());
        assertNotNull(saved.getIngestedAt());
    }

    @Test
    void ingestSkipsWhenInsertHitsDuplicateKey() {
        doThrow(new org.springframework.dao.DuplicateKeyException("dup"))
                .when(repository).insert(any(ScopusImportEvent.class));

        ScopusImportEventIngestionService.EventIngestionOutcome outcome = service.ingest(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "2-s2.0-123",
                "b1",
                "c1",
                "json-object",
                Map.of("eid", "2-s2.0-123", "title", "Sample")
        );

        assertFalse(outcome.imported());
        assertFalse(outcome.error());
        verify(repository).insert(any(ScopusImportEvent.class));
    }

    @Test
    void ingestReturnsErrorWhenSerializationFails() {
        ScopusImportEventIngestionService.EventIngestionOutcome outcome = service.ingest(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "2-s2.0-123",
                "b1",
                "c1",
                "json-object",
                Map.of("invalid", (Object) new Object() {
                    @SuppressWarnings("unused")
                    public String boom() {
                        throw new IllegalStateException("x");
                    }
                })
        );

        assertFalse(outcome.imported());
        assertTrue(outcome.error());
        verify(repository, never()).insert(any(ScopusImportEvent.class));
    }
}
