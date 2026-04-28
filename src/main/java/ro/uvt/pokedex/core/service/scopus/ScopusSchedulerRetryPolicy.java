package ro.uvt.pokedex.core.service.scopus;

import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.time.Instant;

final class ScopusSchedulerRetryPolicy {

    boolean isReadyForAttempt(String nextAttemptAt, Instant now) {
        if (nextAttemptAt == null || nextAttemptAt.isBlank()) {
            return true;
        }
        try {
            return !Instant.parse(nextAttemptAt).isAfter(now);
        } catch (Exception ex) {
            return true;
        }
    }

    long computeBackoffSeconds(int attemptCount, long initialBackoffSeconds, long maxBackoffSeconds) {
        long exponent = Math.max(0, attemptCount - 1);
        long backoff = initialBackoffSeconds * (1L << Math.min(exponent, 10));
        return Math.min(backoff, maxBackoffSeconds);
    }

    FailureDecision decideFailure(
            IntegrationException exception,
            int attemptCount,
            int configuredMaxAttempts,
            int defaultMaxAttempts,
            long initialBackoffSeconds,
            long maxBackoffSeconds,
            Instant now
    ) {
        int maxAttempts = resolveMaxAttempts(configuredMaxAttempts, defaultMaxAttempts);
        if (exception.isRetryable() && attemptCount < maxAttempts) {
            long backoff = computeBackoffSeconds(attemptCount, initialBackoffSeconds, maxBackoffSeconds);
            return new FailureDecision(
                    Status.PENDING,
                    "RETRY_SCHEDULED: " + exception.getMessage(),
                    now.plusSeconds(backoff).toString(),
                    false,
                    maxAttempts,
                    exception.getErrorCode().name(),
                    exception.getMessage()
            );
        }
        return new FailureDecision(
                Status.FAILED,
                "FAILED: " + exception.getMessage(),
                null,
                true,
                maxAttempts,
                exception.getErrorCode().name(),
                exception.getMessage()
        );
    }

    private int resolveMaxAttempts(int configuredMaxAttempts, int defaultMaxAttempts) {
        return configuredMaxAttempts > 0 ? configuredMaxAttempts : defaultMaxAttempts;
    }

    record FailureDecision(
            Status status,
            String message,
            String nextAttemptAt,
            boolean terminal,
            int maxAttempts,
            String lastErrorCode,
            String lastErrorMessage
    ) {
    }
}
