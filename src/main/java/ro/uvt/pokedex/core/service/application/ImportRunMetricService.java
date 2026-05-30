package ro.uvt.pokedex.core.service.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.importing.ImportRunMetric;
import ro.uvt.pokedex.core.repository.importing.ImportRunMetricRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class ImportRunMetricService {

    private final ImportRunMetricRepository repository;
    private final Clock clock;

    @Autowired
    public ImportRunMetricService(ImportRunMetricRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ImportRunMetricService(ImportRunMetricRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<ImportRunMetric> record(String runId,
                                            String source,
                                            String entityType,
                                            String reason,
                                            long delta) {
        String normalizedRunId = normalize(runId);
        String normalizedSource = normalize(source);
        String normalizedEntityType = normalize(entityType);
        String normalizedReason = normalize(reason);
        if (normalizedRunId == null
                || normalizedSource == null
                || normalizedEntityType == null
                || normalizedReason == null
                || delta <= 0) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        ImportRunMetric metric = repository.findByRunIdAndSourceAndEntityTypeAndReason(
                normalizedRunId,
                normalizedSource,
                normalizedEntityType,
                normalizedReason
        ).orElseGet(ImportRunMetric::new);

        if (metric.getRunId() == null) {
            metric.setRunId(normalizedRunId);
            metric.setSource(normalizedSource);
            metric.setEntityType(normalizedEntityType);
            metric.setReason(normalizedReason);
            metric.setFirstSeenAt(now);
        } else if (metric.getFirstSeenAt() == null) {
            metric.setFirstSeenAt(now);
        }
        metric.setCount(metric.getCount() + delta);
        metric.setLastSeenAt(now);
        return Optional.of(repository.save(metric));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
