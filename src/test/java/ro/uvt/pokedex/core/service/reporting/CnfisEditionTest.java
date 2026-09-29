package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CnfisEditionTest {

    @Test
    void anArticleIsReportedByTheListOfItsYearAndTheLastYearByTheOneBefore() {
        CnfisEdition edition = CnfisEdition.EDITION_2025;

        assertEquals(2021, edition.listYearFor(2021));
        assertEquals(2022, edition.listYearFor(2022));
        assertEquals(2023, edition.listYearFor(2023));
        assertEquals(2023, edition.listYearFor(2024), "the 2024 list is not public when edition 2025 is reported");
    }

    @Test
    void consecutiveEditionsOverlapAndDisagreeOnlyOnTheYearThatLagged() {
        assertEquals(2023, CnfisEdition.EDITION_2027.listYearFor(2023));
        assertEquals(2024, CnfisEdition.EDITION_2027.listYearFor(2024));
        assertEquals(2025, CnfisEdition.EDITION_2027.listYearFor(2026));
        assertTrue(CnfisEdition.EDITION_2025.covers(2024) && CnfisEdition.EDITION_2027.covers(2024));
        assertFalse(CnfisEdition.EDITION_2027.covers(2022));
    }

    @Test
    void aWindowIsMatchedToItsEditionAndAnUnknownWindowMakesAProvisionalOne() {
        assertSame(CnfisEdition.EDITION_2025, CnfisEdition.forWindow(2021, 2024));
        assertTrue(CnfisEdition.EDITION_2027.provisional());

        CnfisEdition adHoc = CnfisEdition.forWindow(2019, 2022);
        assertTrue(adHoc.provisional());
        assertEquals(2021, adHoc.listYearFor(2022));
        assertEquals(2023, adHoc.reportingYear());
    }
}
