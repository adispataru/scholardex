package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "general.init.admin-user.startup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AdminUserBootstrapRunner implements CommandLineRunner {

    private final GeneralInitializationService generalInitializationService;

    @Override
    public void run(String... args) {
        GeneralInitializationService.GeneralInitializationStepResult result =
                generalInitializationService.runAdminUserBootstrap();
        if (result.success()) {
            log.info("Startup admin user bootstrap completed: durationMs={}, details={}",
                    result.durationMs(),
                    result.message());
        } else {
            log.error("Startup admin user bootstrap failed: durationMs={}, details={}",
                    result.durationMs(),
                    result.message());
        }
    }
}
