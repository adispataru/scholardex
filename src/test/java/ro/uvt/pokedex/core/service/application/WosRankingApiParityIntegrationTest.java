package ro.uvt.pokedex.core.service.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.model.reporting.wos.EditionNormalized;
import ro.uvt.pokedex.core.model.reporting.wos.WosRankingView;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class WosRankingApiParityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h24_4")
            .withUsername("core")
            .withPassword("core");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private JdbcTemplate jdbcTemplate;
    private MongoWosRankingReadPort mongoReadPort;
    private PostgresWosRankingReadPort postgresReadPort;

    @BeforeEach
    void setUp() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        mongoClient = MongoClients.create(MONGO.getReplicaSetUrl());
        mongoTemplate = new MongoTemplate(mongoClient, "h24_4_wos_api_parity");
        mongoTemplate.getDb().drop();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        mongoReadPort = new MongoWosRankingReadPort(mongoTemplate);
        postgresReadPort = new PostgresWosRankingReadPort(new NamedParameterJdbcTemplate(dataSource));

        seedEquivalentRankings();
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    void noFilterPageMatchesAcrossMongoAndPostgres() {
        assertParity(0, 25, "name", "asc", null);
    }

    @Test
    void namePrefixSearchMatchesAcrossMongoAndPostgres() {
        assertParity(0, 25, "name", "asc", "nature");
    }

    @Test
    void issnPrefixSearchMatchesAcrossMongoAndPostgres() {
        assertParity(0, 25, "issn", "asc", "1111");
    }

    @Test
    void eIssnPrefixSearchMatchesAcrossMongoAndPostgres() {
        assertParity(0, 25, "eIssn", "desc", "8888");
    }

    @Test
    void alternativeIssnPrefixSearchMatchesAcrossMongoAndPostgres() {
        assertParity(0, 25, "name", "asc", "ALT-0001");
    }

    @Test
    void blankQueryBehavesLikeOmittedQueryAndMatchesAcrossStores() {
        WosRankingPageResponse mongoBlank = mongoReadPort.search(0, 25, "name", "asc", "   ");
        WosRankingPageResponse mongoDefault = mongoReadPort.search(0, 25, "name", "asc", null);
        WosRankingPageResponse postgresBlank = postgresReadPort.search(0, 25, "name", "asc", "   ");
        WosRankingPageResponse postgresDefault = postgresReadPort.search(0, 25, "name", "asc", null);

        assertEquals(mongoDefault, mongoBlank);
        assertEquals(postgresDefault, postgresBlank);
        assertEquals(mongoBlank, postgresBlank);
    }

    @Test
    void containsSearchDriftIsRejectedByBothStores() {
        WosRankingPageResponse mongo = mongoReadPort.search(0, 25, "name", "asc", "aterials");
        WosRankingPageResponse postgres = postgresReadPort.search(0, 25, "name", "asc", "aterials");

        assertEquals(new WosRankingPageResponse(List.of(), 0, 25, 0, 0), mongo);
        assertEquals(mongo, postgres);
    }

    @Test
    void emptyResultAndPagingStayInParity() {
        assertParity(0, 25, "name", "asc", "zzz");
        assertParity(1, 2, "issn", "asc", null);
    }

    private void assertParity(int page, int size, String sort, String direction, String q) {
        WosRankingPageResponse mongo = mongoReadPort.search(page, size, sort, direction, q);
        WosRankingPageResponse postgres = postgresReadPort.search(page, size, sort, direction, q);
        assertEquals(mongo, postgres);
    }

    private void seedEquivalentRankings() {
        Instant now = Instant.parse("2026-03-13T10:00:00Z");

        saveMongoRanking("j1", "Nature Materials", "1111-1111", "9999-9999", List.of("3333-3333"), now);
        saveMongoRanking("j2", "Nature Physics", "1111-2222", "8888-0000", List.of("ALT-0001"), now);
        saveMongoRanking("j3", "Zeta Science", "2222-1111", "7777-0000", List.of("4444-5555"), now);
        saveMongoRanking("j4", "Alpha Review", "0001-0001", "6666-0000", List.of(), now);

        insertPostgresRanking("j1", "Nature Materials", "1111-1111", "9999-9999", List.of("3333-3333"), now);
        insertPostgresRanking("j2", "Nature Physics", "1111-2222", "8888-0000", List.of("ALT-0001"), now);
        insertPostgresRanking("j3", "Zeta Science", "2222-1111", "7777-0000", List.of("4444-5555"), now);
        insertPostgresRanking("j4", "Alpha Review", "0001-0001", "6666-0000", List.of(), now);
    }

    private void saveMongoRanking(
            String id,
            String name,
            String issn,
            String eIssn,
            List<String> alternativeIssns,
            Instant now
    ) {
        WosRankingView view = new WosRankingView();
        view.setId(id);
        view.setName(name);
        view.setIssn(issn);
        view.setEIssn(eIssn);
        view.setAlternativeIssns(alternativeIssns);
        view.setAlternativeNames(List.of());
        view.setNameNorm(normalizeText(name));
        view.setIssnNorm(normalizeIssn(issn));
        view.setEIssnNorm(normalizeIssn(eIssn));
        view.setAlternativeIssnsNorm(alternativeIssns.stream().map(this::normalizeIssn).toList());
        view.setLatestAisYear(2025);
        view.setLatestRisYear(2025);
        view.setLatestEditionNormalized(EditionNormalized.SCIE);
        view.setBuildVersion("test");
        view.setBuildAt(now);
        view.setUpdatedAt(now);
        mongoTemplate.save(view);
    }

    private void insertPostgresRanking(
            String id,
            String name,
            String issn,
            String eIssn,
            List<String> alternativeIssns,
            Instant now
    ) {
        String altIssnsArray = toTextArrayLiteral(alternativeIssns);
        String altIssnsNormArray = toTextArrayLiteral(alternativeIssns.stream().map(this::normalizeIssn).toList());

        jdbcTemplate.execute("""
                INSERT INTO reporting_read.wos_ranking_view (
                    journal_id, name, issn, e_issn, alternative_issns, alternative_names,
                    name_norm, issn_norm, e_issn_norm, alternative_issns_norm,
                    latest_ais_year, latest_ris_year, latest_edition_normalized,
                    build_version, build_at, updated_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', %s, ARRAY[]::text[],
                    '%s', '%s', '%s', %s,
                    2025, 2025, 'SCIE',
                    'test', TIMESTAMPTZ '%s', TIMESTAMPTZ '%s'
                )
                """.formatted(
                id,
                sql(name),
                issn,
                eIssn,
                altIssnsArray,
                sql(normalizeText(name)),
                normalizeIssn(issn),
                normalizeIssn(eIssn),
                altIssnsNormArray,
                now,
                now
        ));
    }

    private String normalizeText(String value) {
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String normalizeIssn(String value) {
        return value.trim().toUpperCase().replace("-", "").replace(" ", "");
    }

    private String toTextArrayLiteral(List<String> values) {
        if (values.isEmpty()) {
            return "ARRAY[]::text[]";
        }
        return "ARRAY[" + values.stream()
                .map(this::sql)
                .map(value -> "'" + value + "'")
                .reduce((left, right) -> left + ", " + right)
                .orElse("") + "]";
    }

    private String sql(String value) {
        return value.replace("'", "''");
    }
}
