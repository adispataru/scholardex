package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PluralRulesTest {

    private static final Locale RO = Locale.forLanguageTag("ro");

    @Test
    void romanianUsesThreeFormsWithTheParticleBoundaryAtTwenty() {
        assertThat(PluralRules.select(RO, 1)).isEqualTo(PluralRules.Category.ONE);
        assertThat(PluralRules.select(RO, 2)).isEqualTo(PluralRules.Category.FEW);
        assertThat(PluralRules.select(RO, 19)).isEqualTo(PluralRules.Category.FEW);
        // 20 crosses into the "de" form — the case a two-form port gets wrong
        assertThat(PluralRules.select(RO, 20)).isEqualTo(PluralRules.Category.OTHER);
        assertThat(PluralRules.select(RO, 21)).isEqualTo(PluralRules.Category.OTHER);
    }

    @Test
    void romanianTreatsZeroAndTheHundredWrapAsFew() {
        assertThat(PluralRules.select(RO, 0)).isEqualTo(PluralRules.Category.FEW);   // "0 publicații"
        assertThat(PluralRules.select(RO, 101)).isEqualTo(PluralRules.Category.FEW); // "101 publicații"
        assertThat(PluralRules.select(RO, 119)).isEqualTo(PluralRules.Category.FEW);
        assertThat(PluralRules.select(RO, 100)).isEqualTo(PluralRules.Category.OTHER); // "100 de publicații"
        assertThat(PluralRules.select(RO, 120)).isEqualTo(PluralRules.Category.OTHER);
    }

    @Test
    void englishIsTwoForm() {
        assertThat(PluralRules.select(Locale.ENGLISH, 1)).isEqualTo(PluralRules.Category.ONE);
        assertThat(PluralRules.select(Locale.ENGLISH, 0)).isEqualTo(PluralRules.Category.OTHER);
        assertThat(PluralRules.select(Locale.ENGLISH, 20)).isEqualTo(PluralRules.Category.OTHER);
    }

    @Test
    void keyAppendsTheCategorySuffixAndDefaultsToRomanianWithoutALocale() {
        assertThat(PluralRules.key("landing.welcome.updates", RO, 21)).isEqualTo("landing.welcome.updates.other");
        assertThat(PluralRules.key("landing.welcome.updates", RO, 3)).isEqualTo("landing.welcome.updates.few");
        assertThat(PluralRules.key("landing.welcome.updates", null, 1)).isEqualTo("landing.welcome.updates.one");
    }
}
