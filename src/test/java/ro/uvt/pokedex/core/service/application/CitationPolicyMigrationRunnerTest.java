package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * H61 regression guard: a real-Mongo test that the migration rewrites legacy {@code kind.excludeSelf} Citations
 * indicators to {@code kind.policy} so they deserialize under the new record. This is the bug class the pure-unit
 * suite missed (it never deserialized a legacy persisted document).
 */
@Testcontainers
class CitationPolicyMigrationRunnerTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static final String CITATIONS = "ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind$Citations";
    private static final String PUBLICATIONS = "ro.uvt.pokedex.core.model.reporting.scoring.IndicatorKind$Publications";

    private static MongoClient client;

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void migratesLegacyExcludeSelfToPolicyAndLeavesOthersAlone() {
        client = MongoClients.create(MONGO.getReplicaSetUrl());
        MongoTemplate mongoTemplate = new MongoTemplate(client, "test");
        var col = mongoTemplate.getCollection("indicators");
        col.insertMany(List.of(
                new Document("name", "excl").append("kind",
                        new Document("excludeSelf", true).append("strategy", "RIS").append("_class", CITATIONS)),
                new Document("name", "incl").append("kind",
                        new Document("excludeSelf", false).append("strategy", "CS").append("_class", CITATIONS)),
                new Document("name", "alreadyPolicy").append("kind",
                        new Document("policy", "ANY_COAUTHOR").append("strategy", "AIS").append("_class", CITATIONS)),
                new Document("name", "pubs").append("kind",
                        new Document("role", "ALL").append("strategy", "AIS").append("_class", PUBLICATIONS))));

        new CitationPolicyMigrationRunner(mongoTemplate).run();

        assertEquals("CANDIDATE_ONLY", kindOf(col, "excl").getString("policy"));
        assertFalse(kindOf(col, "excl").containsKey("excludeSelf"), "legacy flag removed");
        assertEquals("NONE", kindOf(col, "incl").getString("policy"));
        // already-migrated and non-citation kinds are untouched.
        assertEquals("ANY_COAUTHOR", kindOf(col, "alreadyPolicy").getString("policy"));
        assertEquals("ALL", kindOf(col, "pubs").getString("role"));

        // Idempotent: a second run changes nothing.
        new CitationPolicyMigrationRunner(mongoTemplate).run();
        assertEquals("CANDIDATE_ONLY", kindOf(col, "excl").getString("policy"));
    }

    private static Document kindOf(com.mongodb.client.MongoCollection<Document> col, String name) {
        return col.find(new Document("name", name)).first().get("kind", Document.class);
    }
}
