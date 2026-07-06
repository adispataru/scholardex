package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ReportingDataEpoch;

import java.time.Instant;
import java.util.Optional;

/**
 * The reporting data epoch — see {@link ReportingDataEpoch}. {@code bump} is called by the deliberate
 * list of data-rebuild chokepoints; {@code currentEpoch} feeds the indicator-result cache fingerprint.
 * Persisted in Mongo so the value (and therefore every cached fingerprint) is stable across app restarts
 * when the data has not changed. A missing document reads as epoch 0, also stable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportingDataEpochService {

    private final MongoTemplate mongoTemplate;
    private final ReportingLookupMemoization reportingLookupMemoization;

    /** The current epoch; memoized in the surrounding refresh scope (one read per report refresh). */
    public long currentEpoch() {
        return reportingLookupMemoization.getOrCompute("userReport", "reportingDataEpoch", "singleton", () -> {
            ReportingDataEpoch doc = mongoTemplate.findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class);
            return doc == null ? 0L : doc.getEpoch();
        });
    }

    /** The full epoch document (bump timestamp + reason), for staleness display; memoized like {@link #currentEpoch}. */
    public Optional<ReportingDataEpoch> currentEpochInfo() {
        return Optional.ofNullable(
                reportingLookupMemoization.getOrCompute("userReport", "reportingDataEpochDoc", "singleton", () ->
                        mongoTemplate.findById(ReportingDataEpoch.SINGLETON_ID, ReportingDataEpoch.class)));
    }

    /** Atomically increment the epoch (upserting the singleton) and record why. Returns the new value. */
    public long bump(String reason) {
        Query query = new Query(Criteria.where("_id").is(ReportingDataEpoch.SINGLETON_ID));
        Update update = new Update()
                .inc("epoch", 1)
                .set("updatedAt", Instant.now())
                .set("lastReason", reason);
        ReportingDataEpoch doc = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), ReportingDataEpoch.class);
        long epoch = doc == null ? 0L : doc.getEpoch();
        log.info("reporting data epoch bumped to {} ({})", epoch, reason);
        return epoch;
    }
}
