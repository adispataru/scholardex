package ro.uvt.pokedex.core.derivation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import ro.uvt.pokedex.core.service.application.JdbcPostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.JdbcPostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.PostgresReadCutoverGuard;
import ro.uvt.pokedex.core.service.application.PostgresReportingLookupFacade;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAffiliationReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexForumReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexProjectionReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosCategoryReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingDetailsReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingReadPort;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;

/**
 * Shared base for real-Mongo canonical-derivation integration tests (H75 + the H73 S2.2 inversion test). Boots the
 * full Spring context with Postgres autoconfig excluded and the read-side Postgres beans mocked (mirrors
 * {@code CoreApplicationTests}); Mongo is a Testcontainers container bound via {@code spring.mongodb.uri}. Subclasses
 * {@code @Autowired} the canon services they exercise, seed source facts, run the canon, and assert against the
 * canonical collections (often via {@link CanonicalSnapshot}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.task.scheduling.enabled=false",
                "core.scopus.scheduler.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
public abstract class CanonicalDerivationIntegrationTestBase {

    // Singleton container (started once per JVM, NOT @Container-managed): shared across every subclass test class.
    // A @Container static field would be stopped after the first class, but Spring caches the context (same config)
    // and reuses it for the next class — leaving its Mongo client pointed at a dead container. Starting it manually
    // and never stopping it keeps the cached context valid; Ryuk reaps it on JVM exit.
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    // Postgres read-side beans are mocked; canonical derivation under test is Mongo-only (mirrors CoreApplicationTests).
    @MockitoBean protected JdbcTemplate jdbcTemplate;
    @MockitoBean protected NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @MockitoBean protected PostgresWosCategoryReadPort postgresWosCategoryReadPort;
    @MockitoBean protected PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;
    @MockitoBean protected PostgresWosRankingReadPort postgresWosRankingReadPort;
    @MockitoBean protected PostgresReportingLookupFacade postgresReportingLookupFacade;
    @MockitoBean protected PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;
    @MockitoBean protected PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    @MockitoBean protected PostgresScholardexProjectionReadPort postgresScholardexProjectionReadPort;
    @MockitoBean protected PostgresScholardexAffiliationReadPort postgresScholardexAffiliationReadPort;
    @MockitoBean protected PostgresScholardexForumReadPort postgresScholardexForumReadPort;
    @MockitoBean protected JdbcPostgresReportingProjectionService jdbcPostgresReportingProjectionService;
    @MockitoBean protected JdbcPostgresMaterializedViewRefreshService jdbcPostgresMaterializedViewRefreshService;
    @MockitoBean protected PostgresReadCutoverGuard postgresReadCutoverGuard;
    @MockitoBean protected PlatformTransactionManager platformTransactionManager;
    @MockitoBean protected WosProjectionBuilderService wosProjectionBuilderService;
    @MockitoBean protected ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService scopusProjectionBuilderService;

    @Autowired protected MongoTemplate mongoTemplate;

    /** Neutralize the real Postgres-touching startup runner (mirrors CoreApplicationTests). */
    @TestConfiguration
    static class TestConfig {
        @Bean(name = "initDatabase")
        CommandLineRunner initDatabase() {
            return args -> {
            };
        }
    }
}
