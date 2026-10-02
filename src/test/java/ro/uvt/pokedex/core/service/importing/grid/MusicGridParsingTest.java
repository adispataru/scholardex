package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine;
import ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.GridLine;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H142 slice 2 — reading the files colleagues already have: the Music grid and a person's CNFIS Anexa 5.1. */
class MusicGridParsingTest {

    // ── splitting a cell into items ──

    @Test
    void itemsAreSplitByBulletsDashesAndLinesAndCarryTheirDate() {
        String cell = "Anul 2026 • 06.06.2026 – Gală corală, Cenad, dirijor cor • 15.05.2026 – Recital coral\n"
                + "Anul 2025\n• Concert de colinde, fără dată exactă";
        List<GridItemSplitter.Item> items = GridItemSplitter.split(cell);
        assertEquals(3, items.size());
        assertEquals("2026-06-06", items.get(0).date());
        assertEquals("2026-05-15", items.get(1).date());
        assertEquals(2025, items.get(2).year(), "an item without a date takes the year header above it");
        assertEquals("2025-01-01", items.get(2).date());
    }

    @Test
    void aLinkAloneOnItsLineBelongsToTheItemAbove() {
        String cell = "- Concert G. Fauré, Requiem, 20.05.2022, ansamblul coral, dirijor\n(https://youtu.be/abc123)\n"
                + "- Concert de Crăciun, 18.12.2023, dirijor\nhttps://youtu.be/def456";
        List<GridItemSplitter.Item> items = GridItemSplitter.split(cell);
        assertEquals(2, items.size());
        assertEquals(List.of("https://youtu.be/abc123"), items.get(0).links());
        assertEquals(List.of("https://youtu.be/def456"), items.get(1).links());
        assertEquals("2022-05-20", items.get(0).date());
    }

    @Test
    void aDashInsideALineDoesNotSplitIt() {
        List<GridItemSplitter.Item> items = GridItemSplitter.split("27.04.2026 – Concert coral – Festivalul Magnificat, ediția I");
        assertEquals(1, items.size());
    }

    @Test
    void anItemWithoutAnyYearHasNoDate() {
        List<GridItemSplitter.Item> items = GridItemSplitter.split("• Membru al Uniunii Compozitorilor");
        assertEquals(1, items.size());
        assertNull(items.getFirst().year());
        assertNull(items.getFirst().date());
        assertTrue(GridItemSplitter.split("  ").isEmpty());
        assertTrue(GridItemSplitter.split("• - •").isEmpty());
    }

    // ── recognising the rows ──

