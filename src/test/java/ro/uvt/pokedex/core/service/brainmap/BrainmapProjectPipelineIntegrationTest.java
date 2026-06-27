package ro.uvt.pokedex.core.service.brainmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.BrainmapProjectFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexProjectFactRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H64 slice 1 — end-to-end: brainmap JSONL → {@code brainmap.project_facts} → canonical {@code scholardex.project_facts}
 * → {@code reporting_read.scholardex_project_view}, against real Mongo + Postgres (Testcontainers). Verifies the
 * coordinator resolves to the seeded UVT canonical affiliation, the EU grant id is derived for EC projects, and the
 * view row count + key fields land.
 */
@Testcontainers
class BrainmapProjectPipelineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h64").withUsername("core").withPassword("core");
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;
    private BrainmapProjectImportService importService;
    private ProjectCanonicalizationService canonService;
    private ProjectProjectionService projectionService;
    private ScholardexAffiliationFactRepository affiliationRepo;

    @BeforeEach
    void setup() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read").locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h64_project_test");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        MongoRepositoryFactory f = new MongoRepositoryFactory(mongoTemplate);
        BrainmapProjectFactRepository brainmapRepo = f.getRepository(BrainmapProjectFactRepository.class);
        affiliationRepo = f.getRepository(ScholardexAffiliationFactRepository.class);
        ScholardexProjectFactRepository projectRepo = f.getRepository(ScholardexProjectFactRepository.class);

        importService = new BrainmapProjectImportService(brainmapRepo, new ObjectMapper());
        canonService = new ProjectCanonicalizationService(brainmapRepo, affiliationRepo, projectRepo);
        projectionService = new ProjectProjectionService(projectRepo, jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    private void seedUvtAffiliation() {
        ScholardexAffiliationFact a = new ScholardexAffiliationFact();
        a.setId("saff_uvt");
        a.setName("West University of Timișoara");
        a.getAliases().add("Universitatea de Vest din Timișoara");
        a.getAliases().add("UVT");
        a.setCountry("Romania");
        a.setCity("Timișoara");
        affiliationRepo.save(a);
    }

    @Test
    void importCanonAndProjectEndToEnd() throws Exception {
        seedUvtAffiliation();
        Path file = Files.createTempFile("uvt_projects", ".jsonl");
        Files.writeString(file, String.join("\n",
                "{\"pkXProiectId\":\"8\",\"code\":\"Horizon-239038-101061610\",\"title\":\"U*Night\","
                        + "\"funder\":\"EC\",\"directorFirst\":\"Ana\",\"directorLast\":\"Popescu\","
                        + "\"coordinator\":\"UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)\","
                        + "\"startYear\":\"2022\",\"endYear\":\"2024\"}",
                "{\"pkXProiectId\":\"9\",\"code\":\"PN-III-P2-2.1-PED-2016-0592\",\"title\":\"PV forecasting\","
                        + "\"funder\":\"UEFISCDI\",\"directorLast\":\"Paulescu\","
                        + "\"coordinator\":\"UNIVERSITATEA DE VEST TIMISOARA (JUDEŢUL TIMIŞ  - TIMISOARA)\","
                        + "\"startYear\":\"2017\",\"endYear\":\"2018\"}"
        ), StandardCharsets.UTF_8);

        var imp = importService.importAll(file, "batch", "corr");
        assertThat(imp.projectsImported()).isEqualTo(2);

        var canon = canonService.rebuild("batch", "corr");
        assertThat(canon.canonicalProjects()).isEqualTo(2);
        assertThat(canon.coordinatorsResolved()).isEqualTo(2);

        int projected = projectionService.rebuildView();
        assertThat(projected).isEqualTo(2);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reporting_read.scholardex_project_view", Long.class);
        assertThat(rows).isEqualTo(2L);

        Map<String, Object> ec = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.scholardex_project_view WHERE funder = 'EC'");
        assertThat(ec.get("eu_grant_id")).isEqualTo("101061610");
        assertThat(ec.get("coordinator_affiliation_id")).isEqualTo("saff_uvt");
        assertThat(ec.get("director_last")).isEqualTo("Popescu");
        assertThat(ec.get("start_year")).isEqualTo(2022);
        assertThat(ec.get("budget")).isNull();
        assertThat((String[]) ((java.sql.Array) ec.get("brainmap_project_ids")).getArray()).containsExactly("8");

        Map<String, Object> ro = jdbcTemplate.queryForMap(
                "SELECT * FROM reporting_read.scholardex_project_view WHERE funder = 'UEFISCDI'");
        assertThat(ro.get("eu_grant_id")).isNull();
        assertThat(ro.get("coordinator_affiliation_id")).isEqualTo("saff_uvt");
        assertThat(ro.get("code")).isEqualTo("PN-III-P2-2.1-PED-2016-0592");

        Files.deleteIfExists(file);
    }

    @Test
    void rebuildIsIdempotentFullReplacement() throws Exception {
        seedUvtAffiliation();
        Path file = Files.createTempFile("uvt_projects", ".jsonl");
        Files.writeString(file,
                "{\"pkXProiectId\":\"1\",\"code\":\"PN-X\",\"funder\":\"UEFISCDI\",\"title\":\"T\"}",
                StandardCharsets.UTF_8);

        importService.importAll(file, "b", "c");
        canonService.rebuild("b", "c");
        projectionService.rebuildView();
        // second run must not duplicate rows
        canonService.rebuild("b2", "c2");
        projectionService.rebuildView();

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reporting_read.scholardex_project_view", Long.class);
        assertThat(rows).isEqualTo(1L);
        Files.deleteIfExists(file);
    }
}
