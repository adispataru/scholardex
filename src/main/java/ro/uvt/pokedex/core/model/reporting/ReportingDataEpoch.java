package ro.uvt.pokedex.core.model.reporting;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Singleton counter of "the reporting data changed" events. Every data rebuild that can alter indicator
 * scores (WoS projection rebuilds, materialized-view refreshes, forum reconciles, CORE imports) bumps the
 * epoch; the {@code userIndicatorResults} cache fingerprint includes it, so cached LATEST results
 * self-invalidate on the next view instead of serving pre-rebuild scores until a manual refresh.
 */
@Data
@Document(collection = "reportingDataEpoch")
public class ReportingDataEpoch {

    public static final String SINGLETON_ID = "reporting-data-epoch";

    @Id
    private String id = SINGLETON_ID;
    private long epoch;
    private Instant updatedAt;
    private String lastReason;
}
