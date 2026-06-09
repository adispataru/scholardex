package ro.uvt.pokedex.core.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService.BatchIngestionItem;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService.BatchIngestionOutcome;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusImportEventIngestionService.EventIngestionOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H54.3a contract test for the Scopus ledger's idempotent supersede semantics, against a real
 * MongoDB with the declared unique key {@code (source, entityType, sourceRecordId)} in place.
 *
 * Guards the two behaviors that the re-key + supersede rewrite must guarantee, on both the single
 * and bulk ingest paths:
 *   - re-ingesting an identical payload is a no-op (one row, version unchanged);
 *   - re-ingesting a changed payload supersedes in place (one row, version bumped, latest payload).
 */
@Testcontainers(disabledWithoutDocker = true)
class ScopusImportEventSupersedeIntegrationTest {

    private static final String SOURCE = "SCOPUS_PYTHON_AUTHOR_WORKS";
    private static final String RECORD = "2-s2.0-1";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private ScopusImportEventRepository repository;
    private ScopusImportEventIngestionService service;

    @BeforeEach
    void setup() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h54_ledger_test");
        mongoTemplate.getDb().drop();

        // Create the declared unique index so the test reflects production enforcement.
        var ops = mongoTemplate.indexOps(ScopusImportEvent.class);
        new MongoPersistentEntityIndexResolver(
                (MongoMappingContext) mongoTemplate.getConverter().getMappingContext())
                .resolveIndexFor(ScopusImportEvent.class)
                .forEach(ops::createIndex);

        repository = new MongoRepositoryFactory(mongoTemplate).getRepository(ScopusImportEventRepository.class);
        service = new ScopusImportEventIngestionService(repository, new ObjectMapper(), mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    /** Deterministic insertion order so an "identical" payload serializes to the same bytes/hash. */
    private static Map<String, Object> payload(String title) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("eid", RECORD);
        p.put("title", title);
        return p;
    }

    private EventIngestionOutcome ingest(String title) {
        return service.ingest(ScopusImportEntityType.PUBLICATION, SOURCE, RECORD,
                "batch", "corr", "json-object", payload(title));
    }

    private BatchIngestionOutcome ingestBatch(String title) {
        return service.ingestBatch(ScopusImportEntityType.PUBLICATION, SOURCE, "batch", "json-object",
                List.of(new BatchIngestionItem(RECORD, "corr", payload(title))));
    }

    private ScopusImportEvent current() {
        return repository.findBySourceAndEntityTypeAndSourceRecordId(
                SOURCE, ScopusImportEntityType.PUBLICATION, RECORD).orElseThrow();
    }

    @Test
    void singleIngest_identicalReingestIsNoOp() {
        assertThat(ingest("A").imported()).isTrue();
        EventIngestionOutcome second = ingest("A");

        assertThat(second.imported()).as("identical re-ingest must be skipped").isFalse();
        assertThat(second.error()).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(current().getVersion()).isEqualTo(1);
    }

    @Test
    void singleIngest_changedPayloadSupersedesInPlace() {
        ingest("A");
        EventIngestionOutcome second = ingest("B");

        assertThat(second.imported()).as("changed payload is a write").isTrue();
        assertThat(repository.count()).as("supersede must not create a second row").isEqualTo(1);
        ScopusImportEvent ev = current();
        assertThat(ev.getVersion()).isEqualTo(2);
        assertThat(ev.getPayload()).contains("\"title\":\"B\"");
        assertThat(ev.getUpdatedAt()).isNotNull();
    }

    @Test
    void bulkIngest_identicalReingestIsNoOp() {
        assertThat(ingestBatch("A").imported()).isEqualTo(1);
        BatchIngestionOutcome second = ingestBatch("A");

        assertThat(second.imported()).as("identical bulk re-ingest writes nothing").isEqualTo(0);
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(current().getVersion()).isEqualTo(1);
    }

    @Test
    void bulkIngest_changedPayloadSupersedesInPlace() {
        ingestBatch("A");
        BatchIngestionOutcome second = ingestBatch("B");

        assertThat(second.imported()).as("supersede counts as a write").isEqualTo(1);
        assertThat(second.skipped()).isEqualTo(0);
        assertThat(repository.count()).isEqualTo(1);
        ScopusImportEvent ev = current();
        assertThat(ev.getVersion()).isEqualTo(2);
        assertThat(ev.getPayload()).contains("\"title\":\"B\"");
    }
}
