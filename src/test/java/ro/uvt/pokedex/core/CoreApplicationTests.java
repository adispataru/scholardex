package ro.uvt.pokedex.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.application.JdbcPostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.JdbcPostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.PostgresReadCutoverGuard;
import ro.uvt.pokedex.core.service.importing.scopus.ScopusProjectionBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosProjectionBuilderService;
import ro.uvt.pokedex.core.service.application.PostgresReportingLookupFacade;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAffiliationReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexForumReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexProjectionReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosCategoryReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingDetailsReadPort;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingReadPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.task.scheduling.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
class CoreApplicationTests {
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @MockitoBean
    private PostgresWosCategoryReadPort postgresWosCategoryReadPort;
    @MockitoBean
    private PostgresWosRankingDetailsReadPort postgresWosRankingDetailsReadPort;
    @MockitoBean
    private PostgresWosRankingReadPort postgresWosRankingReadPort;
    @MockitoBean
    private PostgresReportingLookupFacade postgresReportingLookupFacade;
    @MockitoBean
    private PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;
    @MockitoBean
    private PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    @MockitoBean
    private PostgresScholardexProjectionReadPort postgresScholardexProjectionReadPort;
    @MockitoBean
    private PostgresScholardexAffiliationReadPort postgresScholardexAffiliationReadPort;
    @MockitoBean
    private PostgresScholardexForumReadPort postgresScholardexForumReadPort;
    @MockitoBean
    private JdbcPostgresReportingProjectionService jdbcPostgresReportingProjectionService;
    @MockitoBean
    private JdbcPostgresMaterializedViewRefreshService jdbcPostgresMaterializedViewRefreshService;
    @MockitoBean
    private PostgresReadCutoverGuard postgresReadCutoverGuard;
    @MockitoBean
    private PlatformTransactionManager platformTransactionManager;
    @MockitoBean
    private WosProjectionBuilderService wosProjectionBuilderService;
    @MockitoBean
    private ScopusProjectionBuilderService scopusProjectionBuilderService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebClient scopusPythonClient;

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void requiredBeansAreCreated() {
        assertThat(applicationContext.containsBean("scopusPythonClient")).isTrue();
        assertThat(scopusPythonClient).isNotNull();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "initDatabase")
        CommandLineRunner initDatabase() {
            return args -> {
            };
        }
    }
}
