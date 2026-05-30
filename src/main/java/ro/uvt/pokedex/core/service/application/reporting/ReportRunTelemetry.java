package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.GroupIndividualReportRun;

import java.util.Locale;

/**
 * Emits the structured timing log for a group-report refresh. Warns above the slow threshold,
 * otherwise debug. Kept out of {@link GroupReportRunner} so the runner stays pure compute.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportRunTelemetry {

    private static final long REFRESH_SLOW_WARN_THRESHOLD_MS = 5_000L;
    private static final String READ_STORE = "POSTGRES";

    public void logRefresh(
            String groupId, String reportId, int researcherCount,
            GroupIndividualReportRun run, GroupReportRunner.TimingsSummary timings,
            long lookupMs, long computeMs, long saveMs, long totalMs) {
        String message = String.format(
                Locale.ROOT,
                "Group report refresh timings: groupId=%s reportId=%s readStore=%s researchers=%d status=%s errors=%d "
                        + "counts[publications=%d, citationFacts=%d, citationIndicators=%d, activityIndicators=%d] "
                        + "timingsMs[lookup=%d, compute=%d, save=%d, total=%d] "
                        + "computeMs[authorLookup=%d, publicationLoad=%d, activityLoad=%d, citationLoad=%d, "
                        + "citationBasePrecompute=%d, scoring=%d, publicationScoring=%d, activityScoring=%d, "
                        + "citationScoring=%d, selector=%d, thresholdBuild=%d]",
                groupId, reportId, READ_STORE, researcherCount, run.getStatus(),
                run.getBuildErrors() == null ? 0 : run.getBuildErrors().size(),
                timings.publicationsProcessed(), timings.citationFacts(),
                timings.citationIndicators(), timings.activityIndicators(),
                lookupMs, computeMs, saveMs, totalMs,
                timings.authorLookupMs(), timings.publicationLoadMs(), timings.activityLoadMs(),
                timings.citationLoadMs(), timings.citationBasePrecomputeMs(), timings.scoringMs(),
                timings.publicationScoringMs(), timings.activityScoringMs(),
                timings.citationScoringMs(), timings.selectorMs(), timings.thresholdBuildMs());
        if (totalMs > REFRESH_SLOW_WARN_THRESHOLD_MS) {
            log.warn(message);
        } else if (log.isDebugEnabled()) {
            log.debug(message);
        }
    }
}
