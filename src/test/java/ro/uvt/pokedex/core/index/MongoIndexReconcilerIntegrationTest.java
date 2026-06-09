package ro.uvt.pokedex.core.index;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ro.uvt.pokedex.core.config.MongoIndexReconciler;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H54.2 regression guard for {@link MongoIndexReconciler}: proves a declared index whose shape
 * changed since it was last created is dropped and rebuilt at startup instead of aborting boot
 * (the IndexKeySpecsConflict trap that crashed startup twice on 2026-06-08/09).
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoIndexReconcilerIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private MongoMappingContext mappingContext;

    @BeforeEach
    void setup() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h54_reconciler_test");
        mongoTemplate.getDb().drop();

        // Reuse the template's Spring-configured mapping context (a hand-built one needs JDK
        // --add-opens under Java 25); register the entities under test so getPersistentEntities()
        // returns them for the reconciler to iterate.
        mappingContext = (MongoMappingContext) mongoTemplate.getConverter().getMappingContext();
        mappingContext.getPersistentEntity(ScholardexCitationFact.class);
        mappingContext.getPersistentEntity(ScholardexForumFact.class);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    private Optional<IndexInfo> indexByName(Class<?> type, String name) {
        return mongoTemplate.indexOps(type).getIndexInfo().stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst();
    }

    @Test
    void createsMissingDeclaredIndexesOnFreshDatabase() {
        new MongoIndexReconciler(mongoTemplate, mappingContext).afterSingletonsInstantiated();

        assertThat(indexByName(ScholardexCitationFact.class, "uniq_scholardex_citation_edge")).isPresent();
        assertThat(indexByName(ScholardexForumFact.class, "uniq_scholardex_forum_scopus_id")).isPresent();
        assertThat(indexByName(ScholardexForumFact.class, "uniq_scholardex_forum_wos_id")).isPresent();
    }

    @Test
    void dropsAndRebuildsDriftedIndexInsteadOfFailing() {
        // Simulate a previously-deployed, now-stale index: same name, OLD 3-field shape
        // (the citation_facts drift we saw in production data).
        mongoTemplate.indexOps(ScholardexCitationFact.class).createIndex(
                new CompoundIndexDefinition(new Document("citedPublicationId", 1)
                        .append("citingPublicationId", 1)
                        .append("source", 1))
                        .named("uniq_scholardex_citation_edge")
                        .unique());

        IndexInfo before = indexByName(ScholardexCitationFact.class, "uniq_scholardex_citation_edge").orElseThrow();
        assertThat(before.getIndexFields()).extracting(IndexField::getKey)
                .containsExactly("citedPublicationId", "citingPublicationId", "source");

        // Reconcile: must NOT throw, and must converge the index to the declared 2-field shape.
        new MongoIndexReconciler(mongoTemplate, mappingContext).afterSingletonsInstantiated();

        IndexInfo after = indexByName(ScholardexCitationFact.class, "uniq_scholardex_citation_edge").orElseThrow();
        assertThat(after.getIndexFields()).extracting(IndexField::getKey)
                .containsExactly("citedPublicationId", "citingPublicationId");
        assertThat(after.isUnique()).isTrue();
    }
}