    @ParameterizedTest(name = "{0} | {1}")
    @CsvSource(delimiter = '|', value = {
            "DID | 1.1. Tratat / studiu amplu / volum de studii teoretice sau privind compoziția muzicală, publicat* | DID_1_1",
            "DID | 1.2. Capitol într-un volum colectiv | DID_1_2",
            "DID | 1.3. Manual, curs, suport de curs, crestomație, colecție, îndrumător metodic, tipărit** | DID_1_3",
            "DID | 1.4. Traducere / editare critică / îngrijire redacțională a unei opere muzicale, crestomații | DID_1_4",
            "DID | 2.1. Înregistrări (cu cod de autentificare SAU/ȘI format digital distribuit prin streaming) | DID_2_1",
            "CS | 1.1 Prestații realizate în condiții de vizibilitate internațională sau națională de vârf* | CS_1_1",
            "CS | 2.1.1 Prestații realizate în condiții de vizibilitate internațională sau națională de vârf* | CS_1_1",
            "CS | 1.2 Prestații realizate în condiții de vizibilitate regională sau locală** | CS_1_2",
            "CS | 2.1 Studiu sau articol publicat într-o revistă de specialitate indexată în baze de date internaționale | CS_2_1",
            "CS | 2.2. Articole publicate in lexicoane și dicționare muzicale internaționale; rezumate RIPM, RILM | CS_2_2",
            "CS | 2.3. Comunicare susținută la o conferință / simpozion cu comitet de selecție | CS_2_3",
            "CS | 3.1. Membru în echipa de cercetare/ creație artistică din cadrul unui grant/proiect | CS_3_1",
            "CS | 4.1. Compoziții ample sau cicluri/albume de lucrări publicate la edituri de profil | CS_4_1",
            "RIA | 1.1. Funcții de management deținute | RIA_1_1",
            "RIA | 1.2. Director/Coordonator de grant/proiect obținut prin atragere de finanțare | RIA_1_2",
            "RIA | 1.3. Membru în colectivele de redacție / recenzor al unor publicații sau edituri indexate | RIA_1_3",
            "RIA | 1.4. Organizator al unor manifestări științifice / artistice de nivel internațional | RIA_1_4",
            "RIA | 1.5. Organizator al unor manifestări științifice / artistice de nivel național | RIA_1_5",
            "RIA | 2.1. Distincții sau premii de stat (în România sau străinătate) | RIA_2_1",
            "RIA | 2.2. Distincții sau premii acordate de organizații profesionale, media (internaționale sau naționale) ș.a. | RIA_2_2",
            "RIA | 2.3. Premii obținute la concursuri de creație sau interpretare de prestigiu, internaționale sau naționale | RIA_2_3",
            "RIA | 3.1. Membru în academii, organizații și asociații profesionale naționale sau internaționale de prestigiu | RIA_3_1",
            "RIA | 3.2. Deținător al unor funcții în academii, organizații și asociații profesionale | RIA_3_2",
            "RIA | 3.3. Participări în jurii de concursuri naționale sau internaționale | RIA_3_3",
            "RIA | 3.4. Lucrări achiziționate/comandate de UCMR sau de alte organisme/instituții de spectacole | RIA_3_4",
            "RIA | 3.5. Cursuri, masterclass-uri, conferințe susținute în alte instituții de profil | RIA_3_5",
            "RIA | 3.6 Portrete/interviuri ca invitat unic în media scrisă sau audiovizuală cu difuzare națională | RIA_3_6",
            "RIA | 3.7. Key-note speaker la manifestări științifice de nivel național / internațional | RIA_3_7",
    })
    void everyRowOfTheStandardIsRecognisedByItsWords(String table, String label, String expected) {
        assertEquals(MusicGridLayout.Row.valueOf(expected),
                MusicGridLayout.classify(MusicGridLayout.Table.valueOf(table), label).orElseThrow());
    }

    @Test
    void sheetsAreRecognisedByNameOrTitle() {
        assertEquals(MusicGridLayout.Table.DID, MusicGridLayout.tableOf("Tabelul 1 - DID").orElseThrow());
        assertEquals(MusicGridLayout.Table.CS, MusicGridLayout.tableOf("Tabelul 2 - CS").orElseThrow());
        assertEquals(MusicGridLayout.Table.RIA, MusicGridLayout.tableOf("Tabelul 3 - RIA").orElseThrow());
        assertTrue(MusicGridLayout.tableOf("Baze de Date").isEmpty());
    }

    // ── the whole grid ──

