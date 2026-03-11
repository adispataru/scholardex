package ro.uvt.pokedex.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import ro.uvt.pokedex.core.service.CacheService;

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
