package ro.uvt.pokedex.core.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

public final class H19CanonicalMetrics {

    private H19CanonicalMetrics() {
    }

    public static void recordCanonicalBuildRun(String entity, String source, String outcome, long durationNanos) {
        Timer.builder("core.h19.canonical.build.duration")
                .tag("entity", safe(entity))
                .tag("source", safe(source))
                .tag("outcome", safe(outcome))
                .register(Metrics.globalRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        Metrics.counter("core.h19.canonical.build.runs",
                "entity", safe(entity),
                "source", safe(source),
                "outcome", safe(outcome)
        ).increment();
    }

    public static void recordReconcileRun(String reconcileType, String outcome, long durationNanos) {
        Timer.builder("core.h19.reconcile.duration")
                .tag("reconcileType", safe(reconcileType))
                .tag("outcome", safe(outcome))
                .register(Metrics.globalRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        Metrics.counter("core.h19.reconcile.runs",
                "reconcileType", safe(reconcileType),
                "outcome", safe(outcome)
        ).increment();
    }

    public static void recordSourceLinkTransition(String entityType, String fromState, String toState, String outcome) {
        Metrics.counter("core.h19.source_link.transitions",
                "entityType", safe(entityType),
                "fromState", safe(fromState),
                "toState", safe(toState),
                "outcome", safe(outcome)
        ).increment();
    }

    public static void recordConflictCreated(String entityType, String source, String reasonCode) {
        Metrics.counter("core.h19.identity_conflict.created",
                "entityType", safe(entityType),
                "source", safe(source),
                "reasonCode", safe(reasonCode)
        ).increment();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }
}
