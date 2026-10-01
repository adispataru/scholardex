package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetHeader;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.user.StaffRecord;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetHeaderRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetSnapshotRepository;
import ro.uvt.pokedex.core.service.application.model.CnfisSheetViewModel;
import ro.uvt.pokedex.core.service.application.model.UserReportsListViewModel;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CnfisDomainCatalog;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CnfisReportingFacadeTest {

    private static final String EMAIL = "ana@e-uvt.ro";

    @Mock private UserReportFacade userReportFacade;
    @Mock private UserIndividualReportRunService runService;
    @Mock private UserRepository userRepository;
    @Mock private ResearcherAuthorLookupService lookupService;
    @Mock private ScholardexProjectionReadService projectionReadService;
    @Mock private ActivityInstanceRepository activityInstanceRepository;
    @Mock private CnfisSheetHeaderRepository headerRepository;
    @Mock private CnfisSheetSnapshotRepository snapshotRepository;
    @Mock private CnfisDomainCatalog domainCatalog;
    @Mock private CNFISReportExportService exportService;
    @Mock private ro.uvt.pokedex.core.repository.ArtisticEventRepository artisticEventRepository;
    @Mock private ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository;
    @Mock private ro.uvt.pokedex.core.service.reporting.CiteScoreQuartiles citeScoreQuartiles;

    private CnfisReportingFacade facade;

    @BeforeEach
    void setUp() {
        facade = new CnfisReportingFacade(userReportFacade, runService, userRepository, lookupService,
                projectionReadService, activityInstanceRepository, headerRepository, snapshotRepository,
                domainCatalog, exportService, artisticEventRepository, bookFactRepository, citeScoreQuartiles);
        lenient().when(userReportFacade.buildIndividualReportsListView(EMAIL))
                .thenReturn(new UserReportsListViewModel(List.of()));
        lenient().when(headerRepository.findByUserEmailAndReportingYear(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(snapshotRepository.findByUserEmailAndReportingYearOrderByCreatedAtDesc(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        lenient().when(activityInstanceRepository.findAllByResearcherId(EMAIL)).thenReturn(List.of());
        lenient().when(domainCatalog.domains()).thenReturn(List.of());
        lenient().when(citeScoreQuartiles.availableYears()).thenReturn(List.of(2023));
    }

    // ── the sheet ──────────────────────────────────────────────────────────

    @Test
    void theSheetSplitsRowsFromLeftOutAndCountsUniversityAuthorsAtTheReferenceDate() {
        ScoringPublication reported = pub("p1", "2023-04-01", "10.1/one", List.of("a-ana", "a-ion", "a-guest", "a-gone"), 4);
        ScoringPublication noCodes = pub("p2", "2022-04-01", null, List.of("a-ana"), 1);
        CNFISReport2025 q1 = new CNFISReport2025();
        q1.setIsiQ1(true);
        q1.setClassifiedBy("AIS Q1 · MATHEMATICS-SCIE · list 2023");
        q1.setListYear(2023);
        q1.setNumarAutori(4);
        CNFISReport2025 unclassified = new CNFISReport2025();
        unclassified.setIsiQ2(true);
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("f1");
        forum.setPublicationName("Journal of Testing");
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(reported, noCodes), List.of(q1, unclassified),
                        Map.of("f1", forum), List.of("a-ana"))));
        // four co-authors: Ana (tenured), Ion (no record: counted, named), a guest without account, Gone (retired)
        staff(List.of(
                user("ana@e-uvt.ro", "Ana", "a-ana", record(StaffRecord.EmploymentType.TITULAR_FUNCTIA_DE_BAZA, null, null)),
                user("ion@e-uvt.ro", "Ion", "a-ion", null),
                user("gone@e-uvt.ro", "Gone", "a-gone", record(StaffRecord.EmploymentType.PENSIONAT, null, null))));

        CnfisSheetViewModel sheet = facade.buildSheet(EMAIL, 2025).orElseThrow();

        assertEquals(1, sheet.rows().size());
        CnfisSheetViewModel.Row row = sheet.rows().getFirst();
        assertEquals("ISI Q1", row.category());
        assertEquals("Journal of Testing", row.venue());
        assertEquals(2023, row.listYear());
        assertEquals(4, row.authorCount());
        assertEquals(2, row.universityAuthorCount(), "Ana and Ion; not the guest, not the retired one");
        assertEquals(List.of("Ion Popescu"), sheet.staffRecordMissing());
        assertEquals(1, sheet.leftOut().size());
        assertTrue(sheet.leftOut().getFirst().reason().contains("neither a DOI nor a WoS code"));
        assertEquals(1, sheet.counts().q1());
        assertEquals(1, sheet.counts().q2(), "the totals count classifications, the rows only what has a row");
    }

    @Test
    void aFixedTermContractThatEndedBeforeTheReferenceDateDoesNotCount() {
        ScoringPublication reported = pub("p1", "2022-04-01", "10.1/one", List.of("a-ana", "a-tmp"), 2);
        CNFISReport2025 q3 = new CNFISReport2025();
        q3.setIsiQ3(true);
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(reported), List.of(q3), Map.of(), List.of("a-ana"))));
        staff(List.of(
                user("ana@e-uvt.ro", "Ana", "a-ana", record(StaffRecord.EmploymentType.TITULAR_FUNCTIA_DE_BAZA, null, null)),
                user("tmp@e-uvt.ro", "Tmp", "a-tmp", record(StaffRecord.EmploymentType.PERIOADA_DETERMINATA_NORMA_INTREAGA,
                        LocalDate.of(2021, 10, 1), LocalDate.of(2024, 9, 30)))));

        CnfisSheetViewModel sheet = facade.buildSheet(EMAIL, 2025).orElseThrow();

        assertEquals(1, sheet.rows().getFirst().universityAuthorCount(), "the contract ended before 1 January 2025");
        assertTrue(sheet.staffRecordMissing().isEmpty());
    }

    @Test
    void patentsComeFromTheDeclaredActivityOfTheWindow() {
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(), List.of(), Map.of(), List.of())));
        when(userRepository.findAll()).thenReturn(List.of());
        when(activityInstanceRepository.findAllByResearcherId(EMAIL)).thenReturn(List.of(
                patent("Sistem de răcire", "2023-05-10", Map.of("Tip", "European", "N_autori", "3",
                        "N_autori_universitate", "2", "Cod brevet", "EP123", "Oficiu", "EPO")),
                patent("Prea vechi", "2019-01-01", Map.of("Tip", "National", "N_autori", "1")),
                other("Conferință", "2023-05-10")));

        CnfisSheetViewModel sheet = facade.buildSheet(EMAIL, 2025).orElseThrow();

        assertEquals(1, sheet.patents().size());
        CnfisSheetViewModel.Patent p = sheet.patents().getFirst();
        assertEquals("EP123", p.code());
        assertEquals("EPO", p.office());
        assertEquals("European", p.type());
        assertEquals(3, p.authorCount());
        assertEquals(2, p.universityAuthorCount());
        assertEquals(1, sheet.counts().patents());
    }

    // ── the head of the sheet ──────────────────────────────────────────────

    @Test
    void theHeaderKeepsTheDomainFromTheCatalogueAndOneScoreSource() {
        when(domainCatalog.byCode("2")).thenReturn(Optional.of(new CnfisDomainCatalog.CnfisDomain("2", "Informatică", "Matematică")));
        when(headerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CnfisSheetHeader saved = facade.saveHeader(EMAIL, 2025, new CnfisReportingFacade.HeaderForm(
                "2", "rep-fv", 123.0, " ", 12, 9, 10)).orElseThrow();

        assertEquals("2", saved.getDomainCode());
        assertEquals("Informatică", saved.getDomainName());
        assertEquals("rep-fv", saved.getScoreReportId());
        assertNull(saved.getScoreTyped(), "a report supplies the score; the typed value is dropped");
        assertNull(saved.getUnmetCriterion());
        assertEquals(12, saved.getHirschGoogleScholar());
        assertEquals(2025, saved.getReportingYear());
    }

    @Test
    void theScoreOfTheChosenReportIsTheTotalAtTheStaffPosition() {
        // A report whose Total criterion diverges by position (the Info D-gate shape): canonical 100,
        // 80 for a conferențiar. The researcher's position comes from the staff list, not the page.
        ro.uvt.pokedex.core.model.reporting.Indicator indicator = new ro.uvt.pokedex.core.model.reporting.Indicator();
        indicator.setId("ind-total");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion total = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        total.setName("Total");
        total.setIndicatorIndices(List.of(0));
        total.setContributesToTotal(true);
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold conf = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold();
        conf.setPosition(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV);
        conf.setValue(50.0);
        total.setThresholds(List.of(conf));
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion aside = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        aside.setName("Not in the total");
        aside.setContributesToTotal(false);
        ro.uvt.pokedex.core.model.reporting.IndividualReport report = new ro.uvt.pokedex.core.model.reporting.IndividualReport();
        report.setId("rep-fv");
        report.setIndicators(List.of(indicator));
        report.setCriteria(List.of(total, aside));
        when(userReportFacade.findIndividualReportById("rep-fv")).thenReturn(Optional.of(report));
        when(runService.getOrCreateLatestRun(EMAIL, "rep-fv")).thenReturn(Optional.of(
                new ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto("run-1", "rep-fv", List.of(),
                        Map.of("ind-total", 100.0), Map.of("ind-total", Map.of("CONF_UNIV", 80.0)),
                        Map.of(0, 100.0, 1, 7.0), null,
                        ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto.Source.PERSISTED, EMAIL)));
        CnfisSheetHeader header = new CnfisSheetHeader();
        header.setScoreReportId("rep-fv");
        when(headerRepository.findByUserEmailAndReportingYear(EMAIL, 2025)).thenReturn(Optional.of(header));
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(), List.of(), Map.of(), List.of())));
        when(userRepository.findAll()).thenReturn(List.of());

        User ana = user(EMAIL, "Ana", "a-ana", null);
        ana.getResearcherProfile().setPosition(ro.uvt.pokedex.core.model.reporting.Position.CONF_UNIV);
        when(userRepository.findById(EMAIL)).thenReturn(Optional.of(ana));
        assertEquals(80.0, facade.buildSheet(EMAIL, 2025).orElseThrow().cnatdcuScore(),
                "a conferențiar gets the position-effective total, as on the evaluation page");

        User noPosition = user(EMAIL, "Ana", "a-ana", null);
        when(userRepository.findById(EMAIL)).thenReturn(Optional.of(noPosition));
        assertEquals(100.0, facade.buildSheet(EMAIL, 2025).orElseThrow().cnatdcuScore(),
                "without a staff position the canonical sum stands");
    }

    // ── frozen copies ──────────────────────────────────────────────────────

    @Test
    void freezingKeepsTheRowsAndTheLeftOutAsTheyAre() {
        ScoringPublication reported = pub("p1", "2023-04-01", "10.1/one", List.of("a-ana"), 1);
        ScoringPublication left = pub("p2", "2023-04-01", "10.1/two", List.of("a-ana"), 1);
        CNFISReport2025 q1 = new CNFISReport2025();
        q1.setIsiQ1(true);
        CNFISReport2025 none = new CNFISReport2025();
        none.setLeftOutReason("the journal is in none of the lists of 2023");
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(reported, left), List.of(q1, none), Map.of(), List.of("a-ana"))));
        when(userRepository.findAll()).thenReturn(List.of());
        when(userRepository.findById(EMAIL)).thenReturn(Optional.of(user(EMAIL, "Ana", "a-ana", null)));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CnfisSheetSnapshot snapshot = facade.freeze(EMAIL, 2025).orElseThrow();

        assertEquals("Ana Popescu", snapshot.getDisplayName());
        assertEquals(1, snapshot.getRows().size());
        assertEquals("p1", snapshot.getRows().getFirst().getPublicationId());
        assertTrue(snapshot.getRows().getFirst().getClassification().isIsiQ1());
        assertEquals(1, snapshot.getLeftOut().size());
        assertEquals("the journal is in none of the lists of 2023", snapshot.getLeftOut().getFirst().getReason());
        assertNull(snapshot.getLockedByUnitSheetId());
    }

    @Test
    void aPersonReleasesTheirOwnCopyUnlessAnInstitutionalTableHoldsIt() {
        CnfisSheetSnapshot own = new CnfisSheetSnapshot();
        own.setId("s1");
        own.setUserEmail(EMAIL);
        CnfisSheetSnapshot locked = new CnfisSheetSnapshot();
        locked.setId("s2");
        locked.setUserEmail(EMAIL);
        locked.setLockedByUnitSheetId("unit-1");
        CnfisSheetSnapshot someoneElses = new CnfisSheetSnapshot();
        someoneElses.setId("s3");
        someoneElses.setUserEmail("other@e-uvt.ro");
        when(snapshotRepository.findById("s1")).thenReturn(Optional.of(own));
        when(snapshotRepository.findById("s2")).thenReturn(Optional.of(locked));
        when(snapshotRepository.findById("s3")).thenReturn(Optional.of(someoneElses));

        assertEquals(CnfisReportingFacade.ReleaseResult.RELEASED, facade.release(EMAIL, "s1"));
        assertEquals(CnfisReportingFacade.ReleaseResult.LOCKED, facade.release(EMAIL, "s2"));
        assertEquals(CnfisReportingFacade.ReleaseResult.NOT_FOUND, facade.release(EMAIL, "s3"));
        verify(snapshotRepository).deleteById("s1");
        verify(snapshotRepository, never()).deleteById("s2");
        verify(snapshotRepository, never()).deleteById("s3");
    }

    @Test
    void aFrozenCopyIsExportedFromWhatItHoldsWithItsPatents() throws Exception {
        CnfisSheetSnapshot snapshot = new CnfisSheetSnapshot();
        snapshot.setUserEmail(EMAIL);
        CnfisSheetSnapshot.Row row = new CnfisSheetSnapshot.Row();
        row.setPublicationId("p1");
        row.setTitle("Frozen title");
        row.setDoi("10.1/one");
        row.setYear("2023");
        row.setForumId("f1");
        row.setAuthorCount(2);
        CNFISReport2025 q2 = new CNFISReport2025();
        q2.setIsiQ2(true);
        row.setClassification(q2);
        snapshot.getRows().add(row);
        CnfisSheetSnapshot.Patent patent = new CnfisSheetSnapshot.Patent();
        patent.setTitle("Brevet");
        patent.setType("National");
        patent.setYear("2022");
        snapshot.getPatents().add(patent);
        when(projectionReadService.findForumsByIdIn(anyCollection())).thenReturn(List.of());
        when(exportService.generateAnexa5(any(), any(), any(), any())).thenReturn(new byte[]{1});

        facade.exportSnapshot(snapshot);

        org.mockito.ArgumentCaptor<List<? extends ScoringPublicationReadModel>> pubs = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<List<CNFISReport2025>> patents = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exportService).generateAnexa5(pubs.capture(), any(), any(), patents.capture());
        assertEquals("Frozen title", pubs.getValue().getFirst().getTitle());
        assertEquals("2023-01-01", pubs.getValue().getFirst().getCoverDate());
        assertTrue(patents.getValue().getFirst().isNationale());
        assertEquals(2022, patents.getValue().getFirst().getListYear());
        verify(userReportFacade, never()).buildCnfisSheet(any(), any());
    }

    // ── Anexa 5.1 ──────────────────────────────────────────────────────────

    @Test
    void artisticPerformancesTakeTheirLevelFromTheRegistryAndTheirKindFromTheDeclaration() {
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(), List.of(), Map.of(), List.of())));
        when(userRepository.findAll()).thenReturn(List.of());
        ro.uvt.pokedex.core.model.ArtisticEvent venice = new ro.uvt.pokedex.core.model.ArtisticEvent();
        venice.setName("Bienala de la Veneția");
        venice.setRank(ro.uvt.pokedex.core.model.ArtisticEvent.Rank.INTERNATIONAL_TOP);
        when(artisticEventRepository.findAllByNameIgnoreCase("Bienala de la Veneția")).thenReturn(List.of(venice));
        when(artisticEventRepository.findAllByNameIgnoreCase("Un festival oarecare")).thenReturn(List.of());
        when(activityInstanceRepository.findAllByResearcherId(EMAIL)).thenReturn(List.of(
                performance("Expoziție", "2023-05-10", "Bienala de la Veneția", Map.of("Tip", "Proiect de grup (2-4)", "N_participanti_universitate", "3")),
                performance("Fără tip", "2023-05-10", "Bienala de la Veneția", Map.of()),
                performance("Festival necunoscut", "2022-05-10", "Un festival oarecare", Map.of("Tip", "Proiect individual")),
                performance("Prea veche", "2019-05-10", "Bienala de la Veneția", Map.of("Tip", "Proiect individual"))));
        // a person of an artistic domain
        CnfisSheetHeader header = new CnfisSheetHeader();
        header.setDomainCode("72");
        when(headerRepository.findByUserEmailAndReportingYear(EMAIL, 2025)).thenReturn(Optional.of(header));

        CnfisSheetViewModel sheet = facade.buildSheet(EMAIL, 2025).orElseThrow();

        assertTrue(sheet.arts().applies());
        assertEquals(1, sheet.arts().rows().size());
        CnfisSheetViewModel.ArtsRow row = sheet.arts().rows().getFirst();
        assertEquals("INTERNATIONAL_TOP", row.level());
        assertEquals("GROUP", row.kind());
        assertEquals(3, row.universityParticipants());
        assertEquals(2, sheet.arts().leftOut().size());
        assertTrue(sheet.arts().leftOut().get(0).reason().contains("kind of the work"));
        assertTrue(sheet.arts().leftOut().get(1).reason().contains("not in the registry"));
    }

    @Test
    void theArtsSheetDoesNotApplyToSomebodyOutsideTheArtsWhoDeclaredNothing() {
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(), List.of(), Map.of(), List.of())));
        when(userRepository.findAll()).thenReturn(List.of());
        CnfisSheetHeader header = new CnfisSheetHeader();
        header.setDomainCode("2");
        when(headerRepository.findByUserEmailAndReportingYear(EMAIL, 2025)).thenReturn(Optional.of(header));

        assertFalse(facade.buildSheet(EMAIL, 2025).orElseThrow().arts().applies());
    }

    private static ActivityInstance performance(String name, String date, String event, Map<String, String> fields) {
        ActivityInstance instance = other(name, date);
        instance.getActivity().setName("Participare eveniment artistic");
        instance.setFields(new java.util.HashMap<>(fields));
        instance.setReferenceFields(new java.util.HashMap<>(Map.of(Activity.ReferenceField.EVENT_NAME, event)));
        return instance;
    }

    // ── Anexa 5.3 ──────────────────────────────────────────────────────────

    @Test
    void theHumanitiesSheetTakesScopusArticlesBooksChaptersAndDeclaredVolumes() {
        ScoringPublication article = new ScoringPublication("p-art", null, "f-scopus", "2024-02-01", "ar", "ar", List.of("a-ana"), 2, "10.1/art", null, "An article", 0, Set.of());
        ScoringPublication nonScopus = new ScoringPublication("p-non", null, "f-plain", "2023-02-01", "ar", "ar", List.of("a-ana"), 1, "10.1/non", null, "Elsewhere", 0, Set.of());
        ScoringPublication chapter = new ScoringPublication("p-ch", null, "f-book", "2022-02-01", "ch", "ch", List.of("a-ana"), 3, "10.1/ch", null, "A chapter", 0, Set.of());
        CNFISReport2025 r1 = new CNFISReport2025(); r1.setNumarAutori(2);
        CNFISReport2025 r2 = new CNFISReport2025(); r2.setNumarAutori(1);
        CNFISReport2025 r3 = new CNFISReport2025(); r3.setNumarAutori(3);
        ScholardexForumView scopus = new ScholardexForumView();
        scopus.setId("f-scopus"); scopus.setPublicationName("Studia"); scopus.setScopusId("21100"); scopus.setEIssn("1234-5678");
        ScholardexForumView plain = new ScholardexForumView();
        plain.setId("f-plain"); plain.setPublicationName("Revista locală");
        ScholardexForumView bookVenue = new ScholardexForumView();
        bookVenue.setId("f-book"); bookVenue.setPublicationName("Un volum colectiv"); bookVenue.setPublisher("Polirom"); bookVenue.setIsbn("978-1");
        when(userReportFacade.buildCnfisSheet(EMAIL, CnfisEdition.EDITION_2025)).thenReturn(Optional.of(
                new UserReportFacade.CnfisSheetData(List.of(article, nonScopus, chapter), List.of(r1, r2, r3),
                        Map.of("f-scopus", scopus, "f-plain", plain, "f-book", bookVenue), List.of("a-ana"))));
        staff(List.of(user("ana@e-uvt.ro", "Ana", "a-ana", record(StaffRecord.EmploymentType.TITULAR_FUNCTIA_DE_BAZA, null, null))));
        // the 2024 article wants the 2023 list; only 2023 is loaded
        when(citeScoreQuartiles.placement("21100", 2023)).thenReturn(Optional.of(new ro.uvt.pokedex.core.service.reporting.CiteScoreQuartiles.Placement(2, 2023)));
        when(activityInstanceRepository.findAllByResearcherId(EMAIL)).thenReturn(List.of(
                declared("Carte coordonată (Comisia 28, I17)", "Volumul nostru", "2023-06-01", Map.of("Titlu", "Volumul nostru", "N_coordonatori", "2", "Editura", "Humanitas")),
                declared("Traducere a unei lucrări fundamentale din științele sociale (Comisia 25, I.8)", "Tr", "2019-06-01", Map.of("Titlu", "Prea veche"))));
        CnfisSheetHeader header = new CnfisSheetHeader();
        header.setDomainCode("63");
        when(headerRepository.findByUserEmailAndReportingYear(EMAIL, 2025)).thenReturn(Optional.of(header));

        CnfisSheetViewModel sheet = facade.buildSheet(EMAIL, 2025).orElseThrow();

        assertTrue(sheet.humanities().applies());
        List<CnfisSheetViewModel.HumanitiesRow> rows = sheet.humanities().rows();
        assertEquals(List.of("CHAPTER", "EDITED_VOLUME", "SCOPUS_Q2"), rows.stream().map(CnfisSheetViewModel.HumanitiesRow::category).toList());
        CnfisSheetViewModel.HumanitiesRow ch = rows.get(0);
        assertEquals("Un volum colectiv", ch.containerTitle());
        assertEquals("A chapter", ch.itemTitle());
        assertEquals("Polirom", ch.publisher());
        assertEquals("978-1", ch.isbn());
        assertEquals(1, ch.universityAuthorCount());
        CnfisSheetViewModel.HumanitiesRow volume = rows.get(1);
        assertEquals("Humanitas", volume.publisher());
        assertEquals(2, volume.authorCount());
        CnfisSheetViewModel.HumanitiesRow art = rows.get(2);
        assertEquals("1234-5678", art.issnOnline());
        assertEquals(2023, art.listYear());
        assertTrue(art.classifiedBy().startsWith("CiteScore Q2 · list 2023"));
        assertEquals(1, sheet.humanities().leftOut().size());
        assertTrue(sheet.humanities().leftOut().getFirst().reason().contains("not one"));
    }

    private static ActivityInstance declared(String type, String name, String date, Map<String, String> fields) {
        ActivityInstance instance = other(name, date);
        instance.getActivity().setName(type);
        instance.setFields(new java.util.HashMap<>(fields));
        return instance;
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static ScoringPublication pub(String id, String coverDate, String doi, List<String> authorIds, int authorCount) {
        return new ScoringPublication(id, null, "f1", coverDate, "ar", "ar", authorIds, authorCount, doi, null,
                "Title " + id, 0, Set.of());
    }

    private static User user(String email, String firstName, String authorId, StaffRecord record) {
        User user = new User();
        user.setEmail(email);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setFirstName(firstName);
        profile.setLastName("Popescu");
        profile.setPrimaryScholardexAuthorId(authorId);
        user.setResearcherProfile(profile);
        user.setStaffRecord(record);
        return user;
    }

    private static StaffRecord record(StaffRecord.EmploymentType type, LocalDate from, LocalDate to) {
        StaffRecord record = new StaffRecord();
        record.setEmploymentType(type);
        record.setEmployedFrom(from);
        record.setEmployedTo(to);
        return record;
    }

    /** Each user's lookup keys resolve to the author view of their primary author id. */
    private void staff(List<User> users) {
        when(userRepository.findAll()).thenReturn(users);
        for (User u : users) {
            String authorId = u.getResearcherProfile().getPrimaryScholardexAuthorId();
            when(lookupService.resolveAuthorLookupKeys(u.getResearcherProfile())).thenReturn(List.of(authorId));
            ScholardexAuthorView view = new ScholardexAuthorView();
            view.setId(authorId);
            when(projectionReadService.findAuthorsByIdIn(List.of(authorId))).thenReturn(List.of(view));
        }
    }

    private static ActivityInstance patent(String name, String date, Map<String, String> fields) {
        ActivityInstance instance = other(name, date);
        instance.getActivity().setName("Brevet");
        instance.setFields(new java.util.HashMap<>(fields));
        return instance;
    }

    private static ActivityInstance other(String name, String date) {
        ActivityInstance instance = new ActivityInstance();
        instance.setId("act-" + name.hashCode());
        instance.setName(name);
        instance.setDate(date);
        Activity activity = new Activity();
        activity.setName("Conferință");
        instance.setActivity(activity);
        return instance;
    }
}
