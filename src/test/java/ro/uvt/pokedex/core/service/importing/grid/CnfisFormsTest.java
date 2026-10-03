package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H142 slice 7 — a faculty's submitted CNFIS forms, read from the official templates as the teachers filled them. */
class CnfisFormsTest {

    @Test
    void theNameOfTheTeacherIsTheOneUnderTheLabel() throws IOException {
        assertEquals(Optional.of("Almași Gabriel-Vicențiu"), CnfisSheets.personName(GridWorkbooks.anexa5Filled()));
        XSSFWorkbook arts = GridWorkbooks.officialForm("AC2025_Anexa5.1-Performanta_creatie_artistica-2025.xlsx");
        GridWorkbooks.set(arts.getSheetAt(0), 4, 1, "Ardeleanu Roxana-Sorana");
        assertEquals(Optional.of("Ardeleanu Roxana-Sorana"), CnfisSheets.personName(arts), "the guidance line between is skipped");
        assertTrue(CnfisSheets.personName(GridWorkbooks.officialForm("AC2025_Anexa5.1-Performanta_creatie_artistica-2025.xlsx")).isEmpty(),
                "a blank form names nobody");
        XSSFWorkbook unnamed = GridWorkbooks.officialForm("AC2025_Anexa5-Fisa_articole_brevete-2025.xlsx");
        GridWorkbooks.set(unnamed.getSheetAt(0), 5, 1, "Conferențiar");
        assertTrue(CnfisSheets.personName(unnamed).isEmpty(), "the label and the position under a blank name are no name");
    }

    @Test
    void anArticlesSheetGivesEachArticleWithItsJournalsIssnsAndSkipsPatents() throws IOException {
        XSSFWorkbook wb = GridWorkbooks.anexa5Filled();
        assertTrue(CnfisArticlesSheetParser.looksLikeArticlesSheet(wb));

        CnfisArticlesSheetParser.ParsedArticles parsed = CnfisArticlesSheetParser.parse(wb);

        assertEquals(2, parsed.rows().size());
        assertEquals(1, parsed.patents());
        CnfisArticlesSheetParser.ArticleRow first = parsed.rows().getFirst();
        assertEquals(2021, first.year());
        assertEquals("10.47809/ICTMF.2021.1", first.doi());
        assertEquals("Tehnologii Informatice și de Comunicație în domeniul Muzical", first.journal());
        assertEquals(List.of("2069665X", "20679408"), first.issns());
        CnfisArticlesSheetParser.ArticleRow volume = parsed.rows().get(1);
        assertEquals(2023, volume.year(), "a row without its year takes the one its title names");
        assertEquals("10.1234/abc.5", volume.doi(), "the resolver's address and the final dot are dropped");
        assertEquals("WOS:000123", volume.wosCode());
        assertEquals(List.of("978-973-0-12345-6"), volume.isbns());
        assertTrue(volume.issns().isEmpty());
    }

    @Test
    void aNumberedArtsRowWithoutItsYearTakesTheYearOfItsEvent() throws IOException {
        XSSFWorkbook wb = GridWorkbooks.officialForm("AC2025_Anexa5.1-Performanta_creatie_artistica-2025.xlsx");
        Sheet s = wb.getSheetAt(0);
        GridWorkbooks.set(s, 11, 0, 1);
        GridWorkbooks.set(s, 11, 2, "Recital cameral Duo «Molto espressivo»");
        GridWorkbooks.set(s, 11, 3, "Festivalul muzicii românești, ediția a XXVI-a, Iași, 14-20 octombrie 2023");
        GridWorkbooks.set(s, 11, 8, 1);
        GridWorkbooks.set(s, 12, 0, 2);
        GridWorkbooks.set(s, 12, 2, "Concert de colinde");
        GridWorkbooks.set(s, 12, 3, "Filarmonica Banatul");
        GridWorkbooks.set(s, 12, 4, 1);

        CnfisArtsSheetParser.ParsedArts parsed = CnfisArtsSheetParser.parse(wb);

        assertEquals(2, parsed.rows().size(), "the template's guidance and column rows stay out");
        CnfisArtsSheetParser.ArtsRow duo = parsed.rows().getFirst();
        assertEquals(2023, duo.year());
        assertEquals("GROUP", duo.kind());
        assertEquals("INTERNATIONAL", duo.level());
        assertNull(parsed.rows().get(1).year(), "no year written anywhere: none guessed");
    }

    @Test
    void theLastYearATextNamesIsNeverInTheFuture() {
        assertEquals(2023, CnfisSheets.lastYear("Iași, 14-20 octombrie 2023"));
        assertEquals(2021, CnfisSheets.lastYear("ediția 2019, 2021"));
        assertNull(CnfisSheets.lastYear("ediția a XXVI-a"));
        assertNull(CnfisSheets.lastYear("Planificat pentru " + (Year.now().getValue() + 2)));
    }
}
