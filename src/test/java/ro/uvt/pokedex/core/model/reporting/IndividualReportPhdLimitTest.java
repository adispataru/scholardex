package ro.uvt.pokedex.core.model.reporting;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** H137 — the UEFISCDI PhD-age limit, by whole years at the submission deadline. */
class IndividualReportPhdLimitTest {

    private static IndividualReport report(Integer limit, LocalDate deadline) {
        IndividualReport r = new IndividualReport();
        r.setPhdLimitYears(limit);
        r.setCompetitionDeadline(deadline);
        return r;
    }

    @Test
    void firstPhdWithinTheLimitAtTheDeadline() {
        IndividualReport te = report(12, LocalDate.of(2026, 7, 30));
        assertEquals(true, te.withinPhdLimit(2014), "2014 + 12 = 2026: the limit year counts");
        assertEquals(true, te.withinPhdLimit(2020));
        assertEquals(false, te.withinPhdLimit(2013));
        IndividualReport pd = report(8, LocalDate.of(2026, 7, 30));
        assertEquals(true, pd.withinPhdLimit(2018));
        assertEquals(false, pd.withinPhdLimit(2017));
    }

    @Test
    void noVerdictWithoutALimitADeadlineOrAYear() {
        assertNull(report(null, LocalDate.of(2026, 7, 30)).withinPhdLimit(2020), "the mentor has no limit");
        assertNull(report(12, null).withinPhdLimit(2020));
        assertNull(report(12, LocalDate.of(2026, 7, 30)).withinPhdLimit(null), "no PhD year in the profile");
    }
}
