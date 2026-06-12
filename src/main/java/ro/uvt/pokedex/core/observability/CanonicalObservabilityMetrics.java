package ro.uvt.pokedex.core.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

public final class CanonicalObservabilityMetrics {

    private CanonicalObservabilityMetrics() {
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

    // H56: recordSourceLinkTransition fires per source-link command (~1.2M+ per pipeline rebuild).
    // Cache Counter handles instead of re-resolving name+tags against the registry on every call.
    // Composite meters bind to registries added later, so caching at class-init time is safe.
    private static final java.util.concurrent.ConcurrentHashMap<String, io.micrometer.core.instrument.Counter> COUNTER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordSourceLinkTransition(String entityType, String fromState, String toState, String outcome) {
        String e = safe(entityType);
        String f = safe(fromState);
        String t = safe(toState);
        String o = safe(outcome);
        COUNTER_CACHE.computeIfAbsent("slt|" + e + '|' + f + '|' + t + '|' + o,
                key -> Metrics.counter("core.h19.source_link.transitions",
                        "entityType", e, "fromState", f, "toState", t, "outcome", o))
                .increment();
    }

    public static void recordConflictCreated(String entityType, String source, String reasonCode) {
        String e = safe(entityType);
        String s = safe(source);
        String r = safe(reasonCode);
        COUNTER_CACHE.computeIfAbsent("icc|" + e + '|' + s + '|' + r,
                key -> Metrics.counter("core.h19.identity_conflict.created",
                        "entityType", e, "source", s, "reasonCode", r))
                .increment();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }
}
