package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.ScoringStrategy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H52 slice 6 — proves the Map-registry dispatcher behaves identically to the pre-v1
 * if/else ladder for every production strategy, and that the startup invariants fire
 * loudly when violated.
 *
 * <p>Replaces the pre-v1 mock-each-concrete-service style — the new factory takes
 * {@code List<ScoringService>} so any conforming fake is enough.</p>
 */
class ScoringFactoryServiceTest {

    private static ScoringService fake(ScoringStrategy strategy) {
        return new ScoringService() {
            @Override public ScoringStrategy strategy() { return strategy; }
            @Override public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) { return null; }
            @Override public Score getScore(ActivityInstance activity, Indicator indicator) { return null; }
            @Override public String getDescription() { return "fake:" + strategy; }
        };
    }

    /**
     * Strategies that must be registered as beans. Mirrors the production ladder.
     * {@code GENERIC_COUNT} and {@code GENERIC_ACTIVITY} are handled inline by the
     * call sites and are deliberately not registered.
     */
    private static Set<ScoringStrategy> beanBackedStrategies() {
        Set<ScoringStrategy> all = EnumSet.allOf(ScoringStrategy.class);
        all.remove(ScoringStrategy.GENERIC_COUNT);
        all.remove(ScoringStrategy.GENERIC_ACTIVITY);
        return all;
    }

    private static List<ScoringService> oneOfEach() {
        List<ScoringService> services = new ArrayList<>();
        for (ScoringStrategy s : beanBackedStrategies()) {
            services.add(fake(s));
        }
        return services;
    }

    private static ScoringFactoryService init(List<ScoringService> services) {
        ScoringFactoryService f = new ScoringFactoryService(services);
        f.verifyRegistry();
        return f;
    }

    // ---------- Production parity ----------

    @Test
    void everyBeanBackedStrategyResolvesViaTheRegistry() {
        ScoringFactoryService factory = init(oneOfEach());
        for (ScoringStrategy s : beanBackedStrategies()) {
            ScoringService svc = factory.getScoringService(s);
            assertNotNull(svc, "no bean returned for " + s);
            assertEquals(s, svc.strategy(), "wrong bean returned for " + s);
        }
    }

    @Test
    void legacyEnumLookupBridgesToV1() {
        ScoringFactoryService factory = init(oneOfEach());
        Indicator.Strategy[] legacyValues = {
                Indicator.Strategy.CS_CONFERENCE, Indicator.Strategy.CS_JOURNAL,
                Indicator.Strategy.CS, Indicator.Strategy.RIS, Indicator.Strategy.AIS,
                Indicator.Strategy.CS_SENSE, Indicator.Strategy.UNI_RANKING,
                Indicator.Strategy.CNCSIS, Indicator.Strategy.ART_EVENT,
                Indicator.Strategy.IMPACT_FACTOR, Indicator.Strategy.ECONOMICS_JOURNAL_AIS,
        };
        for (Indicator.Strategy legacy : legacyValues) {
            ScoringService viaLegacy = factory.getScoringService(legacy);
            ScoringService viaV1 = factory.getScoringService(ScoringStrategy.fromLegacy(legacy));
            assertSame(viaLegacy, viaV1, "legacy and v1 lookups returned different beans for " + legacy);
        }
    }

    // ---------- Invariants ----------

    @Test
    void duplicateStrategyClaimFailsAtConstruction() {
        List<ScoringService> services = oneOfEach();
        services.add(fake(ScoringStrategy.AIS)); // second AIS bean
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ScoringFactoryService(services));
        assertTrue(ex.getMessage().contains("Duplicate"));
        assertTrue(ex.getMessage().contains("AIS"));
    }

    @Test
    void missingStrategyFailsAtVerifyRegistry() {
        List<ScoringService> services = new ArrayList<>();
        for (ScoringStrategy s : beanBackedStrategies()) {
            if (s == ScoringStrategy.IMPACT_FACTOR) continue; // drop one
            services.add(fake(s));
        }
        ScoringFactoryService f = new ScoringFactoryService(services);
        IllegalStateException ex = assertThrows(IllegalStateException.class, f::verifyRegistry);
        assertTrue(ex.getMessage().contains("IMPACT_FACTOR"));
    }

    @Test
    void inlineStrategiesAreRejectedAtLookup() {
        ScoringFactoryService factory = init(oneOfEach());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getScoringService(ScoringStrategy.GENERIC_COUNT));
        assertTrue(ex.getMessage().contains("inline"));
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> factory.getScoringService(Indicator.Strategy.GENERIC_ACTIVITY));
        assertTrue(ex2.getMessage().contains("inline"));
    }

    @Test
    void nullStrategyThrows() {
        ScoringFactoryService factory = init(oneOfEach());
        assertThrows(IllegalArgumentException.class,
                () -> factory.getScoringService((ScoringStrategy) null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.getScoringService((Indicator.Strategy) null));
    }

    @Test
    void serviceThatReturnsNullStrategyIsRejected() {
        ScoringService rogue = new ScoringService() {
            @Override public ScoringStrategy strategy() { return null; }
            @Override public Score getScore(ScoringPublicationReadModel publication, Indicator indicator) { return null; }
            @Override public Score getScore(ActivityInstance activity, Indicator indicator) { return null; }
            @Override public String getDescription() { return "rogue"; }
        };
        List<ScoringService> services = oneOfEach();
        services.add(rogue);
        assertThrows(IllegalStateException.class, () -> new ScoringFactoryService(services));
    }
}
