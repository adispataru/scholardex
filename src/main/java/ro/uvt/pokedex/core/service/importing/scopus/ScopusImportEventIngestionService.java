package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ScopusImportEventIngestionService {

    private final ScopusImportEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ScopusTouchQueueService touchQueueService;
    private final MongoTemplate mongoTemplate;

    public ScopusImportEventIngestionService(
            ScopusImportEventRepository repository,
            ObjectMapper objectMapper,
            @Nullable ScopusTouchQueueService touchQueueService,
            @Nullable MongoTemplate mongoTemplate
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.touchQueueService = touchQueueService;
        this.mongoTemplate = mongoTemplate;
    }

    public EventIngestionOutcome ingest(
            ScopusImportEntityType entityType,
            String source,
            String sourceRecordId,
            String batchId,
            String correlationId,
            String payloadFormat,
            Object payloadObject
    ) {
        return ingest(entityType, source, sourceRecordId, batchId, correlationId, payloadFormat, payloadObject, true);
    }

    public EventIngestionOutcome ingest(
            ScopusImportEntityType entityType,
            String source,
            String sourceRecordId,
            String batchId,
            String correlationId,
            String payloadFormat,
            Object payloadObject,
            boolean emitTouchQueue
    ) {
        try {
            String payload = normalizePayload(payloadObject);
            String payloadHash = sha256Hex(payload);

            ScopusImportEvent event = new ScopusImportEvent();
            event.setEntityType(entityType);
            event.setSource(source);
            event.setSourceRecordId(sourceRecordId);
            event.setBatchId(batchId);
            event.setCorrelationId(correlationId);
            event.setPayloadFormat(payloadFormat);
            event.setPayload(payload);
            event.setPayloadHash(payloadHash);
            event.setIngestedAt(Instant.now());
            repository.insert(event);
            if (emitTouchQueue && touchQueueService != null) {
                touchQueueService.touchFromIngestPayloadObject(entityType, source, payloadObject);
            }
            return EventIngestionOutcome.imported(event.getId());
        } catch (DuplicateKeyException ignored) {
            return EventIngestionOutcome.skipped();
        } catch (Exception e) {
            return EventIngestionOutcome.error(e.getMessage());
        }
    }

    public BatchIngestionOutcome ingestBatch(
            ScopusImportEntityType entityType,
            String source,
            String batchId,
            String payloadFormat,
            List<BatchIngestionItem> items
    ) {
        return ingestBatch(entityType, source, batchId, payloadFormat, items, true);
    }

    public BatchIngestionOutcome ingestBatch(
            ScopusImportEntityType entityType,
            String source,
            String batchId,
            String payloadFormat,
            List<BatchIngestionItem> items,
            boolean emitTouchQueue
    ) {
        long startedAtNanos = System.nanoTime();
        if (items == null || items.isEmpty()) {
            return new BatchIngestionOutcome(0, 0, 0, 0, 0L, 0L, 0L, 0L);
        }
        if (mongoTemplate == null) {
            int imported = 0;
            int skipped = 0;
            int errors = 0;
            long serializeMs = 0L;
            long touchMs = 0L;
            for (BatchIngestionItem item : items) {
                long serializeStart = System.nanoTime();
                EventIngestionOutcome outcome = ingest(
                        entityType,
                        source,
                        item.sourceRecordId(),
                        batchId,
                        item.correlationId(),
                        payloadFormat,
                        item.payloadObject(),
                        emitTouchQueue
                );
                serializeMs += nanosToMillis(System.nanoTime() - serializeStart);
                if (outcome.error()) {
                    errors++;
                } else if (outcome.imported()) {
                    imported++;
                } else {
                    skipped++;
                }
            }
            return new BatchIngestionOutcome(items.size(), imported, skipped, errors, serializeMs, 0L, touchMs, nanosToMillis(System.nanoTime() - startedAtNanos));
        }

        List<PreparedBatchItem> prepared = new ArrayList<>(items.size());
        int serializationErrors = 0;
        long serializeStart = System.nanoTime();
        for (BatchIngestionItem item : items) {
            try {
                String payload = normalizePayload(item.payloadObject());
                String payloadHash = sha256Hex(payload);
                prepared.add(new PreparedBatchItem(item, payload, payloadHash));
            } catch (Exception e) {
                serializationErrors++;
            }
        }
        long serializeMs = nanosToMillis(System.nanoTime() - serializeStart);
        if (prepared.isEmpty()) {
            return new BatchIngestionOutcome(items.size(), 0, 0, serializationErrors, serializeMs, 0L, 0L, nanosToMillis(System.nanoTime() - startedAtNanos));
        }

        List<org.bson.Document> docs = new ArrayList<>(prepared.size());
        Instant now = Instant.now();
        for (PreparedBatchItem item : prepared) {
            org.bson.Document doc = new org.bson.Document()
                    .append("entityType", entityType.name())
                    .append("source", source)
                    .append("sourceRecordId", item.item().sourceRecordId())
                    .append("batchId", batchId)
                    .append("correlationId", item.item().correlationId())
                    .append("payloadFormat", payloadFormat)
                    .append("payload", item.payload())
                    .append("payloadHash", item.payloadHash());
            doc.append("ingestedAt", now);
            docs.add(doc);
        }

        long dbStart = System.nanoTime();
        Set<Integer> failedIndexes = new HashSet<>();
        int insertedCount = 0;
        try {
            insertedCount = mongoTemplate.getCollection("scopus.import_events")
                    .insertMany(docs, new InsertManyOptions().ordered(false))
                    .getInsertedIds()
                    .size();
        } catch (MongoBulkWriteException e) {
            e.getWriteErrors().forEach(writeError -> failedIndexes.add(writeError.getIndex()));
            insertedCount = prepared.size() - failedIndexes.size();
        }
        long dbMs = nanosToMillis(System.nanoTime() - dbStart);

        Set<Integer> insertedIndexes = new HashSet<>();
        for (int i = 0; i < prepared.size(); i++) {
            if (!failedIndexes.contains(i)) {
                insertedIndexes.add(i);
            }
        }

        long touchStart = System.nanoTime();
        if (emitTouchQueue && touchQueueService != null) {
            if (entityType == ScopusImportEntityType.CITATION) {
                List<ScopusTouchQueueService.CitationEdge> insertedEdges = new ArrayList<>(insertedIndexes.size());
                for (int i = 0; i < prepared.size(); i++) {
                    if (!insertedIndexes.contains(i)) {
                        continue;
                    }
                    PreparedBatchItem item = prepared.get(i);
                    if (item.item().payloadObject() instanceof java.util.Map<?, ?> payloadMap) {
                        Object cited = payloadMap.get("citedEid");
                        Object citing = payloadMap.get("citingEid");
                        if (cited != null && citing != null) {
                            insertedEdges.add(new ScopusTouchQueueService.CitationEdge(
                                    String.valueOf(cited).trim(),
                                    String.valueOf(citing).trim()
                            ));
                        }
                    }
                }
                touchQueueService.touchCitationEdgesBatch(source, insertedEdges);
            } else {
                for (int i = 0; i < prepared.size(); i++) {
                    if (!insertedIndexes.contains(i)) {
                        continue;
                    }
                    PreparedBatchItem item = prepared.get(i);
                    touchQueueService.touchFromIngestPayloadObject(entityType, source, item.item().payloadObject());
                }
            }
        }
        long touchMs = nanosToMillis(System.nanoTime() - touchStart);

        int imported = Math.max(0, insertedCount);
        int errors = serializationErrors;
        int skipped = items.size() - imported - errors;
        return new BatchIngestionOutcome(
                items.size(),
                imported,
                Math.max(0, skipped),
                errors,
                serializeMs,
                dbMs,
                touchMs,
                nanosToMillis(System.nanoTime() - startedAtNanos)
        );
    }

    private String normalizePayload(Object payloadObject) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payloadObject);
    }

    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record EventIngestionOutcome(boolean imported, boolean error, String message, String eventId) {
        public static EventIngestionOutcome imported(String eventId) {
            return new EventIngestionOutcome(true, false, null, eventId);
        }

        public static EventIngestionOutcome skipped() {
            return new EventIngestionOutcome(false, false, null, null);
        }

        public static EventIngestionOutcome error(String message) {
            return new EventIngestionOutcome(false, true, message, null);
        }
    }

    public record BatchIngestionItem(String sourceRecordId, String correlationId, Object payloadObject) {
    }

    private record PreparedBatchItem(BatchIngestionItem item, String payload, String payloadHash) {
    }

    public record BatchIngestionOutcome(
            int processed,
            int imported,
            int skipped,
            int errors,
            long serializeMs,
            long dbInsertEventMs,
            long touchQueueUpsertMs,
            long totalMs
    ) {
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
