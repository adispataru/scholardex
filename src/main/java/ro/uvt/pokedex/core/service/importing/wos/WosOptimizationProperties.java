package ro.uvt.pokedex.core.service.importing.wos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    public int getFactChunkSize() {
        return factChunkSize;
    }

    public void setFactChunkSize(int factChunkSize) {
        this.factChunkSize = factChunkSize;
    }

    public int getIngestPersistBatchSize() {
        return ingestPersistBatchSize;
    }

    public void setIngestPersistBatchSize(int ingestPersistBatchSize) {
        this.ingestPersistBatchSize = ingestPersistBatchSize;
    }

    public int getProjectionWriteChunkSize() {
        return projectionWriteChunkSize;
    }

    public void setProjectionWriteChunkSize(int projectionWriteChunkSize) {
        this.projectionWriteChunkSize = projectionWriteChunkSize;
    }

    public int getIdentityLruMaxSize() {
        return identityLruMaxSize;
    }

    public void setIdentityLruMaxSize(int identityLruMaxSize) {
        this.identityLruMaxSize = identityLruMaxSize;
    }

    public boolean isPreflightIndexesEnabled() {
        return preflightIndexesEnabled;
    }

    public void setPreflightIndexesEnabled(boolean preflightIndexesEnabled) {
        this.preflightIndexesEnabled = preflightIndexesEnabled;
    }

    public int getTelemetryHeartbeatInterval() {
        return telemetryHeartbeatInterval;
    }

    public void setTelemetryHeartbeatInterval(int telemetryHeartbeatInterval) {
        this.telemetryHeartbeatInterval = telemetryHeartbeatInterval;
    }

    public long getSlowChunkThresholdMs() {
        return slowChunkThresholdMs;
    }

    public void setSlowChunkThresholdMs(long slowChunkThresholdMs) {
        this.slowChunkThresholdMs = slowChunkThresholdMs;
    }
}

