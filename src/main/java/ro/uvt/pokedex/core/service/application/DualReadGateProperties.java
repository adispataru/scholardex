package ro.uvt.pokedex.core.service.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core.h22.dual-read-gate")
public class DualReadGateProperties {

    private int sampleSize = 5;
    private double p95RatioThreshold = 1.2d;
    private boolean groupReportRefreshEnabled = false;
    private String groupReportRefreshGroupId = "680fa885dbe2f57466f9f4d2";
    private String groupReportRefreshReportId = "682101ad04e38843635e0cba";
    private double groupReportRefreshP95ThresholdMs = 1200d;

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

    public void setGroupReportRefreshEnabled(boolean groupReportRefreshEnabled) {
        this.groupReportRefreshEnabled = groupReportRefreshEnabled;
    }

    public String getGroupReportRefreshGroupId() {
        return groupReportRefreshGroupId;
    }

    public void setGroupReportRefreshGroupId(String groupReportRefreshGroupId) {
        this.groupReportRefreshGroupId = groupReportRefreshGroupId;
    }

    public String getGroupReportRefreshReportId() {
        return groupReportRefreshReportId;
    }

    public void setGroupReportRefreshReportId(String groupReportRefreshReportId) {
        this.groupReportRefreshReportId = groupReportRefreshReportId;
    }

    public double getGroupReportRefreshP95ThresholdMs() {
        return groupReportRefreshP95ThresholdMs;
    }

    public void setGroupReportRefreshP95ThresholdMs(double groupReportRefreshP95ThresholdMs) {
        this.groupReportRefreshP95ThresholdMs = groupReportRefreshP95ThresholdMs;
    }
}
