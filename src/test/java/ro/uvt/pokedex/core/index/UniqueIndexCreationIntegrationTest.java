package ro.uvt.pokedex.core.index;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H54.2 regression guard for the unique-index declarations that crashed startup twice.
 *
 * The Scholardex *_facts collections carry multikey-array unique indexes that previously used
 * {@code sparse = true}. Sparse does NOT skip empty arrays in a multikey index, so every document
 * with an empty id array collided on a single "undefined" key and the index was unbuildable.
 * The fix replaces sparse with {@code partialFilter = "{'field': {'$type': 'string'}}"}.
 *
 * This test recreates the exact failure condition — building the entity's declared indexes while
 * empty-array documents are already present — against a real MongoDB, and asserts the index builds
 * and enforces uniqueness only across documents that actually carry an id.
 */
@Testcontainers(disabledWithoutDocker = true)
class UniqueIndexCreationIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h54_index_test");
        mongoTemplate.getDb().drop();
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    /** Resolve the entity's declared indexes and create them, exactly as auto-index-creation does. */
    private void createDeclaredIndexes(Class<?> entityType) {
        IndexOperations ops = mongoTemplate.indexOps(entityType);
        MongoMappingContext ctx = (MongoMappingContext) mongoTemplate.getConverter().getMappingContext();
        new MongoPersistentEntityIndexResolver(ctx)
                .resolveIndexFor(entityType)
                .forEach(ops::createIndex);
    }

    private ScholardexForumFact forum(String name, List<String> scopusIds, List<String> wosIds) {
        ScholardexForumFact f = new ScholardexForumFact();
        f.setName(name);
        f.setScopusForumIds(scopusIds);
        f.setWosForumIds(wosIds);
        return f;
    }

    @Test
    void indexBuildsWhenEmptyArrayDocumentsAlreadyPresent() {
        // The exact pre-fix crash condition: many docs with empty scopusForumIds, index built after.
        mongoTemplate.insert(forum("A", List.of(), List.of()), "scholardex.forum_facts");
        mongoTemplate.insert(forum("B", List.of(), List.of()), "scholardex.forum_facts");
        mongoTemplate.insert(forum("C", List.of(), List.of()), "scholardex.forum_facts");

        assertThatCode(() -> createDeclaredIndexes(ScholardexForumFact.class))
                .as("partialFilter unique index must build even when empty-array docs exist")
                .doesNotThrowAnyException();

        // All three empty-array docs coexist: the partial filter excludes them from the unique index.
        assertThat(mongoTemplate.getCollection("scholardex.forum_facts").countDocuments()).isEqualTo(3);

        IndexInfo scopusIdx = mongoTemplate.indexOps(ScholardexForumFact.class).getIndexInfo().stream()
                .filter(i -> "uniq_scholardex_forum_scopus_id".equals(i.getName()))
                .findFirst().orElseThrow();
        assertThat(scopusIdx.isUnique()).isTrue();
        assertThat(scopusIdx.isSparse()).isFalse();
    }

    @Test
    void duplicateNonEmptyIdIsRejectedButEmptyArraysAreNot() {
        createDeclaredIndexes(ScholardexForumFact.class);

        // Two documents that actually carry the same scopus id must collide.
        mongoTemplate.insert(forum("X", List.of("SCOPUS-1"), List.of()), "scholardex.forum_facts");
        assertThatThrownBy(() ->
                mongoTemplate.insert(forum("Y", List.of("SCOPUS-1"), List.of()), "scholardex.forum_facts"))
                .isInstanceOf(DuplicateKeyException.class);

        // But any number of documents with no scopus id are allowed.
        assertThatCode(() -> {
            mongoTemplate.insert(forum("E1", List.of(), List.of()), "scholardex.forum_facts");
            mongoTemplate.insert(forum("E2", List.of(), List.of()), "scholardex.forum_facts");
        }).doesNotThrowAnyException();
    }
}
