package ro.uvt.pokedex.core.service.application;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.registry.RegistryStatus;
import ro.uvt.pokedex.core.repository.ArtisticEventRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankRegistrar;
import ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 3 — the events an institutional Anexa 6.1 table names wait for the experts; nothing else is read. */
class ArtisticEventSeedServiceTest {

    private final ArtisticEventRepository repository = mock(ArtisticEventRepository.class);
    private final ArtisticEventRankRegistrar registrar = mock(ArtisticEventRankRegistrar.class);
    private final ArtisticEventSeedService service = new ArtisticEventSeedService(repository, registrar);

    @AfterEach
    void reset() {
        ArtisticEventRankSupport.reset();
    }

    private static Optional<String> name(String cell) {
        return ArtisticEventSeedService.eventName(cell);
    }

    @Test
    void theNameRunsFromTheEventsKindToTheFirstSeparatorDateOrEdition() {
        assertEquals(Optional.of("Festivalul Național de Interpretare a Romanței Crizantema de Aur"),
                name("21- 23.10.2021 – Festivalul Național de Interpretare a Romanței „Crizantema de Aur”, ediția a54-a, Târgoviște"));
        assertEquals(Optional.of("Festivalul Internațional Meridian"),
                name("Festivalul Internațional Meridian 2024 ediția a XIX-a 3-10 nov. 2024, Pixel Club"));
        assertEquals(Optional.of("Concursul Internațional George Enescu"),
                name("Concursul Internațional George Enescu, ediția 2024. Colaborări în calitate de pianist"));
        assertEquals(Optional.of("Festivalul muzicii românești"), name("Festivalul muzicii românești, ediția a XXVI-a, Iași"));
    }

    @Test
    void aPerformanceWithinAFestivalNamesTheFestivalInTheNominative() {
        assertEquals(Optional.of("Festivalul Classic for teens"),
                name("23.9.2023 Concert la Focșani în cadrul Festivalului Classic for teens"));
        assertEquals(Optional.of("Concursul VIENNA CLASSIC STARS"),
                name("August 2024 – Bosendorfer Salon, Vienna – Recital în Gala Laureaților Concursului VIENNA CLASSIC STARS"));
        assertEquals(Optional.of("Festivalul Orgile Cetății"),
                name("Concert la Timișoara în cadrul Festivalului „Orgile Cetății”;"));
    }

    @Test
    void aDashRightAfterTheKindDoesNotEndTheName() {
        assertEquals(Optional.of("Festivalul - Concurs Internațional Remus Georgescu"),
                name("Festivalul - Concurs Internațional Remus Georgescu ediția a XI-a - De Profundis 14-27 oct. 2024"));
    }

    @Test
    void aNameThatEndsInItsKindIsTheStretchAroundIt() {
        assertEquals(Optional.of("HOT AIR FESTIVAL"),
                name("Lucrarea a fost selectată pentru a fi prezentată în cadrul HOT AIR FESTIVAL, Conservatorul din San Francisco"));
        assertEquals(Optional.of("Goppisberg Musikfestival und Akademie"),
                name("36. Goppisberg Musikfestival und Akademie\nSpiez, iulie 2024"));
        assertEquals(Optional.of("Flight Festival din Timișoara"), name("Concert la Flight Festival din Timișoara, 10.09.2022"));
    }

    @Test
    void aSeriesOrAHostInstitutionCountsWhenNoFestivalIsNamed() {
        assertEquals(Optional.of("Stagiunea Filarmonicii Tg. Mureș"), name("Stagiunea Filarmonicii Tg. Mureș, 27 ianuarie 2022"));
        assertEquals(Optional.of("Turneul Național ELECTRIC"), name("Turneul Național ELECTRIC, Sala Capitol, Timișoara. 8.11.2021"));
        assertEquals(Optional.of("Opera Română Timișoara"), name("Decembrie: Bal la Savoy, Opera Română Timișoara. Dirijor: N. N."));
        assertEquals(Optional.of("Filarmonica a Macedoniei de Nord"), name("Solist cu orchestra Filarmonica a Macedoniei de Nord, Skopje"));
        assertEquals(Optional.of("Festivalul Timișoara Muzicală"),
                name("Festivalul Timișoara Muzicală. Filarmonica Banatul, Timișoara, 6 iunie 2022"), "a festival before its host");
    }

