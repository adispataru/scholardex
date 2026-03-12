package ro.uvt.pokedex.core.service.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core.h22.dual-read-gate")
public class DualReadGateProperties {

    private int sampleSize = 5;
    private double p95RatioThreshold = 0.8d;
    private boolean wosIssnWarmupEnabled = true;
    private boolean groupReportRefreshEnabled = false;
    private double groupReportRefreshP95ThresholdMs = 2200d;

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    public double getP95RatioThreshold() {
        return p95RatioThreshold;
    }

    public void setP95RatioThreshold(double p95RatioThreshold) {
        this.p95RatioThreshold = p95RatioThreshold;
    }

    public boolean isGroupReportRefreshEnabled() {
        return groupReportRefreshEnabled;
    }

    public boolean isWosIssnWarmupEnabled() {
        return wosIssnWarmupEnabled;
    }

    public void setWosIssnWarmupEnabled(boolean wosIssnWarmupEnabled) {
        this.wosIssnWarmupEnabled = wosIssnWarmupEnabled;
    }

    public void setGroupReportRefreshEnabled(boolean groupReportRefreshEnabled) {
        this.groupReportRefreshEnabled = groupReportRefreshEnabled;
    }

    public double getGroupReportRefreshP95ThresholdMs() {
        return groupReportRefreshP95ThresholdMs;
    }

    public void setGroupReportRefreshP95ThresholdMs(double groupReportRefreshP95ThresholdMs) {
        this.groupReportRefreshP95ThresholdMs = groupReportRefreshP95ThresholdMs;
    }
}
