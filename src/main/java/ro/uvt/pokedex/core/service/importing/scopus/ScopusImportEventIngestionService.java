package ro.uvt.pokedex.core.service.importing.scopus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ingests Scopus-source records into the {@code scopus.import_events} ledger with idempotent,
 * supersede-on-change semantics (H54.3a).
 *
 * <p>The ledger holds exactly one current event per source-record identity
 * {@code (source, entityType, sourceRecordId)} — the unique key no longer includes the payload
 * hash. Re-ingesting:
 * <ul>
 *   <li>an <b>identical</b> payload is a no-op ("skipped");</li>
 *   <li>a <b>changed</b> payload supersedes in place — replaces the payload, sets {@code updatedAt},
 *       and bumps {@code version} ("imported", i.e. a write occurred);</li>
 *   <li>a <b>new</b> identity inserts a fresh event with {@code version = 1} ("imported").</li>
 * </ul>
 *
 * <p>Previously the hash was part of the unique key, so a corrected payload produced a second row
 * instead of replacing the first — the root of the ledger duplication found on 2026-06-08.
 */
@Service
public class ScopusImportEventIngestionService {

    private static final String COLLECTION = "scopus.import_events";

    private final ScopusImportEventRepository repository;
    private final ObjectMapper objectMapper;
    private final MongoTemplate mongoTemplate;

    public ScopusImportEventIngestionService(
            ScopusImportEventRepository repository,
            ObjectMapper objectMapper,
            @Nullable MongoTemplate mongoTemplate
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
        try {
            String payload = normalizePayload(payloadObject);
            String payloadHash = sha256Hex(payload);
            Instant now = Instant.now();

            Optional<ScopusImportEvent> existing =
                    repository.findBySourceAndEntityTypeAndSourceRecordId(source, entityType, sourceRecordId);

            if (existing.isEmpty()) {
                ScopusImportEvent event = new ScopusImportEvent();
                event.setEntityType(entityType);
                event.setSource(source);
                event.setSourceRecordId(sourceRecordId);
                event.setBatchId(batchId);
                event.setCorrelationId(correlationId);
                event.setPayloadFormat(payloadFormat);
                event.setPayload(payload);
                event.setPayloadHash(payloadHash);
                event.setIngestedAt(now);
                event.setUpdatedAt(now);
                event.setVersion(1);
                try {
                    repository.insert(event);
                    return EventIngestionOutcome.imported(event.getId());
                } catch (DuplicateKeyException raceLostToConcurrentInsert) {
                    existing = repository.findBySourceAndEntityTypeAndSourceRecordId(source, entityType, sourceRecordId);
                    if (existing.isEmpty()) {
                        return EventIngestionOutcome.skipped();
                    }
                    // fall through to supersede/skip against the concurrently-inserted row
                }
            }

            ScopusImportEvent current = existing.get();
            if (payloadHash.equals(current.getPayloadHash())) {
                return EventIngestionOutcome.skipped();
            }
            current.setPayload(payload);
            current.setPayloadHash(payloadHash);
            current.setPayloadFormat(payloadFormat);
            current.setBatchId(batchId);
            current.setCorrelationId(correlationId);
            current.setUpdatedAt(now);
            current.setVersion(current.getVersion() + 1);
            repository.save(current);
            return EventIngestionOutcome.imported(current.getId());
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
        long startedAtNanos = System.nanoTime();
        if (items == null || items.isEmpty()) {
            return new BatchIngestionOutcome(0, 0, 0, 0, 0L, 0L, 0L);
        }
        if (mongoTemplate == null) {
            int imported = 0;
            int skipped = 0;
            int errors = 0;
            long serializeMs = 0L;
            for (BatchIngestionItem item : items) {
                long serializeStart = System.nanoTime();
                EventIngestionOutcome outcome = ingest(
                        entityType, source, item.sourceRecordId(), batchId,
                        item.correlationId(), payloadFormat, item.payloadObject()
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
            return new BatchIngestionOutcome(items.size(), imported, skipped, errors, serializeMs, 0L,
                    nanosToMillis(System.nanoTime() - startedAtNanos));
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
            return new BatchIngestionOutcome(items.size(), 0, 0, serializationErrors, serializeMs, 0L,
                    nanosToMillis(System.nanoTime() - startedAtNanos));
        }

        Instant now = Instant.now();
        List<WriteModel<Document>> ops = new ArrayList<>(prepared.size());
        for (PreparedBatchItem item : prepared) {
            ops.add(supersedeOrInsert(entityType, source, item.item().sourceRecordId(), batchId,
                    item.item().correlationId(), payloadFormat, item.payload(), item.payloadHash(), now));
        }

        long dbStart = System.nanoTime();
        BulkWriteResult result;
        int writeErrors = 0;
        try {
            result = mongoTemplate.getCollection(COLLECTION).bulkWrite(ops, new BulkWriteOptions().ordered(false));
        } catch (MongoBulkWriteException e) {
            result = e.getWriteResult();
            writeErrors = e.getWriteErrors().size();
        }
        long dbMs = nanosToMillis(System.nanoTime() - dbStart);

        // upserts = brand-new rows; modifiedCount = supersedes (payload changed); both are writes.
        // matchedCount - modifiedCount = identical re-ingests (true no-ops).
        int inserted = result.getUpserts().size();
        int modified = result.getModifiedCount();
        int matched = result.getMatchedCount();
        int imported = inserted + modified;
        int skipped = Math.max(0, matched - modified);
        int errors = serializationErrors + writeErrors;

        return new BatchIngestionOutcome(
                items.size(), imported, skipped, errors, serializeMs, dbMs,
                nanosToMillis(System.nanoTime() - startedAtNanos)
        );
    }

    /**
     * Atomic upsert keyed by the source-record identity. On insert: version 1, ingestedAt/updatedAt
     * set. On match with the same hash: every field resolves to its current value, so the document
     * is unchanged (no-op skip). On match with a different hash: payload replaced, updatedAt set,
     * version incremented. All branches are expressed with {@code $cond} against the pre-update
     * document, so the whole decision is server-side and atomic.
     */
    private UpdateOneModel<Document> supersedeOrInsert(
            ScopusImportEntityType entityType, String source, String sourceRecordId, String batchId,
            String correlationId, String payloadFormat, String payload, String payloadHash, Instant now) {

        Document hashUnchanged = new Document("$eq", List.of("$payloadHash", payloadHash));

        Document set = new Document()
                .append("payload", new Document("$cond", List.of(hashUnchanged, "$payload", payload)))
                .append("version", new Document("$cond", List.of(
                        hashUnchanged,
                        new Document("$ifNull", List.of("$version", 1)),
                        new Document("$add", List.of(new Document("$ifNull", List.of("$version", 0)), 1)))))
                .append("updatedAt", new Document("$cond", List.of(
                        hashUnchanged,
                        new Document("$ifNull", List.of("$updatedAt", "$ingestedAt")),
                        now)))
                .append("payloadHash", payloadHash)
                .append("ingestedAt", new Document("$ifNull", List.of("$ingestedAt", now)))
                .append("entityType", entityType.name())
                .append("source", source)
                .append("sourceRecordId", sourceRecordId)
                .append("batchId", batchId)
                .append("correlationId", correlationId)
                .append("payloadFormat", payloadFormat);

        Document filter = new Document("source", source)
                .append("entityType", entityType.name())
                .append("sourceRecordId", sourceRecordId);

        return new UpdateOneModel<>(filter, List.of(new Document("$set", set)), new UpdateOptions().upsert(true));
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
            long totalMs
    ) {
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