    @Test
    void aCellThatNamesNoEventGivesNothing() {
        assertEquals(Optional.empty(), name("Concert de Crăciun, Biserica Romano-Catolică din Cenad"));
        assertEquals(Optional.empty(), name("https://www.example.org/agenda/festivalul-muzicii-de-camera"));
        assertEquals(Optional.empty(), name("membru în juriu – Online International Competition – secțiunea pian"));
        assertEquals(Optional.empty(), name("precum și programul complet al festivalului se găsesc la adresa"));
        assertEquals(Optional.empty(), name("Spectalol opera „La Traviata”"), "an opera performed is no host");
        assertEquals(Optional.empty(), name(null));
    }

    @Test
    void theEventsTheRegistryDoesNotKnowBecomeProposalsOfTheChosenDomain() throws Exception {
        ArtisticEvent enescu = new ArtisticEvent();
        enescu.setName("Festivalul Internațional George Enescu");
        enescu.setRank(ArtisticEvent.Rank.INTERNATIONAL_TOP);
        ArtisticEvent rejected = new ArtisticEvent();
        rejected.setName("Gala UVT");
        rejected.setStatus(RegistryStatus.REJECTED);
        when(repository.findAll()).thenReturn(List.of(enescu, rejected));

        byte[] xlsx = anexa61(
                "Festivalul Internațional MERIDIAN, București, 9.11.2024",
                "Festivalul Internațional Meridian 2023, Cluj-Napoca", // the same name, written otherwise
                "Festivalul Internațional GEORGE ENESCU, București, 19.09.2023", // ranked already
                "24.05.2024 – Gala UVT, scena TNT", // rejected already
                "Concert de Crăciun, Biserica din Cenad", // names no event
                "Stagiunea Filarmonicii Tg. Mureș, 27 ianuarie 2022");

        var report = service.seedFromAnexa61(new ByteArrayInputStream(xlsx), "FMT_Anexa6.1.xlsx", "Muzică", "admin@uvt.ro");

        assertEquals(6, report.cells());
        assertEquals(2, report.created());
        assertEquals(2, report.known());
        assertEquals(1, report.skipped());
        assertEquals(List.of("Festivalul Internațional MERIDIAN", "Stagiunea Filarmonicii Tg. Mureș"), report.createdNames());
        ArgumentCaptor<ArtisticEvent> saved = ArgumentCaptor.forClass(ArtisticEvent.class);
        verify(repository, times(2)).save(saved.capture());
        ArtisticEvent first = saved.getAllValues().getFirst();
        assertEquals(RegistryStatus.PROPOSED, first.getStatus());
        assertEquals("Muzică", first.getDomainId());
        assertEquals("Anexa 6.1: FMT_Anexa6.1.xlsx", first.getSource());
        assertEquals(null, first.getRank(), "an expert ranks it");
        assertEquals("PROPOSED", first.getHistory().getFirst().getAction());
        assertEquals("admin@uvt.ro", first.getHistory().getFirst().getBy());
        verify(registrar).refresh();
    }

    @Test
    void aTableWithoutTheEventColumnProposesNothing() throws Exception {
        when(repository.findAll()).thenReturn(List.of());
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Altceva").createRow(0).createCell(0).setCellValue("Festivalul Meridian");
            wb.write(out);
            var report = service.seedFromAnexa61(new ByteArrayInputStream(out.toByteArray()), "x.xlsx", "Muzică", "admin@uvt.ro");
            assertEquals(0, report.cells());
        }
        verify(repository, never()).save(any());
        verify(registrar, never()).refresh();
    }

    /** A table laid out like the CNFIS template: titles, the header row, then one event per row in column D. */
    private static byte[] anexa61(String... events) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("A6.1-IC2.3-Performanta-creatie");
            sheet.createRow(0).createCell(0).setCellValue("Anexa 6.1 — Tabel instituțional");
            var header = sheet.createRow(6);
            header.createCell(0).setCellValue("Nr. crt.");
            header.createCell(1).setCellValue("Autor");
            header.createCell(3).setCellValue("Date de identificare ale evenimentului (denumire, loc, dată)");
            for (int i = 0; i < events.length; i++) {
                var row = sheet.createRow(7 + i);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue("N. N.");
                row.createCell(3).setCellValue(events[i]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
