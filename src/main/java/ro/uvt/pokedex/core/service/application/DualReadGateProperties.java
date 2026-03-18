package ro.uvt.pokedex.core.service.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "core.h22.dual-read-gate")
public class DualReadGateProperties {

    private int sampleSize = 5;
    private double p95RatioThreshold = 0.8d;
    private boolean wosIssnWarmupEnabled = true;
    private boolean groupReportRefreshEnabled = false;
    private double groupReportRefreshP95ThresholdMs = 2200d;
}
