package ro.uvt.pokedex.core.service.scopus;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.tasks.Status;
import ro.uvt.pokedex.core.service.integration.IntegrationErrorCode;
import ro.uvt.pokedex.core.service.integration.IntegrationException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopusSchedulerRetryPolicyTest {

    private final ScopusSchedulerRetryPolicy policy = new ScopusSchedulerRetryPolicy();

    @Test
    void readyForAttemptTreatsMissingPastAndMalformedTimestampsAsReady() {
        Instant now = Instant.parse("2026-04-28T10:00:00Z");

        assertTrue(policy.isReadyForAttempt(null, now));
        assertTrue(policy.isReadyForAttempt("   ", now));
        assertTrue(policy.isReadyForAttempt("not-an-instant", now));
        assertTrue(policy.isReadyForAttempt("2026-04-28T09:59:59Z", now));
        assertFalse(policy.isReadyForAttempt("2026-04-28T10:00:01Z", now));
    }

    @Test
    void backoffUsesInitialDelayDoublesByAttemptAndCapsAtMax() {
        assertEquals(60L, policy.computeBackoffSeconds(0, 60L, 3_600L));
        assertEquals(60L, policy.computeBackoffSeconds(1, 60L, 3_600L));
        assertEquals(120L, policy.computeBackoffSeconds(2, 60L, 3_600L));
        assertEquals(3_600L, policy.computeBackoffSeconds(20, 60L, 3_600L));
    }

    @Test
    void failureDecisionSchedulesRetryWhenRetryableAndAttemptsRemain() {
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        IntegrationException exception = new IntegrationException(
                IntegrationErrorCode.EXTERNAL_TIMEOUT,
                true,
                "external timeout"
        );

        ScopusSchedulerRetryPolicy.FailureDecision decision = policy.decideFailure(
                exception,
                2,
                3,
                5,
                60L,
                3_600L,
                now
        );

        assertEquals(Status.PENDING, decision.status());
        assertEquals("RETRY_SCHEDULED: external timeout", decision.message());
        assertEquals("2026-04-28T10:02:00Z", decision.nextAttemptAt());
        assertFalse(decision.terminal());
        assertEquals(3, decision.maxAttempts());
        assertEquals("EXTERNAL_TIMEOUT", decision.lastErrorCode());
        assertEquals("external timeout", decision.lastErrorMessage());
    }

    @Test
    void failureDecisionFailsWhenExceptionIsNotRetryable() {
        IntegrationException exception = new IntegrationException(
                IntegrationErrorCode.EXTERNAL_BAD_PAYLOAD,
                false,
                "bad payload"
        );

        ScopusSchedulerRetryPolicy.FailureDecision decision = policy.decideFailure(
                exception,
                1,
                3,
                5,
                60L,
                3_600L,
                Instant.parse("2026-04-28T10:00:00Z")
        );

        assertEquals(Status.FAILED, decision.status());
        assertEquals("FAILED: bad payload", decision.message());
        assertNull(decision.nextAttemptAt());
        assertTrue(decision.terminal());
        assertEquals(3, decision.maxAttempts());
        assertEquals("EXTERNAL_BAD_PAYLOAD", decision.lastErrorCode());
        assertEquals("bad payload", decision.lastErrorMessage());
    }

    @Test
    void failureDecisionUsesDefaultMaxAttemptsWhenTaskMaxAttemptsIsUnset() {
        IntegrationException exception = new IntegrationException(
                IntegrationErrorCode.EXTERNAL_5XX,
                true,
                "server error"
        );

        ScopusSchedulerRetryPolicy.FailureDecision decision = policy.decideFailure(
                exception,
                3,
                0,
                3,
                60L,
                3_600L,
                Instant.parse("2026-04-28T10:00:00Z")
        );

        assertEquals(Status.FAILED, decision.status());
        assertEquals(3, decision.maxAttempts());
        assertTrue(decision.terminal());
    }
}
