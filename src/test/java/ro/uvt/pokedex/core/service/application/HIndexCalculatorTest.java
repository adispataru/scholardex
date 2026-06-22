package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** H67: the generalized h-index + the four-source breakdown. */
class HIndexCalculatorTest {

    private static ScholardexPublicationView pub(int citedBy, int graph, int scopus, int wos) {
        ScholardexPublicationView v = new ScholardexPublicationView();
        v.setCitedbyCount(citedBy);
        v.setGraphCitationCount(graph);
        v.setScopusCitationCount(scopus);
        v.setWosCitationCount(wos);
        return v;
    }

    @Test
    void breakdownComputesEachSourceIndependently() {
        // 3 papers; per-source counts chosen so the four h-indices differ.
        List<ScholardexPublicationView> pubs = List.of(
                pub(10, 8, 5, 2),
                pub(10, 8, 5, 2),
                pub(10, 8, 1, 1));
        HIndexCalculator.HIndexBreakdown b = HIndexCalculator.breakdown(pubs);
        assertThat(b.scholardex()).isEqualTo(3); // [10,10,10] -> 3 papers >=3
        assertThat(b.graphTotal()).isEqualTo(3); // [8,8,8]    -> 3
        assertThat(b.scopusVenue()).isEqualTo(2); // [5,5,1]   -> 5>=1,5>=2,1<3
        assertThat(b.wosVenue()).isEqualTo(2);    // [2,2,1]   -> 2>=1,2>=2,1<3
    }

    @Test
    void hIndexClassicDefinition() {
        // 5 papers with scopus citations [13,12,5,5,3] -> h=4 (the 5th needs >=5, has 3). Mirrors the Adrian case.
        List<ScholardexPublicationView> pubs = List.of(
                pub(0, 0, 13, 0), pub(0, 0, 12, 0), pub(0, 0, 5, 0), pub(0, 0, 5, 0), pub(0, 0, 3, 0));
        assertThat(HIndexCalculator.hIndex(pubs, ScholardexPublicationView::getScopusCitationCount)).isEqualTo(4);
    }

    @Test
    void emptyAndZeroAreZero() {
        assertThat(HIndexCalculator.breakdown(List.of()).scholardex()).isZero();
        assertThat(HIndexCalculator.breakdown(List.of(pub(0, 0, 0, 0))).scopusVenue()).isZero();
    }
}
