package ro.uvt.pokedex.core.repository.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
public abstract class MongoIntegrationTestBase {

    @Container
    protected static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void overrideMongoProperties(DynamicPropertyRegistry registry) {
        // Spring Boot 4 binds the Mongo URI from `spring.mongodb.uri`; the legacy `spring.data.mongodb.uri` is inert
        // here (see application.properties). Registering the live key is what actually points integration tests at the
        // isolated Testcontainer — otherwise they fall through to the application.properties default (a REAL db).
        registry.add("spring.mongodb.uri", MONGO_CONTAINER::getReplicaSetUrl);
    }
}
