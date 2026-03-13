package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresReadCutoverGuard implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PostgresReportingLookupFacade postgresReportingLookupFacade;
    private final PostgresWosRankingReadPort postgresWosRankingReadPort;
    private final PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    private final PostgresScholardexForumReadPort postgresScholardexForumReadPort;
    private final PostgresScholardexAffiliationReadPort postgresScholardexAffiliationReadPort;
    private final PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;

    @Override
    public void run(ApplicationArguments args) {
        // Enforce first-wave all-or-nothing: all adapters above must resolve and projection checkpoints must exist.
        Long checkpointCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM reporting_read.projection_checkpoint
                        WHERE slice_name IN ('wos', 'scopus')
                        """,
                Long.class
        );
        if (checkpointCount == null || checkpointCount < 2) {
            throw new IllegalStateException(
                    "Postgres read-store requires completed H22.3 projection checkpoints for both wos and scopus slices."
            );
        }
    }
}
