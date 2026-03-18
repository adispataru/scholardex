package ro.uvt.pokedex.core.service.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "core.h22.projection")
public class PostgresReportingProjectionProperties {

    private boolean enabled;
    private int chunkSize = 1000;
    private int statementTimeoutMs = 120000;
}
