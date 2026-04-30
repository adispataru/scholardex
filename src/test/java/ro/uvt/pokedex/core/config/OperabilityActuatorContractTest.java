package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.CoreApplication;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.application.JdbcPostgresMaterializedViewRefreshService;
import ro.uvt.pokedex.core.service.application.JdbcPostgresReportingProjectionService;
import ro.uvt.pokedex.core.service.application.PostgresReadCutoverGuard;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexProjectionBuilderService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = CoreApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.task.scheduling.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@Import(OperabilityActuatorContractTest.TestConfig.class)
class OperabilityActuatorContractTest {

    @Autowired
    private MockMvc mockMvc;
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
    private ScholardexProjectionBuilderService scopusProjectionBuilderService;

    @Test
    void healthProbesArePubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void metricsEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
