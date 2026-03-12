package ro.uvt.pokedex.core.service.application;

import java.time.Instant;
import java.util.List;

public interface DualReadGateService {

    DualReadGateRunSummary runFullGate();

    DualReadGateStatusSnapshot latestStatus();

    record DualReadGateRunSummary(
            String runId,
            String status,
            int sampleSize,
            double p95RatioThreshold,
            Instant startedAt,
            Instant completedAt,
            List<DualReadScenarioResult> scenarios,
            String errorSample
    ) {
    }

    record DualReadScenarioResult(
            String scenarioId,
            String scenarioType,
            String status,
            boolean parityPassed,
            boolean performancePassed,
            double mongoAvgMs,
            double mongoP95Ms,
            double postgresAvgMs,
            double postgresP95Ms,
            Double p95Ratio,
            Double p95ThresholdMs,
            String mismatchSample,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    record DualReadGateStatusSnapshot(
            DualReadGateRunSummary latestRun
    ) {
    }
}
