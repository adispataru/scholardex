package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.WriteConcernError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.InsertManyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.Document;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopusImportEventIngestionServiceTest {

    @Mock
    private ScopusImportEventRepository repository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private MongoCollection<Document> collection;
    @Mock
    private InsertManyResult insertManyResult;

    private ScopusImportEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new ScopusImportEventIngestionService(repository, new ObjectMapper(), null);
    }

    @Test
    void ingestCreatesEventWhenPayloadHashDoesNotExist() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", "2-s2.0-123");
        payload.put("title", "Sample");

        ScopusImportEventIngestionService.EventIngestionOutcome outcome = service.ingest(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "2-s2.0-123",
                "b1",
                "c1",
                "json-object",
                payload
        );

        assertTrue(outcome.imported());
        assertFalse(outcome.error());
        ArgumentCaptor<ScopusImportEvent> captor = ArgumentCaptor.forClass(ScopusImportEvent.class);
        verify(repository).insert(captor.capture());
        ScopusImportEvent saved = captor.getValue();
        assertEquals(ScopusImportEntityType.PUBLICATION, saved.getEntityType());
        assertEquals("SCOPUS_PYTHON_AUTHOR_WORKS", saved.getSource());
        assertEquals("2-s2.0-123", saved.getSourceRecordId());
        assertEquals("b1", saved.getBatchId());
        assertEquals("c1", saved.getCorrelationId());
        assertEquals("json-object", saved.getPayloadFormat());
        assertEquals("{\"eid\":\"2-s2.0-123\",\"title\":\"Sample\"}", saved.getPayload());
        assertEquals("aaf030fb86d87a18ca0a2452922b65b90a3941d304b44a4c7fa919ce1298790b", saved.getPayloadHash());
        assertNotNull(saved.getIngestedAt());
        assertNull(outcome.message());
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

    @Test
    void ingestBatchReturnsZeroesWhenItemsNullOrEmpty() {
        ScopusImportEventIngestionService.BatchIngestionOutcome nullOutcome = service.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                null
        );
        ScopusImportEventIngestionService.BatchIngestionOutcome emptyOutcome = service.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                List.of()
        );

        assertEquals(0, nullOutcome.processed());
        assertEquals(0, nullOutcome.imported());
        assertEquals(0, nullOutcome.skipped());
        assertEquals(0, nullOutcome.errors());
        assertEquals(0, emptyOutcome.processed());
        verify(repository, never()).insert(any(ScopusImportEvent.class));
    }

    @Test
    void ingestBatchWithoutMongoCountsImportedSkippedAndErrors() {
        doAnswer(invocation -> {
            ScopusImportEvent event = invocation.getArgument(0);
            if ("dup".equals(event.getSourceRecordId())) {
                throw new org.springframework.dao.DuplicateKeyException("dup");
            }
            return event;
        }).when(repository).insert(any(ScopusImportEvent.class));

        ScopusImportEventIngestionService batchService = new ScopusImportEventIngestionService(repository, new ObjectMapper(), null);
        List<ScopusImportEventIngestionService.BatchIngestionItem> items = List.of(
                new ScopusImportEventIngestionService.BatchIngestionItem("ok", "c0", Map.of("eid", "ok")),
                new ScopusImportEventIngestionService.BatchIngestionItem("dup", "c1", Map.of("eid", "dup")),
                new ScopusImportEventIngestionService.BatchIngestionItem("err", "c2", Map.of("invalid", (Object) new Object() {
                    @SuppressWarnings("unused")
                    public String boom() {
                        throw new IllegalStateException("x");
                    }
                }))
        );

        ScopusImportEventIngestionService.BatchIngestionOutcome outcome = batchService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                items
        );

        assertEquals(3, outcome.processed());
        assertEquals(1, outcome.imported());
        assertEquals(1, outcome.skipped());
        assertEquals(1, outcome.errors());
        assertEquals(0L, outcome.dbInsertEventMs());
        assertTrue(outcome.serializeMs() >= 0L);
        assertTrue(outcome.totalMs() >= 0L);
    }

    @Test
    void ingestBatchWithMongoReturnsSerializationErrorsWhenNoItemsCanBePrepared() {
        ScopusImportEventIngestionService batchService =
                new ScopusImportEventIngestionService(repository, new ObjectMapper(), mongoTemplate);
        List<ScopusImportEventIngestionService.BatchIngestionItem> items = List.of(
                new ScopusImportEventIngestionService.BatchIngestionItem("err-1", "c1", Map.of("invalid", (Object) new Object() {
                    @SuppressWarnings("unused")
                    public String boom() {
                        throw new IllegalStateException("x");
                    }
                })),
                new ScopusImportEventIngestionService.BatchIngestionItem("err-2", "c2", Map.of("invalid", (Object) new Object() {
                    @SuppressWarnings("unused")
                    public String boom() {
                        throw new IllegalStateException("y");
                    }
                }))
        );

        ScopusImportEventIngestionService.BatchIngestionOutcome outcome = batchService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                items
        );

        assertEquals(2, outcome.processed());
        assertEquals(0, outcome.imported());
        assertEquals(0, outcome.skipped());
        assertEquals(2, outcome.errors());
        assertEquals(0L, outcome.dbInsertEventMs());
        verify(mongoTemplate, never()).getCollection(any());
    }

    @Test
    void ingestBatchWithMongoStoresPreparedDocumentsAndCountsSerializationErrors() {
        when(mongoTemplate.getCollection("scopus.import_events")).thenReturn(collection);
        when(collection.insertMany(anyList(), any())).thenReturn(insertManyResult);
        when(insertManyResult.getInsertedIds()).thenReturn(Map.of(0, new BsonInt32(1)));

        ScopusImportEventIngestionService batchService =
                new ScopusImportEventIngestionService(repository, new ObjectMapper(), mongoTemplate);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", "2-s2.0-123");
        payload.put("title", "Sample");
        List<ScopusImportEventIngestionService.BatchIngestionItem> items = List.of(
                new ScopusImportEventIngestionService.BatchIngestionItem("ok", "c1", payload),
                new ScopusImportEventIngestionService.BatchIngestionItem("err", "c2", Map.of("invalid", (Object) new Object() {
                    @SuppressWarnings("unused")
                    public String boom() {
                        throw new IllegalStateException("x");
                    }
                }))
        );

        ScopusImportEventIngestionService.BatchIngestionOutcome outcome = batchService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                items
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(collection).insertMany(docsCaptor.capture(), any());
        Document doc = docsCaptor.getValue().getFirst();
        assertEquals("PUBLICATION", doc.getString("entityType"));
        assertEquals("SCOPUS", doc.getString("source"));
        assertEquals("ok", doc.getString("sourceRecordId"));
        assertEquals("batch-1", doc.getString("batchId"));
        assertEquals("c1", doc.getString("correlationId"));
        assertEquals("json-object", doc.getString("payloadFormat"));
        assertEquals("{\"eid\":\"2-s2.0-123\",\"title\":\"Sample\"}", doc.getString("payload"));
        assertEquals("aaf030fb86d87a18ca0a2452922b65b90a3941d304b44a4c7fa919ce1298790b", doc.getString("payloadHash"));
        assertNotNull(doc.get("ingestedAt"));
        assertEquals(2, outcome.processed());
        assertEquals(1, outcome.imported());
        assertEquals(0, outcome.skipped());
        assertEquals(1, outcome.errors());
        assertTrue(outcome.serializeMs() >= 0L);
        assertTrue(outcome.dbInsertEventMs() >= 0L);
        assertTrue(outcome.totalMs() >= 0L);
    }

    @Test
    void ingestBatchWithMongoBulkWriteCountsFailedIndexesAsSkipped() {
        when(mongoTemplate.getCollection("scopus.import_events")).thenReturn(collection);
        when(collection.insertMany(anyList(), any())).thenThrow(new MongoBulkWriteException(
                BulkWriteResult.acknowledged(1, 0, 0, 0, List.of(), List.of()),
                List.of(new BulkWriteError(11000, "dup", new BsonDocument(), 1)),
                new WriteConcernError(64, "wc", "wc", new BsonDocument()),
                new ServerAddress(),
                Set.of()
        ));

        ScopusImportEventIngestionService batchService =
                new ScopusImportEventIngestionService(repository, new ObjectMapper(), mongoTemplate);
        List<ScopusImportEventIngestionService.BatchIngestionItem> items = List.of(
                new ScopusImportEventIngestionService.BatchIngestionItem("ok-1", "c1", Map.of("eid", "ok-1")),
                new ScopusImportEventIngestionService.BatchIngestionItem("dup-2", "c2", Map.of("eid", "dup-2"))
        );

        ScopusImportEventIngestionService.BatchIngestionOutcome outcome = batchService.ingestBatch(
                ScopusImportEntityType.PUBLICATION,
                "SCOPUS",
                "batch-1",
                "json-object",
                items
        );

        assertEquals(2, outcome.processed());
        assertEquals(1, outcome.imported());
        assertEquals(1, outcome.skipped());
        assertEquals(0, outcome.errors());
        assertTrue(outcome.dbInsertEventMs() >= 0L);
    }

    @Test
    void helperMethodsNormalizePayloadHashAndNanosToMillisAreDeterministic() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eid", "2-s2.0-123");
        payload.put("title", "Sample");

        assertEquals(
                "{\"eid\":\"2-s2.0-123\",\"title\":\"Sample\"}",
                ReflectionTestUtils.invokeMethod(service, "normalizePayload", payload)
        );
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ReflectionTestUtils.invokeMethod(service, "sha256Hex", "abc")
        );
        assertEquals(Long.valueOf(2L), ReflectionTestUtils.invokeMethod(service, "nanosToMillis", 2_999_999L));
    }
}
