package ro.uvt.pokedex.core.service.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectPageResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class PostgresScholardexProjectReadPortIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("core_h64_rp").withUsername("core").withPassword("core");

    private NamedParameterJdbcTemplate jdbc;
    private PostgresScholardexProjectReadPort port;

    @BeforeEach
    void setup() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("reporting_read").locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        port = new PostgresScholardexProjectReadPort(jdbc);

        jdbc.getJdbcTemplate().update("DELETE FROM reporting_read.scholardex_project_view");
        insert("sproj_a", "PN-III-P2-2.1-PED-2016-0592", null, "Photovoltaic power toolkit",
                "UEFISCDI", "Marius", "Paulescu", 2017, 2018, "Universitatea de Vest din Timișoara");
        insert("sproj_b", "Horizon-239038-101061610", "101061610", "U Night researchers night",
                "EC", "Ana", "Popescu", 2022, 2024, "Universitatea de Vest din Timișoara");
        insert("sproj_c", "PN-II-RU-TE-2014-4-0398", null, "Occupational burnout study",
                "UEFISCDI", null, null, 2015, 2017, "Other Institute");
    }

    private void insert(String id, String code, String euGrantId, String title, String funder,
                        String dFirst, String dLast, Integer startY, Integer endY, String coord) {
        // director_signature is what the projection computes (ProjectCanonicalizationService.signature over first+last).
        String sig = ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService.signature(
                (((dFirst == null ? "" : dFirst) + " " + (dLast == null ? "" : dLast)).trim()));
        jdbc.getJdbcTemplate().update(
                "INSERT INTO reporting_read.scholardex_project_view "
                        + "(id, code, eu_grant_id, title, funder, director_first, director_last, start_year, end_year, coordinator_name, director_signature) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, code, euGrantId, title, funder, dFirst, dLast, startY, endY, coord, sig);
    }

    @Test
    void searchByTitleCodeFunderAndEuGrantId() {
        assertThat(port.search(0, 25, "title", "asc", "photovoltaic").items())
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_a");
        assertThat(port.search(0, 25, "title", "asc", "PN-II-RU").items())
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_c");
        assertThat(port.search(0, 25, "title", "asc", "EC").items())
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_b");
        assertThat(port.search(0, 25, "title", "asc", "101061610").items())
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_b");
    }

    @Test
    void directorConcatenatedAndNullWhenAbsent() {
        ScholardexProjectListItemResponse a = port.findById("sproj_a");
        assertThat(a.director()).isEqualTo("Marius Paulescu");
        assertThat(a.startYear()).isEqualTo(2017);
        ScholardexProjectListItemResponse c = port.findById("sproj_c");
        assertThat(c.director()).isNull();
    }

    @Test
    void findByIdMissingReturnsNull() {
        assertThat(port.findById("nope")).isNull();
        assertThat(port.findById(null)).isNull();
    }

    @Test
    void pagingAndTotals() {
        ScholardexProjectPageResponse p = port.search(0, 2, "code", "asc", null);
        assertThat(p.items()).hasSize(2);
        assertThat(p.totalItems()).isEqualTo(3);
        assertThat(p.totalPages()).isEqualTo(2);
    }

    @Test
    void invalidSortRejected() {
        assertThrows(IllegalArgumentException.class, () -> port.search(0, 25, "bogus", "asc", null));
    }

    @Test
    void findByDirectorSignatureMatchesOrderInsensitiveAndNullSafe() {
        // exact-signature equality; signature() applied to both sides → word-order insensitive ("Paulescu Marius").
        String sig = ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService.signature("Paulescu Marius");
        assertThat(port.findByDirectorSignature(sig))
                .extracting(ScholardexProjectListItemResponse::id).containsExactly("sproj_a");
        // a director the data doesn't carry → no rows; blank/null → empty (no query)
        assertThat(port.findByDirectorSignature(
                ro.uvt.pokedex.core.service.brainmap.ProjectCanonicalizationService.signature("Nobody Here"))).isEmpty();
        assertThat(port.findByDirectorSignature(null)).isEmpty();
        assertThat(port.findByDirectorSignature("  ")).isEmpty();
    }
}
