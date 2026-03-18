package ro.uvt.pokedex.core.service.importing.wos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "core.wos.optimization")
public class WosOptimizationProperties {

    private int factChunkSize = 1000;
    private int ingestPersistBatchSize = 1000;
    private int projectionWriteChunkSize = 1000;
    private int identityLruMaxSize = 200000;
    private boolean preflightIndexesEnabled = true;
    private int telemetryHeartbeatInterval = 10000;
    private long slowChunkThresholdMs = 2000L;
}
