package ro.uvt.pokedex.core.service.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core.h22.dual-read-gate")
public class DualReadGateProperties {

    private int sampleSize = 5;
    private double p95RatioThreshold = 1.2d;

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
}
