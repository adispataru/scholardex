package ro.uvt.pokedex.core.service.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core.h22.projection")
public class PostgresReportingProjectionProperties {

    private boolean enabled;
    private int chunkSize = 1000;
    private int statementTimeoutMs = 120000;
    private boolean dryRun = false;
    private String wosFingerprintFields = "count,max(updatedAt),max(createdAt),max(sourceVersion)";
    private String scopusFingerprintFields = "count,max(updatedAt),max(buildAt),max(createdAt)";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getStatementTimeoutMs() {
        return statementTimeoutMs;
    }

    public void setStatementTimeoutMs(int statementTimeoutMs) {
        this.statementTimeoutMs = statementTimeoutMs;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getWosFingerprintFields() {
        return wosFingerprintFields;
    }

    public void setWosFingerprintFields(String wosFingerprintFields) {
        this.wosFingerprintFields = wosFingerprintFields;
    }

    public String getScopusFingerprintFields() {
        return scopusFingerprintFields;
    }

    public void setScopusFingerprintFields(String scopusFingerprintFields) {
        this.scopusFingerprintFields = scopusFingerprintFields;
    }
}
