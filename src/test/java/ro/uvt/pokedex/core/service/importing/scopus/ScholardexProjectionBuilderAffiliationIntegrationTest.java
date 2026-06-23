package ro.uvt.pokedex.core.service.importing.scopus;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.repository.reporting.WosCategoryFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosCoverageFactRepository;
import ro.uvt.pokedex.core.repository.reporting.WosMetricFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorshipFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Mongo &rarr; Postgres rebuild test: an author's affiliations live only in the
 * {@link ScholardexAuthorAffiliationFact} edge table (V2 never populates the author fact's affiliationIds), so the
 * projection rebuild must denormalize them onto {@code scholardex_author_view.affiliation_ids}. Exercises the actual
 * {@code rebuildViews()} end-to-end against Testcontainers Postgres + Mongo.
 */
@Testcontainers
class ScholardexProjectionBuilderAffiliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_scopus_affil_test")
            .withUsername("core")
            .withPassword("core");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;
    private ScholardexProjectionBuilderService service;

    private ScholardexAuthorFactRepository authorFactRepository;
    private ScholardexAffiliationFactRepository affiliationFactRepository;
    private ScholardexAuthorAffiliationFactRepository authorAffiliationFactRepository;

    @BeforeEach
    void setup() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "scopus_affil_integration_test");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongoTemplate);
        authorFactRepository = factory.getRepository(ScholardexAuthorFactRepository.class);
        affiliationFactRepository = factory.getRepository(ScholardexAffiliationFactRepository.class);
        authorAffiliationFactRepository = factory.getRepository(ScholardexAuthorAffiliationFactRepository.class);

        service = new ScholardexProjectionBuilderService(
                factory.getRepository(ScholardexForumFactRepository.class),
                authorFactRepository,
                affiliationFactRepository,
                factory.getRepository(ScholardexPublicationFactRepository.class),
                factory.getRepository(ScholardexCitationFactRepository.class),
                factory.getRepository(ScholardexAuthorshipFactRepository.class),
                authorAffiliationFactRepository,
                factory.getRepository(WosMetricFactRepository.class),
                factory.getRepository(WosCategoryFactRepository.class),
                factory.getRepository(WosCoverageFactRepository.class),
                mongoTemplate,
                jdbcTemplate,
                txManager
        );
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void rebuildDenormalizesAuthorAffiliationsFromTheEdgeTable() {
        // An author with two affiliation edges (incl. a duplicate across sources), and one with none.
        authorFactRepository.save(author("sauth_with", "Linked Author"));
        authorFactRepository.save(author("sauth_without", "Unlinked Author"));
        affiliationFactRepository.save(affiliation("saff_uvt", "West University of Timisoara"));
        affiliationFactRepository.save(affiliation("saff_ucc", "University College Cork"));
        authorAffiliationFactRepository.save(edge("e1", "sauth_with", "saff_uvt", "OPENALEX"));
        authorAffiliationFactRepository.save(edge("e2", "sauth_with", "saff_ucc", "OPENALEX"));
        authorAffiliationFactRepository.save(edge("e3", "sauth_with", "saff_uvt", "SCOPUS")); // duplicate affiliation, other source

        service.rebuildViews();

        List<String> linked = affiliationIdsOf("sauth_with");
        assertEquals(2, linked.size(), "distinct affiliations from the edges, de-duplicated across sources");
        assertTrue(linked.contains("saff_uvt"));
        assertTrue(linked.contains("saff_ucc"));

        assertTrue(affiliationIdsOf("sauth_without").isEmpty(),
                "an author with no edges is written with an empty affiliation list, not the stale fact value");
    }

    private List<String> affiliationIdsOf(String authorId) {
        return jdbcTemplate.query(
                "SELECT affiliation_ids FROM reporting_read.scholardex_author_view WHERE id = ?",
                (rs, rowNum) -> {
                    java.sql.Array array = rs.getArray("affiliation_ids");
                    if (array == null) return List.<String>of();
                    return List.of((String[]) array.getArray());
                },
                authorId
        ).stream().findFirst().orElse(List.of());
    }

    private static ScholardexAuthorFact author(String id, String displayName) {
        ScholardexAuthorFact fact = new ScholardexAuthorFact();
        fact.setId(id);
        fact.setDisplayName(displayName);
        return fact;
    }

    private static ScholardexAffiliationFact affiliation(String id, String name) {
        ScholardexAffiliationFact fact = new ScholardexAffiliationFact();
        fact.setId(id);
        fact.setName(name);
        return fact;
    }

    private static ScholardexAuthorAffiliationFact edge(String id, String authorId, String affiliationId, String source) {
        ScholardexAuthorAffiliationFact fact = new ScholardexAuthorAffiliationFact();
        fact.setId(id);
        fact.setAuthorId(authorId);
        fact.setAffiliationId(affiliationId);
        fact.setSource(source);
        return fact;
    }
}
