package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** H65: the physics Nef brackets (Ordin 6129/2016, Anexa 1 — Fizică). */
class EffectiveAuthorCountSupportTest {

    @Test
    void bracketsMatchTheStandard() {
        // n ≤ 5 → n
        assertEquals(1.0, EffectiveAuthorCountSupport.computeNef(1));
        assertEquals(5.0, EffectiveAuthorCountSupport.computeNef(5));
        // 5 < n ≤ 15 → (n+5)/2
        assertEquals(5.5, EffectiveAuthorCountSupport.computeNef(6));   // 11/2
        assertEquals(10.0, EffectiveAuthorCountSupport.computeNef(15)); // 20/2
        // 15 < n ≤ 75 → (n+15)/3
        assertEquals((16 + 15) / 3.0, EffectiveAuthorCountSupport.computeNef(16));
        assertEquals(30.0, EffectiveAuthorCountSupport.computeNef(75)); // 90/3
        // n > 75 → (n+45)/4
        assertEquals((76 + 45) / 4.0, EffectiveAuthorCountSupport.computeNef(76));
        assertEquals((100 + 45) / 4.0, EffectiveAuthorCountSupport.computeNef(100));
    }

    @Test
    void zeroAuthorsYieldsZeroNefSoSOverNefIsGuardedDownstream() {
        // Nef(0) = 0; S/Nef is then non-finite and the scoring guard clamps it to 0 (see ScientificProductionService).
        assertEquals(0.0, EffectiveAuthorCountSupport.computeNef(0));
    }
}
