package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.importing.ImportRunMetric;
import ro.uvt.pokedex.core.repository.importing.ImportRunMetricRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportRunMetricServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-30T09:15:00Z");

    @Mock
    private ImportRunMetricRepository repository;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void createsAggregateMetricForNewRunSourceEntityAndReason() {
        when(repository.findByRunIdAndSourceAndEntityTypeAndReason(
                "run-1",
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "AUTHOR",
                "auto-relinked-identity-link"
        )).thenReturn(Optional.empty());
        when(repository.save(any(ImportRunMetric.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImportRunMetricService service = new ImportRunMetricService(repository, clock);

        Optional<ImportRunMetric> result = service.record(
                "run-1",
                "SCOPUS_PYTHON_AUTHOR_WORKS",
                "AUTHOR",
                "auto-relinked-identity-link",
                3
        );

        ArgumentCaptor<ImportRunMetric> captor = ArgumentCaptor.forClass(ImportRunMetric.class);
        verify(repository).save(captor.capture());
        ImportRunMetric saved = captor.getValue();
        assertThat(result).containsSame(saved);
        assertThat(saved.getRunId()).isEqualTo("run-1");
        assertThat(saved.getSource()).isEqualTo("SCOPUS_PYTHON_AUTHOR_WORKS");
        assertThat(saved.getEntityType()).isEqualTo("AUTHOR");
        assertThat(saved.getReason()).isEqualTo("auto-relinked-identity-link");
        assertThat(saved.getCount()).isEqualTo(3);
        assertThat(saved.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(saved.getLastSeenAt()).isEqualTo(NOW);
    }

    @Test
    void incrementsExistingAggregateMetricAndPreservesFirstSeenAt() {
        Instant firstSeenAt = Instant.parse("2026-05-29T10:00:00Z");
        ImportRunMetric existing = new ImportRunMetric();
        existing.setId("metric-1");
        existing.setRunId("run-1");
        existing.setSource("SCOPUS");
        existing.setEntityType("AUTHORSHIP");
        existing.setReason("skipped-edge-evidence");
        existing.setCount(7);
        existing.setFirstSeenAt(firstSeenAt);
        existing.setLastSeenAt(Instant.parse("2026-05-29T10:05:00Z"));
        when(repository.findByRunIdAndSourceAndEntityTypeAndReason(
                "run-1",
                "SCOPUS",
                "AUTHORSHIP",
                "skipped-edge-evidence"
        )).thenReturn(Optional.of(existing));
        when(repository.save(any(ImportRunMetric.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ImportRunMetricService service = new ImportRunMetricService(repository, clock);

        Optional<ImportRunMetric> result = service.record(
                "run-1",
                "SCOPUS",
                "AUTHORSHIP",
                "skipped-edge-evidence",
                4
        );

        assertThat(result).containsSame(existing);
        assertThat(existing.getCount()).isEqualTo(11);
        assertThat(existing.getFirstSeenAt()).isEqualTo(firstSeenAt);
        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
        verify(repository).save(existing);
    }

    @Test
    void ignoresBlankMetricKeysAndNonPositiveDeltas() {
        ImportRunMetricService service = new ImportRunMetricService(repository, clock);

        assertThat(service.record(" ", "SCOPUS", "AUTHOR", "reason", 1)).isEmpty();
        assertThat(service.record("run-1", "", "AUTHOR", "reason", 1)).isEmpty();
        assertThat(service.record("run-1", "SCOPUS", null, "reason", 1)).isEmpty();
        assertThat(service.record("run-1", "SCOPUS", "AUTHOR", " ", 1)).isEmpty();
        assertThat(service.record("run-1", "SCOPUS", "AUTHOR", "reason", 0)).isEmpty();

        verifyNoInteractions(repository);
    }
}