    @Test
    void aFilledGridGivesEveryRowItsItemsAndReportsWhatItCannotPlace() {
        XSSFWorkbook wb = GridWorkbooks.musicGrid("Lect.univ.dr. POPESCU ION",
                List.of(new GridLine("1. Cărți și capitole", "1.1. Tratat / studiu amplu / volum de studii", "- Volum de studii, Editura X, 2019"),
                        new GridLine(null, "1.3. Manual, curs, suport de curs", "1. Suport de curs, 2020"),
                        new GridLine("2. Documentarea realizărilor", "2.1. Înregistrări (cu cod de autentificare)",
                                "- Concert, 20.05.2022\nhttps://youtu.be/a\n- Concert, 18.12.2023\nhttps://youtu.be/b")),
                List.of(new GridLine("1. Concert / recital / spectacol", "1.1 Prestații realizate în condiții de vizibilitate internațională sau națională de vârf*",
                                "• 27.04.2026 – Concert, Festivalul Magnificat • 12.03.2025 – Concert, Sibiu"),
                        new GridLine(null, "1.2 Prestații realizate în condiții de vizibilitate regională sau locală**",
                                "Anul 2026 • 06.06.2026 – Gală corală • 15.05.2026 – Recital coral • 01.05.2026 – Concert"),
                        new GridLine(null, "1.9 Altceva pe care standardul nu îl are", "• ceva declarat, 2024")),
                List.of(new GridLine("3. Recunoaștere profesională", "3.2. Deținător al unor funcții în academii, organizații",
                        "2021 - prezent, Consilier în consiliul de administrație al unui centru de cultură")));

        MusicGridParser.ParsedGrid grid = MusicGridParser.parse(wb);

        Map<String, Integer> counts = new TreeMap<>();
        grid.rows().forEach(r -> counts.merge(r.row().label(), r.items().size(), Integer::sum));
        assertEquals(Map.of("CS 1.1", 2, "CS 1.2", 3, "DID 1.1", 1, "DID 1.3", 1, "DID 2.1", 2, "RIA 3.2", 1), counts);
        assertEquals(10, grid.itemCount());
        assertEquals(List.of("CS: 1.9 Altceva pe care standardul nu îl are"), grid.unrecognised());
        assertEquals("Lect.univ.dr. POPESCU ION", grid.heading());
        assertTrue(MusicGridParser.looksLikeGrid(wb));
    }

    @Test
    void aWorkbookWithoutTheCandidateColumnIsNotAGrid() {
        XSSFWorkbook wb = new XSSFWorkbook();
        wb.createSheet("Tabelul 1 - DID").createRow(0).createCell(0).setCellValue("Tipul activităților");
        assertFalse(MusicGridParser.looksLikeGrid(wb));
    }

    // ── CNFIS Anexa 5.1 ──

    @Test
    void anArtsSheetGivesEachPerformanceItsKindLevelAndParticipants() {
        XSSFWorkbook wb = GridWorkbooks.cnfisArts("Anexa 5.1. Fişa individuală pentru performanţa creaţiei artistice", List.of(
                new ArtsLine(2024, "Recital cameral, duo vioară-pian", "Festivalul muzicii românești, Iași", 5, 1),
                new ArtsLine(2022, "Concert orchestra FMT", "Festivalul de Muzică de Cameră", 7, 6),
                new ArtsLine(2023, "Premiu pentru acompaniament", "Concursul Național Eduard Caudella", 13, null),
                new ArtsLine(2021, "Rând fără marcaj", "Festival", 0, null),
                new ArtsLine("2021.0", "An scris ca text", "Festival", 3, null)));

        assertTrue(CnfisArtsSheetParser.looksLikeArtsSheet(wb));
        assertFalse(CnfisArtsSheetParser.isInstitutionalTable(wb));
        CnfisArtsSheetParser.ParsedArts arts = CnfisArtsSheetParser.parse(wb);
        assertEquals(4, arts.rows().size());
        assertEquals(1, arts.unmarked());
        assertEquals("GROUP", arts.rows().get(0).kind());
        assertEquals("INTERNATIONAL", arts.rows().get(0).level());
        assertEquals(1, arts.rows().get(0).participants());
        assertEquals("COLLECTIVE", arts.rows().get(1).kind());
        assertEquals("NATIONAL", arts.rows().get(1).level());
        assertEquals("PRIZE", arts.rows().get(2).kind());
        assertEquals("INDIVIDUAL", arts.rows().get(3).kind());
        assertEquals("INTERNATIONAL_TOP", arts.rows().get(3).level());
        assertEquals(2021, arts.rows().get(3).year());
    }

    @Test
    void theInstitutionalTableIsRecognisedAsSuch() {
        XSSFWorkbook wb = GridWorkbooks.cnfisArts("Anexa 6.1. Tabel instituțional - centralizare performanţa creaţiei artistice",
                List.of(new ArtsLine(2024, "Recital", "Festival", 2, 1)));
        assertTrue(CnfisArtsSheetParser.isInstitutionalTable(wb));
    }
}
