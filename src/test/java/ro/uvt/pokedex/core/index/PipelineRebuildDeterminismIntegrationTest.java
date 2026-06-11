package ro.uvt.pokedex.core.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusImportEvent;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusFundingFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusImportEventRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusFactBuilderService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H54.7 — rebuild-determinism proof at the integration level: building stage-2 Scopus facts from a
 * fixed ledger must be deterministic and idempotent, so "wipe + reimport" reproduces the same data.
 *
 * <p>Runs the real {@link ScopusFactBuilderService} against a real MongoDB on a small fixture, then
 * (1) wipe + rebuild and (2) re-run without wiping, asserting the derived content is byte-identical
 * each time. Comparison excludes the volatile {@code _id} (generated ObjectId) and timestamp fields;
 * Scopus source facts reference each other by natural source ids, so the content is fully
 * deterministic. The full-scale equivalent (rebuild from the real source files) is an operational
 * procedure, documented in {@code docs/rebuild-runbook.md}.
 */
@Testcontainers(disabledWithoutDocker = true)
class PipelineRebuildDeterminismIntegrationTest {

    private static final List<String> FACT_COLLECTIONS = List.of(
            "scopus.publication_facts",
            "scopus.citation_facts",
            "scopus.forum_facts",
            "scopus.author_facts",
            "scopus.affiliation_facts",
            "scopus.funding_facts");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private ScopusFactBuilderService factBuilder;

    @BeforeEach
    void setup() throws Exception {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h54_determinism_test");
        mongoTemplate.getDb().drop();

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongoTemplate);
        ObjectMapper mapper = new ObjectMapper();
        factBuilder = new ScopusFactBuilderService(
                factory.getRepository(ScopusImportEventRepository.class),
                factory.getRepository(ScopusPublicationFactRepository.class),
                factory.getRepository(ScopusCitationFactRepository.class),
                factory.getRepository(ScopusForumFactRepository.class),
                factory.getRepository(ScopusAuthorFactRepository.class),
                factory.getRepository(ScopusAffiliationFactRepository.class),
                factory.getRepository(ScopusFundingFactRepository.class),
                mapper);

        seedLedger(mapper);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void factBuildFromLedgerIsDeterministicAcrossWipeRebuildAndIdempotentReruns() {
        factBuilder.buildFactsFromImportEvents();
        String afterFirstBuild = snapshotDerivedFacts();
        assertThat(afterFirstBuild)
                .as("first build must actually produce facts (else the comparison is trivially empty)")
                .contains("scopus.publication_facts").contains("eid");
        assertThat(afterFirstBuild)
                .as("derived facts must be stamped with the builder version (H54.6b)")
                .contains("\"builderVersion\": \"scopus-fact@1\"");

        // Wipe the derived fact collections (NOT the ledger) and rebuild from the same events.
        wipeFactCollections();
        factBuilder.buildFactsFromImportEvents();
        assertThat(snapshotDerivedFacts())
                .as("wipe + rebuild from the ledger must reproduce identical derived facts")
                .isEqualTo(afterFirstBuild);

        // Re-run the build without wiping: an idempotent rebuild must not change anything.
        factBuilder.buildFactsFromImportEvents();
        assertThat(snapshotDerivedFacts())
                .as("re-running the build must be idempotent")
                .isEqualTo(afterFirstBuild);
    }

    /** Deterministic content snapshot: per collection, docs minus volatile fields, sorted. */
    private String snapshotDerivedFacts() {
        StringBuilder out = new StringBuilder();
        for (String collection : FACT_COLLECTIONS) {
            List<String> docs = new ArrayList<>();
            for (Document doc : mongoTemplate.getCollection(collection).find()) {
                // Exclude volatile fields: the generated _id (ObjectId) and per-build timestamps.
                // All content fields — including natural-key cross-references and the ledger-derived
                // sourceEventId — are deterministic.
                doc.remove("_id");
                doc.remove("_class");
                doc.remove("createdAt");
                doc.remove("updatedAt");
                doc.remove("lastMaterializedAt");
                docs.add(doc.toJson());
            }
            Collections.sort(docs);
            out.append(collection).append('=').append(docs).append('\n');
        }
        return out.toString();
    }

    private void wipeFactCollections() {
        for (String collection : FACT_COLLECTIONS) {
            mongoTemplate.getCollection(collection).deleteMany(new Document());
        }
    }

    private void seedLedger(ObjectMapper mapper) throws Exception {
        ScopusImportEvent publication = new ScopusImportEvent();
        publication.setEntityType(ScopusImportEntityType.PUBLICATION);
        publication.setSource("SCOPUS_JSON_BOOTSTRAP");
        publication.setSourceRecordId("2-s2.0-p1");
        publication.setBatchId("b1");
        publication.setCorrelationId("c1");
        publication.setPayloadHash("payload-hash-p1");
        publication.setPayload(mapper.writeValueAsString(Map.ofEntries(
                Map.entry("eid", "2-s2.0-p1"),
                Map.entry("title", "Paper 1"),
                Map.entry("subtype", "ar"),
                Map.entry("subtypeDescription", "Article"),
                Map.entry("creator", "Creator 1"),
                Map.entry("author_ids", "a1;a2"),
                Map.entry("author_names", "Alice;Bob"),
                Map.entry("author_afids", "af1-af2;af2"),
                Map.entry("afid", "af1;af2"),
                Map.entry("affilname", "Aff 1;Aff 2"),
                Map.entry("affiliation_city", "City1;City2"),
                Map.entry("affiliation_country", "RO;RO"),
                Map.entry("source_id", "f1"),
                Map.entry("publicationName", "Forum 1"),
                Map.entry("issn", "12345678"),
                Map.entry("eIssn", "87654321"),
                Map.entry("aggregationType", "Journal"),
                Map.entry("doi", "10.1000/example"),
                Map.entry("author_count", "2"),
                Map.entry("fund_acr", "PNRR"),
                Map.entry("fund_no", "123"),
                Map.entry("fund_sponsor", "UEFISCDI"),
                Map.entry("coverDate", "2025-01-01"),
                Map.entry("citedby_count", 10))));

        ScopusImportEvent citation = new ScopusImportEvent();
        citation.setEntityType(ScopusImportEntityType.CITATION);
        citation.setSource("SCOPUS_JSON_BOOTSTRAP");
        citation.setSourceRecordId("2-s2.0-p1->2-s2.0-c1");
        citation.setBatchId("b1");
        citation.setCorrelationId("c2");
        citation.setPayloadHash("payload-hash-c1");
        citation.setPayload(mapper.writeValueAsString(Map.of(
                "citedEid", "2-s2.0-p1",
                "citingEid", "2-s2.0-c1")));

        mongoTemplate.insert(publication, "scopus.import_events");
        mongoTemplate.insert(citation, "scopus.import_events");
    }
}
