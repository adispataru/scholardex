package ro.uvt.pokedex.core.repository.scopus.canonical;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.repository.support.MongoIntegrationTestBase;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationAuthorshipDecisionRepositoryTest extends MongoIntegrationTestBase {

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private PublicationAuthorshipDecisionRepository repository;

    @BeforeEach
    void setUp() {
        mongoClient = MongoClients.create(MONGO_CONTAINER.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "publication_authorship_decision_test");
        mongoTemplate.getDb().drop();
        ensureIndexes();
        repository = new MongoRepositoryFactory(mongoTemplate).getRepository(PublicationAuthorshipDecisionRepository.class);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void createsAndQueriesDecisionByUserAndPublication() {
        PublicationAuthorshipDecision decision = decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED);

        repository.save(decision);

        PublicationAuthorshipDecision stored = repository.findByUserEmailAndPublicationId("user@example.com", "spub_1").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PublicationAuthorshipDecision.Status.CONFIRMED);
        assertThat(stored.getSnapshot().getPublication().getTitle()).isEqualTo("Test publication");
        assertThat(repository.findByUserEmailOrderByUpdatedAtDesc("user@example.com")).hasSize(1);
    }

    @Test
    void preventsDuplicateDecisionForSameUserAndPublication() {
        repository.save(decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED));

        assertThatThrownBy(() -> repository.save(decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.REJECTED)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteRestoresImplicitUnreviewedState() {
        repository.save(decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED));

        long deleted = repository.deleteByUserEmailAndPublicationId("user@example.com", "spub_1");

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByUserEmailAndPublicationId("user@example.com", "spub_1")).isEmpty();
    }

    @Test
    void queriesSubsetOfUserPublicationDecisions() {
        repository.saveAll(List.of(
                decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED),
                decision("user@example.com", "spub_2", PublicationAuthorshipDecision.Status.REJECTED),
                decision("other@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED)
        ));

        List<PublicationAuthorshipDecision> decisions =
                repository.findByUserEmailAndPublicationIdIn("user@example.com", List.of("spub_2", "spub_3"));

        assertThat(decisions).extracting(PublicationAuthorshipDecision::getPublicationId).containsExactly("spub_2");
    }

    @Test
    void queriesAllDecisionsForPublicationSubsetAcrossUsers() {
        repository.saveAll(List.of(
                decision("user@example.com", "spub_1", PublicationAuthorshipDecision.Status.CONFIRMED),
                decision("other@example.com", "spub_1", PublicationAuthorshipDecision.Status.REJECTED),
                decision("third@example.com", "spub_2", PublicationAuthorshipDecision.Status.CONFIRMED)
        ));

        List<PublicationAuthorshipDecision> decisions = repository.findByPublicationIdIn(List.of("spub_1"));

        assertThat(decisions)
                .extracting(PublicationAuthorshipDecision::getUserEmail)
                .containsExactlyInAnyOrder("user@example.com", "other@example.com");
    }

    private void ensureIndexes() {
        MappingContext<?, ?> mappingContext = mongoTemplate.getConverter().getMappingContext();
        IndexResolver resolver = new MongoPersistentEntityIndexResolver((MongoMappingContext) mappingContext);
        resolver.resolveIndexFor(PublicationAuthorshipDecision.class)
                .forEach(index -> mongoTemplate.indexOps(PublicationAuthorshipDecision.class).ensureIndex(index));
    }

    private PublicationAuthorshipDecision decision(String userEmail,
                                                   String publicationId,
                                                   PublicationAuthorshipDecision.Status status) {
        PublicationAuthorshipDecision decision = new PublicationAuthorshipDecision();
        decision.setUserEmail(userEmail);
        decision.setResearcherId("sauth_1");
        decision.setPublicationId(publicationId);
        decision.setStatus(status);
        decision.setDecisionSource(PublicationAuthorshipDecision.DecisionSource.USER_REVIEW);
        decision.setCreatedAt(Instant.parse("2026-04-16T10:00:00Z"));
        decision.setUpdatedAt(Instant.parse("2026-04-16T10:00:00Z"));

        PublicationAuthorshipDecision.Snapshot snapshot = new PublicationAuthorshipDecision.Snapshot();
        snapshot.getPublication().setTitle("Test publication");
        snapshot.getPublication().setEid("2-s2.0-test");
        snapshot.getPublication().setDoi("10.1000/test");
        snapshot.getUser().setResearcherId("r_1");
        snapshot.getUser().setPrimaryScholardexAuthorId("sauth_1");
        snapshot.getLinkedAuthorIds().add("sauth_1");
        decision.setSnapshot(snapshot);
        return decision;
    }
}
