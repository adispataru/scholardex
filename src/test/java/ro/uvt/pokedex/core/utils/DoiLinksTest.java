package ro.uvt.pokedex.core.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DoiLinksTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "10.1007/978-3-642-35326-0_26            | 10.1007/978-3-642-35326-0_26",
            "https://doi.org/10.48550/arxiv.0905.4601 | 10.48550/arxiv.0905.4601",
            "http://dx.doi.org/10.3233/WEB-190396     | 10.3233/WEB-190396",
            "doi:10.1016/j.fsidi.2026.302128          | 10.1016/j.fsidi.2026.302128",
            "  DOI: 10.5555/2893559.2893573           | 10.5555/2893559.2893573",
    })
    void normalizesEveryShapeTheCorpusHolds(String raw, String bare) {
        assertThat(DoiLinks.normalize(raw)).isEqualTo(bare);
        assertThat(DoiLinks.resolverUrl(raw)).isEqualTo("https://doi.org/" + bare);
        assertThat(new DoiLinks().url(raw)).isEqualTo("https://doi.org/" + bare);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "n/a", "10.1007", "10.1007/", "2-s2.0-85205088909", "https://doi.org/"})
    void rejectsValuesThatAreNotDois(String raw) {
        assertThat(DoiLinks.normalize(raw)).isNull();
        assertThat(DoiLinks.resolverUrl(raw)).isNull();
    }
}
