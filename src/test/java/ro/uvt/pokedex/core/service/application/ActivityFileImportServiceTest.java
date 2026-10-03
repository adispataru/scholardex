package ro.uvt.pokedex.core.service.application;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.ActivityRepository;
import ro.uvt.pokedex.core.service.importing.grid.MusicGridLayout;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 2 — a file a colleague already has becomes their activities, to check. */
class ActivityFileImportServiceTest {

    private static final String EMAIL = "ion.popescu@e-uvt.ro";

    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final ActivityInstanceRepository instanceRepository = mock(ActivityInstanceRepository.class);
    private final ActivityFileImportService service = new ActivityFileImportService(activityRepository, instanceRepository);
    private final Map<String, Activity> types = new HashMap<>();

    @BeforeEach
    void setUp() {
        for (MusicGridLayout.Row row : MusicGridLayout.Row.values()) {
            types.computeIfAbsent(row.activityType(), this::type);
        }
        when(activityRepository.findByName(any())).thenAnswer(inv -> types.containsKey(inv.getArgument(0))
                ? List.of(types.get(inv.getArgument(0))) : List.of());
        when(instanceRepository.findAllByResearcherIdAndImportKeyIn(eq(EMAIL), anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void resetRegistry() {
        ArtisticEventRankSupport.reset();
    }

    private Activity type(String name) {
        Activity a = new Activity();
        a.setId("type-" + types.size());
        a.setName(name);
        List<Activity.Field> fields = new ArrayList<>();
        for (String f : List.of("Titlu", "Dovezi", "Link", "Suport", "Rol", "Marime_formatie", "Rezultat", "Tip",
                "N_participanti_universitate", "Nume Proiect", "Functia", "Organizatia", "An_inceput", "An_sfarsit", "Nivel",
                "Concursul", "Denumire", "DOI", "Publicatia_sau_editura", "Manifestarea", "Lucrarea", "Publicatia_sau_postul",
                "Revista_sau_volumul")) {
            Activity.Field field = new Activity.Field();
            field.setName(f);
            field.setNumber(List.of("Marime_formatie", "N_participanti_universitate", "An_inceput", "An_sfarsit").contains(f));
            if (f.equals("Rol") && name.equals(MusicGridLayout.GRANT_TYPE)) field.setAllowedValues(List.of("Membru", "Director (proiect național)"));
            fields.add(field);
        }
        a.setFields(fields);
        if (name.equals(MusicGridLayout.EVENT_TYPE)) a.setReferenceFields(List.of(Activity.ReferenceField.EVENT_NAME));
        return a;
    }

    private static ByteArrayInputStream stream(XSSFWorkbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @SuppressWarnings("unchecked")
    private List<ActivityInstance> saved() {
        ArgumentCaptor<List<ActivityInstance>> captor = ArgumentCaptor.forClass(List.class);
        verify(instanceRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.GridLine line(String a, String b, String items) {
        return new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.GridLine(a, b, items);
    }

    private XSSFWorkbook grid() {
        return ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.musicGrid("Conf.univ.dr. POPESCU ION",
                List.of(line("1. Cărți", "1.1. Tratat / studiu amplu / volum de studii", "- Volum de studii, 2019")),
                List.of(line("1. Concert", "1.1 Prestații realizate în condiții de vizibilitate internațională sau națională de vârf*",
                                "• 27.04.2026 – Concert, Festivalul George Enescu, Ateneu, dirijor • 12.03.2025 – Recital în duo cu pianul"),
                        line(null, "1.2 Prestații realizate în condiții de vizibilitate regională sau locală**",
                                "• 06.06.2026 – Gală corală, solist și dirijor"),
                        line("3. Valorificare", "3.1. Membru în echipa de cercetare/ creație artistică din cadrul unui grant/proiect",
                                "• Proiect AFCN «Muzica bănățeană», 2023")),
                List.of(line("2. Premii", "2.3. Premii obținute la concursuri de creație sau interpretare de prestigiu",
                                "- Premiul I, Concursul Remember Enescu, 2023"),
                        line("3. Recunoaștere", "3.2. Deținător al unor funcții în academii, organizații",
                                "2021 - prezent, Consilier în consiliul de administrație"),
                        line(null, "3.3. Participări în jurii de concursuri naționale sau internaționale",
                                "- 13.12.2025 – membru al juriului Festivalului-Concurs Internațional Cu noi este Dumnezeu")));
    }

    @Test
    void aGridBecomesActivitiesOfTheTypeOfTheirRowMarkedImportedAndToCheck() throws IOException {
        ArtisticEventRankSupport.register(List.of(event("Festivalul George Enescu (România)", ArtisticEvent.Rank.INTERNATIONAL_TOP)));

        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "fisa.xlsx", stream(grid()), null);

        assertEquals(ActivityFileImportService.FileKind.MUSIC_GRID, report.kind());
        assertEquals(8, report.created());
        assertEquals(0, report.alreadyImported());
        assertEquals("Conf.univ.dr. POPESCU ION", report.heading());
        assertEquals(1, report.eventsRecognised());
        List<ActivityInstance> saved = saved();
        assertTrue(saved.stream().allMatch(i -> EMAIL.equals(i.getResearcherId()) && Boolean.TRUE.equals(i.getNeedsReview())
                && "Fișa de verificare: fisa.xlsx".equals(i.getImportSource()) && i.getImportKey() != null));

        ActivityInstance enescu = find(saved, "Festivalul George Enescu");
        assertEquals(MusicGridLayout.EVENT_TYPE, enescu.getActivity().getName());
        assertEquals(ActivityFileImportService.SUGGESTED_TOP, enescu.getEventLevelSuggestion(),
                "the grid's row is a suggestion for the experts, never a score");
        assertFalse(enescu.getFields().containsKey("Vizibilitate"));
        assertEquals("Participare", enescu.getFields().get("Rezultat"));
        assertEquals("Dirijor", enescu.getFields().get("Rol"));
        assertEquals("Festivalul George Enescu (România)", enescu.getReferenceFields().get(Activity.ReferenceField.EVENT_NAME));
        assertEquals("2026-04-27", enescu.getDate());

        ActivityInstance duo = find(saved, "Recital în duo");
        assertEquals("2", duo.getFields().get("Marime_formatie"));
        assertFalse(duo.getReferenceFields().containsKey(Activity.ReferenceField.EVENT_NAME));

        ActivityInstance gala = find(saved, "Gală corală");
        assertEquals(ActivityFileImportService.SUGGESTED_REGIONAL, gala.getEventLevelSuggestion());
        assertNull(gala.getFields().get("Rol"), "two roles named: the person chooses");

        ActivityInstance prize = find(saved, "Premiul I");
        assertEquals("Premiu", prize.getFields().get("Rezultat"));

        ActivityInstance grant = find(saved, "Proiect AFCN");
        assertEquals(MusicGridLayout.GRANT_TYPE, grant.getActivity().getName());
        assertEquals("Membru", grant.getFields().get("Rol"));

        ActivityInstance office = find(saved, "Consilier");
        assertEquals("Funcție de conducere", office.getFields().get("Rol"));
        assertEquals("2021", office.getFields().get("An_inceput"));
        assertNull(office.getFields().get("An_sfarsit"), "still held");

        ActivityInstance jury = find(saved, "juriului");
        assertEquals(null, jury.getFields().get("Nivel"), "H144: the researcher never picks the level");
        assertEquals(ActivityFileImportService.SUGGESTED_JURY_INTERNATIONAL, jury.getEventLevelSuggestion(),
                "the grid's word is a suggestion for the experts");
    }

    @Test
    void importingTheSameFileAgainAddsNothing() throws IOException {
        service.importFile(EMAIL, "fisa.xlsx", stream(grid()), null);
        List<ActivityInstance> first = saved();
        when(instanceRepository.findAllByResearcherIdAndImportKeyIn(eq(EMAIL), anyCollection())).thenReturn(first);

        ActivityFileImportService.ImportReport again = service.importFile(EMAIL, "fisa (1).xlsx", stream(grid()), null);

        assertEquals(0, again.created());
        assertEquals(8, again.alreadyImported());
    }

    @Test
    void aTypeMissingFromThePlatformIsReportedNotGuessed() throws IOException {
        types.remove(MusicGridLayout.GRANT_TYPE);
        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "fisa.xlsx", stream(grid()), null);
        assertEquals(7, report.created());
        assertEquals(List.of(MusicGridLayout.GRANT_TYPE), report.missingTypes());
    }

    @Test
    void aHeadUploadingForAColleagueIsNamedInTheSource() throws IOException {
        service.importFile(EMAIL, "fisa.xlsx", stream(grid()), "sef.departament@e-uvt.ro");
        assertTrue(saved().stream().allMatch(i -> i.getImportSource().equals("Fișa de verificare: fisa.xlsx (încărcată de sef.departament@e-uvt.ro)")));
    }

    @Test
    void anArtsSheetBecomesPerformancesWithTheirKindAndTheFacultysLevel() throws IOException {
        XSSFWorkbook wb = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.cnfisArts(
                "Anexa 5.1. Fişa individuală", List.of(
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2024, "Recital cameral", "Festivalul muzicii românești, Iași", 5, 1),
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2023, "Premiu pentru acompaniament", "Concursul Eduard Caudella", 13, null)));

        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "anexa51.xlsx", stream(wb), null);

        assertEquals(ActivityFileImportService.FileKind.CNFIS_ARTS, report.kind());
        assertEquals(2, report.created());
        List<ActivityInstance> saved = saved();
        ActivityInstance recital = find(saved, "Recital cameral");
        // H145: the sheet's kind is kept as the ensemble's size (a fact); the CNFIS kind is derived from it
        assertEquals("2", recital.getFields().get("Marime_formatie"));
        assertFalse(recital.getFields().containsKey("Tip"));
        assertTrue(recital.getEventLevelSuggestion().startsWith("Fișa CNFIS 5.1: internațional"), recital.getEventLevelSuggestion());
        assertFalse(recital.getFields().containsKey("Vizibilitate"));
        assertEquals("1", recital.getFields().get("N_participanti_universitate"));
        // H142 slice 7: the event's own name, cut from the cell, as the experts and the ranking read it
        assertEquals("Festivalul muzicii românești", recital.getReferenceFields().get(Activity.ReferenceField.EVENT_NAME));
        assertEquals(List.of(new ActivityFileImportService.ReportedLevel("Festivalul muzicii românești", "INTERNATIONAL"),
                new ActivityFileImportService.ReportedLevel("Concursul Eduard Caudella", "NATIONAL", true)), report.reportedLevels(),
                "a prize is reported: its event is a competition");
        assertEquals("2024-01-01", recital.getDate());
        ActivityInstance prize = find(saved, "Premiu pentru acompaniament");
        assertFalse(prize.getFields().containsKey("Tip"));
        assertEquals("Premiu", prize.getFields().get("Rezultat"));
        assertEquals("Fișa CNFIS 5.1: național", prize.getEventLevelSuggestion());
    }

    @Test
    void aPrizeAtAListedEventIsReportedWithoutALevelSoItOnlyMakesTheEventACompetition() throws IOException {
        ArtisticEventRankSupport.register(List.of(event("Concursul Național Eduard Caudella", ArtisticEvent.Rank.NATIONAL)));
        XSSFWorkbook wb = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.cnfisArts(
                "Anexa 5.1. Fişa individuală", List.of(
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2023, "Premiul I", "Concursul Național Eduard Caudella", 13, null),
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2023, "Recital", "Concursul Național Eduard Caudella", 1, null)));

        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "anexa51.xlsx", stream(wb), null);

        assertEquals(List.of(new ActivityFileImportService.ReportedLevel("Concursul Național Eduard Caudella", null, true)),
                report.reportedLevels(), "the registry's rank stands; the prize still marks the event a competition");
    }

    @Test
    void aCitationsSheetBecomesOneRecordPerCitationEvenOfTheSameWork() throws IOException {
        types.put(ActivityFileImportService.CITATION_TYPE, citationType());
        String longWork = "Concert pentru vioară și orchestră, prima audiție la Festivalul Internațional Timișoara Muzicală, "
                + "Sala Capitol, Timișoara, 12 mai 2019, cu Orchestra Filarmonicii Banatul și dirijorul invitat al stagiunii";
        XSSFWorkbook wb = anexa41(
                new String[]{"2019", "Suita a II-a pentru pian", "Revista Muzica, nr. 2/2022"},
                new String[]{"2019", "Suita a II-a pentru pian", "Actualitatea muzicală, nr. 5, 2023"},
                new String[]{"2019", longWork, "Observator cultural, nr. 980, 2019"},
                new String[]{"2015", "Cvartet de coarde", "Cronică în presa locală"});

        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "anexa41.xlsx", stream(wb), null);

        assertEquals(ActivityFileImportService.FileKind.CNFIS_CITATIONS, report.kind());
        assertEquals(4, report.created(), "the same work cited in two publications is two citations");
        assertEquals(1, report.withoutYear(), "a citation whose publication names no year waits for its date");
        List<ActivityInstance> saved = saved();
        ActivityInstance first = saved.stream().filter(i -> "Revista Muzica, nr. 2/2022".equals(i.getFields().get("Publicatie")))
                .findFirst().orElseThrow();
        assertEquals("Suita a II-a pentru pian", first.getName());
        assertEquals("2019", first.getFields().get("An_creatie"));
        assertEquals("2022-01-01", first.getDate(), "the year of the citation is the record's date");
        assertEquals("Anexa 4.1 CNFIS: anexa41.xlsx", first.getImportSource());
        assertEquals(Boolean.TRUE, first.getNeedsReview());
        ActivityInstance concert = find(saved, "Concert pentru vioară");
        assertEquals("Concert pentru vioară și orchestră", concert.getName(), "a long identification is cut at its first comma");
        assertTrue(concert.getFields().get("Detalii_creatie").startsWith("prima audiție la Festivalul"));

        when(instanceRepository.findAllByResearcherIdAndImportKeyIn(eq(EMAIL), anyCollection())).thenReturn(saved);
        XSSFWorkbook again = anexa41(new String[]{"2019", "Suita a II-a pentru pian", "Revista Muzica, nr. 2/2022"});
        assertEquals(0, service.importFile(EMAIL, "anexa41 (1).xlsx", stream(again), null).created());
    }

    /** The CNFIS form itself, its rows filled from the first formatted one (B: year of the work, C: work, D: citation, E: 1). */
    private static XSSFWorkbook anexa41(String[]... rows) throws IOException {
        try (var in = ActivityFileImportServiceTest.class
                .getResourceAsStream("/fixtures/templates/AC2025_Anexa4.1-Impact_creatie_artistica-2025.xlsx")) {
            XSSFWorkbook wb = new XSSFWorkbook(in);
            var sheet = wb.getSheetAt(0);
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.getRow(9 + i);
                row.getCell(1).setCellValue(Double.parseDouble(rows[i][0]));
                row.getCell(2).setCellValue(rows[i][1]);
                row.getCell(3).setCellValue(rows[i][2]);
                row.getCell(4).setCellValue(1);
            }
            return wb;
        }
    }

    private static Activity citationType() {
        Activity a = new Activity();
        a.setId("type-citation");
        a.setName(ActivityFileImportService.CITATION_TYPE);
        List<Activity.Field> fields = new ArrayList<>();
        for (String f : List.of("An_creatie", "Detalii_creatie", "Publicatie", "Numar_publicatie", "Dovezi")) {
            Activity.Field field = new Activity.Field();
            field.setName(f);
            field.setNumber(f.equals("An_creatie"));
            fields.add(field);
        }
        a.setFields(fields);
        return a;
    }

    @Test
    void theInstitutionalTableAndOtherFilesAreRefused() throws IOException {
        XSSFWorkbook institutional = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.cnfisArts(
                "Anexa 6.1. Tabel instituțional", List.of(
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2024, "Recital", "Festival", 2, 1)));
        assertEquals(ActivityFileImportService.FileKind.INSTITUTIONAL_TABLE,
                service.importFile(EMAIL, "anexa61.xlsx", stream(institutional), null).kind());
        XSSFWorkbook other = new XSSFWorkbook();
        other.createSheet("Foaie").createRow(0).createCell(0).setCellValue("nimic");
        assertEquals(ActivityFileImportService.FileKind.UNSUPPORTED, service.importFile(EMAIL, "x.xlsx", stream(other), null).kind());
        assertEquals(ActivityFileImportService.FileKind.UNSUPPORTED,
                service.importFile(EMAIL, "x.xlsx", new ByteArrayInputStream("not a workbook".getBytes()), null).kind());
        verify(instanceRepository, never()).saveAll(any());
    }

    @Test
    void aFileTheFacultySubmittedLandsCheckedUnderItsReport() throws IOException {
        XSSFWorkbook wb = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.cnfisArts(
                "Anexa 5.1. Fişa individuală", List.of(
                        new ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.ArtsLine(2024, "Recital", "Festivalul X", 2, null)));

        service.importFile(EMAIL, "PCA.xlsx", stream(wb), "admin@uvt.ro",
                new ActivityFileImportService.ImportOptions(true, "Raportare CNFIS 2025 (depusă de facultate)"));

        ActivityInstance recital = saved().getFirst();
        assertEquals(Boolean.FALSE, recital.getNeedsReview(), "the faculty already submitted it: no «to check»");
        assertEquals("Raportare CNFIS 2025 (depusă de facultate) — Anexa 5.1 CNFIS: PCA.xlsx (încărcată de admin@uvt.ro)",
                recital.getImportSource());
    }

    @Test
    void anArticlesSheetBecomesDeclaredArticlesNamedByTheirJournalsIssn() throws IOException {
        XSSFWorkbook wb = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.anexa5Filled();
        assertEquals("Almași Gabriel-Vicențiu", ActivityFileImportService.headingOf(wb));

        ActivityFileImportService.ImportReport report = service.importFile(EMAIL, "AB.xlsx", stream(wb), null);

        assertEquals(ActivityFileImportService.FileKind.CNFIS_ARTICLES, report.kind());
        assertEquals(2, report.created());
        assertEquals(List.of("Anexa 5: 1 brevet(e), neimportate"), report.unrecognisedRows());
        List<ActivityInstance> saved = saved();
        ActivityInstance article = find(saved, "Instrumente gestuale");
        assertEquals(MusicGridLayout.Row.CS_2_1.activityType(), article.getActivity().getName());
        assertEquals("2069-665X", article.getReferenceFields().get(Activity.ReferenceField.FORUM_ISSN));
        assertEquals("10.47809/ICTMF.2021.1", article.getFields().get("DOI"));
        assertEquals("Tehnologii Informatice și de Comunicație în domeniul Muzical", article.getFields().get("Revista_sau_volumul"));
        assertEquals("ISSN: 2069-665X, 2067-9408", article.getFields().get("Dovezi"));
        assertEquals("2021-01-01", article.getDate());
        ActivityInstance volume = find(saved, "Muzica bănățeană");
        assertNull(volume.getReferenceFields().get(Activity.ReferenceField.FORUM_ISSN), "a volume with an ISBN names no journal");
        assertEquals("WoS: WOS:000123; ISBN: 978-973-0-12345-6", volume.getFields().get("Dovezi"));
    }

    @Test
    void anEventWithoutAFestivalNameIsTheCellWithoutItsDate() {
        assertEquals("Festivalul Internațional Meridian",
                ActivityFileImportService.eventNameOf("Festivalul Internațional Meridian, ediția a XIX-a, 3-10 nov. 2024"));
        String christmas = "Concertul de Crăciun, Ansamblul coral al FMT – UVT, Biserica Romano-Catolică din Cenad";
        assertEquals(christmas, ActivityFileImportService.eventNameOf("17.12.2021 – " + christmas));
        assertEquals(christmas, ActivityFileImportService.eventNameOf("17.12.2022 – " + christmas + ";"),
                "the same concert reported for two years is one name");
        assertEquals("Turneu de concerte în 8 orașe din România",
                ActivityFileImportService.eventNameOf("11-23.3.2023 Turneu de concerte în 8 orașe din România"));
        assertEquals("participare cu lucrarea AlterEcho", ActivityFileImportService.eventNameOf("15 octombrie 2022, participare cu lucrarea AlterEcho"));
    }

    @Test
    void anInstitutionalTableIsNeverOnePersonsWhateverItsColumns() throws IOException {
        XSSFWorkbook wb = ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks.officialForm(
                "AC2025_Anexa6.1-Tabel_institutional_creatie_artistica-2025.xlsx");
        assertEquals(ActivityFileImportService.FileKind.INSTITUTIONAL_TABLE, ActivityFileImportService.kindOf(wb));
    }

    private static ActivityInstance find(List<ActivityInstance> saved, String fragment) {
        return saved.stream().filter(i -> i.getName().contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no imported record with «" + fragment + "»"));
    }

    private static ArtisticEvent event(String name, ArtisticEvent.Rank rank) {
        ArtisticEvent e = new ArtisticEvent();
        e.setName(name);
        e.setRank(rank);
        return e;
    }
}
