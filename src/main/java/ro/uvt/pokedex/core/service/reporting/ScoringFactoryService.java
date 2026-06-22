package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * H52 slice 6: Map-registry dispatcher. Replaces the pre-v1 hand-written if/else ladder
 * over the former {@code Indicator.Strategy} enum. Spring autowires every
 * {@link ScoringService} bean; the constructor indexes them by
 * {@link ScoringService#strategy()} into an {@link EnumMap}. Look-up is O(1).
 *
 * <p>Adding a new strategy is a three-step recipe with zero churn here:</p>
 * <ol>
 *   <li>Add the enum value to {@link ScoringStrategy}.</li>
 *   <li>Create a new {@code @Service} implementing {@link ScoringService} that returns
 *       the new enum value from {@code strategy()}.</li>
 *   <li>That's it. No edit here.</li>
 * </ol>
 *
 * <p>Startup invariants enforced in {@link #verifyRegistry()}:</p>
 * <ul>
 *   <li>No two beans claim the same strategy (duplicate would silently shadow).</li>
 *   <li>Every {@link ScoringStrategy} enum value except {@code GENERIC_COUNT} and
 *       {@code GENERIC_ACTIVITY} is registered — the two generic strategies are
 *       handled inline in {@link ScientificProductionService} and
 *       {@link ActivityReportingService} respectively (they don't load a strategy
 *       service in the pre-v1 path either).</li>
 * </ul>
 */
@Service
@Slf4j
public class ScoringFactoryService {

    /** Strategies handled inline at the call site; not registered as beans. */
    private static final java.util.Set<ScoringStrategy> INLINE_STRATEGIES =
            // H67 S4a: HIRSCH is the aggregate (non-additive) h-index, computed inline at the combine step — no bean.
            java.util.EnumSet.of(ScoringStrategy.GENERIC_COUNT, ScoringStrategy.GENERIC_ACTIVITY, ScoringStrategy.HIRSCH);

    private final Map<ScoringStrategy, ScoringService> registry;

    public ScoringFactoryService(List<ScoringService> services) {
        Map<ScoringStrategy, ScoringService> map = new EnumMap<>(ScoringStrategy.class);
        for (ScoringService svc : services) {
            ScoringStrategy strategy = svc.strategy();
            if (strategy == null) {
                throw new IllegalStateException(
                        "ScoringService " + svc.getClass().getSimpleName() + " returned null strategy()");
            }
            ScoringService prev = map.put(strategy, svc);
            if (prev != null) {
                throw new IllegalStateException("Duplicate ScoringService registration for "
                        + strategy + ": " + prev.getClass().getSimpleName()
                        + " and " + svc.getClass().getSimpleName());
            }
        }
        this.registry = map;
    }

    @PostConstruct
    void verifyRegistry() {
        for (ScoringStrategy strategy : ScoringStrategy.values()) {
            if (INLINE_STRATEGIES.contains(strategy)) continue;
            if (!registry.containsKey(strategy)) {
                throw new IllegalStateException(
                        "No ScoringService bean registered for " + strategy
                                + "; add an implementation or list it in INLINE_STRATEGIES");
            }
        }
        log.info("ScoringFactoryService registry verified: {} strategies bound, {} handled inline",
                registry.size(), INLINE_STRATEGIES.size());
    }

    /**
     * H52 slice 11d.5: name-based lookup. Bridges callers that hold the legacy
     * strategy name as a String (e.g. derived from {@code IndicatorKind.toLegacy().strategyName()}).
     */
    public ScoringService getScoringService(String strategyName) {
        if (strategyName == null) {
            throw new IllegalArgumentException("Scoring strategy cannot be null");
        }
        ScoringStrategy typed;
        try {
            typed = ScoringStrategy.valueOf(strategyName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported scoring strategy: " + strategyName, ex);
        }
        return getScoringService(typed);
    }

    /** v1 look-up by typed {@link ScoringStrategy}. */
    public ScoringService getScoringService(ScoringStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Scoring strategy cannot be null");
        }
        ScoringService svc = registry.get(strategy);
        if (svc == null) {
            if (INLINE_STRATEGIES.contains(strategy)) {
                throw new IllegalArgumentException("Strategy " + strategy
                        + " is handled inline at the call site; no ScoringService is available");
            }
            throw new IllegalArgumentException("Unsupported scoring strategy: " + strategy);
        }
        return svc;
    }
}
