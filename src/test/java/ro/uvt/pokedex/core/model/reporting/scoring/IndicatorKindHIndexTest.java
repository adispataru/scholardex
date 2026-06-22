package ro.uvt.pokedex.core.model.reporting.scoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H67 S4a: the HIndex aggregate kind round-trips through the legacy persisted (outputType, strategy) shape. */
class IndicatorKindHIndexTest {

    @Test
    void roundTripsEverySourceAndExcludeSelf() {
        for (HIndexSource source : HIndexSource.values()) {
            for (boolean excludeSelf : new boolean[]{false, true}) {
                IndicatorKind.HIndex kind = new IndicatorKind.HIndex(source, excludeSelf);
                IndicatorKind.LegacyShape legacy = kind.toLegacy();
                assertThat(legacy.strategyName()).isEqualTo("HIRSCH");
                assertThat(IndicatorKind.of(legacy.outputTypeName(), legacy.strategyName())).isEqualTo(kind);
            }
        }
    }

    @Test
    void parsesKnownLegacyStrings() {
        assertThat(IndicatorKind.of("HINDEX_WOS", "HIRSCH"))
                .isEqualTo(new IndicatorKind.HIndex(HIndexSource.WOS_VENUE, false));
        assertThat(IndicatorKind.of("HINDEX_SCOPUS_EXCLUDE_SELF", "HIRSCH"))
                .isEqualTo(new IndicatorKind.HIndex(HIndexSource.SCOPUS_VENUE, true));
        assertThat(new IndicatorKind.HIndex(HIndexSource.WOS_VENUE, true).strategy())
                .isEqualTo(ScoringStrategy.HIRSCH);
    }

    @Test
    void rejectsHIndexWithWrongStrategy() {
        assertThatThrownBy(() -> IndicatorKind.of("HINDEX_WOS", "AIS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HIRSCH");
    }
}
