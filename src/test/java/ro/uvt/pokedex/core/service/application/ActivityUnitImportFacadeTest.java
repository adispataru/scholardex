package ro.uvt.pokedex.core.service.application;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H142 slice 2 — a head's upload: every file goes to the one member its heading or its name names. */
class ActivityUnitImportFacadeTest {

    private static final String DEPARTMENT = "dept-muz";
    private static final String HEAD = "director@e-uvt.ro";

    private final OrgUnitRosterService rosterService = mock(OrgUnitRosterService.class);
    private final ActivityFileImportService importService = mock(ActivityFileImportService.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final OrgDivisionRepository divisionRepository = mock(OrgDivisionRepository.class);
    private final ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository domainExpertsRepository =
            mock(ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository.class);
    private final ArtisticEventFacultyRanking facultyRanking = mock(ArtisticEventFacultyRanking.class);
    private final ActivityUnitImportFacade facade = new ActivityUnitImportFacade(rosterService, importService,
            departmentRepository, divisionRepository, domainExpertsRepository, facultyRanking);

    private static final ActivityFileImportService.ImportReport REPORT = new ActivityFileImportService.ImportReport(
            ActivityFileImportService.FileKind.MUSIC_GRID, 5, 0, 0, 0, Map.of(), List.of(), List.of(), null, 0);

    @BeforeEach
    void aDepartmentOfMusic() {
        Department department = new Department();
        department.setId(DEPARTMENT);
        department.setName("Muzică");
        when(departmentRepository.findById(DEPARTMENT)).thenReturn(Optional.of(department));
        when(rosterService.departmentRoster(DEPARTMENT)).thenReturn(List.of(
                member("ion.popescu@e-uvt.ro", "Popescu", "Ion"),
                member("maria.popescu@e-uvt.ro", "Popescu", "Maria"),
                member("ana.ionescu@e-uvt.ro", "Ionescu", "Ana Maria"),
                member("darius.iordanescu@e-uvt.ro", "Iordănescu", "Darius-Divian")));
        when(importService.importFile(anyString(), anyString(), any(), eq(HEAD))).thenReturn(REPORT);
    }

    private static OrgUnitRosterService.RosterMember member(String email, String last, String first) {
        User user = new User();
        user.setEmail(email);
        User.ResearcherProfile profile = new User.ResearcherProfile();
        profile.setLastName(last);
        profile.setFirstName(first);
        user.setResearcherProfile(profile);
        return new OrgUnitRosterService.RosterMember(user, "Muzică");
    }

    private static ActivityUnitImportFacade.UploadedFile grid(String fileName, String heading) throws IOException {
        XSSFWorkbook wb = GridWorkbooks.musicGrid(heading,
                List.of(new GridWorkbooks.GridLine("1. Publicații", "1.1. Tratat, studiu amplu, volum", "O carte, 2020")),
                List.of(), List.of());
        return new ActivityUnitImportFacade.UploadedFile(fileName, GridWorkbooks.bytes(wb));
    }

    private ActivityUnitImportFacade.FileResult only(List<ActivityUnitImportFacade.UploadedFile> files, String chosen) {
        List<ActivityUnitImportFacade.FileResult> results = facade.importFiles("departments", DEPARTMENT, files, chosen, HEAD);
        assertEquals(files.size(), results.size());
        return results.getFirst();
    }

    @Test
    void aFileGoesToTheMemberItsHeadingNames() throws IOException {
        ActivityUnitImportFacade.FileResult result = only(List.of(grid("fisa.xlsx", "Lect.univ.dr. POPESCU ION")), null);

        assertEquals("IMPORTED", result.outcome());
        assertEquals("ion.popescu@e-uvt.ro", result.memberEmail(), "Maria Popescu shares the last name, not the first");
        assertEquals("Ion Popescu", result.memberName());
        verify(importService).importFile(eq("ion.popescu@e-uvt.ro"), eq("fisa.xlsx"), any(), eq(HEAD));
    }

    @Test
    void diacriticsCaseAndCompoundFirstNamesDoNotStopTheMatch() throws IOException {
        ActivityUnitImportFacade.FileResult byHeading = only(List.of(grid("fisa.xlsx", "Lect.univ.dr. IORDANESCU DARIUS")), null);
        assertEquals("darius.iordanescu@e-uvt.ro", byHeading.memberEmail());

        ActivityUnitImportFacade.FileResult byFileName = only(List.of(grid("Standarde minimale Muzica_ IONESCU ANA.xlsx", null)), null);
        assertEquals("ana.ionescu@e-uvt.ro", byFileName.memberEmail(), "without a heading the file name names the member");
    }

    @Test
    void aFileNamingNobodyOrTwoMembersIsNotImported() throws IOException {
        List<ActivityUnitImportFacade.FileResult> results = facade.importFiles("departments", DEPARTMENT, List.of(
                grid("fisa.xlsx", "Lect.univ.dr. NECUNOSCUT VASILE"),
                grid("Popescu Ion si Popescu Maria.xlsx", null)), null, HEAD);

        assertEquals("NOT_MATCHED", results.get(0).outcome());
        assertEquals("AMBIGUOUS", results.get(1).outcome());
        assertNull(results.get(1).memberEmail());
        verify(importService, never()).importFile(anyString(), anyString(), any(), anyString());
    }

    @Test
    void theChosenMemberCountsOnlyForASingleFile() throws IOException {
        ActivityUnitImportFacade.FileResult chosen = only(List.of(grid("fisa.xlsx", null)), "ana.ionescu@e-uvt.ro");
        assertEquals("IMPORTED", chosen.outcome());
        assertEquals("ana.ionescu@e-uvt.ro", chosen.memberEmail());

        List<ActivityUnitImportFacade.FileResult> two = facade.importFiles("departments", DEPARTMENT, List.of(
                grid("fisa.xlsx", null), grid("Fisa Popescu Ion.xlsx", null)), "ana.ionescu@e-uvt.ro", HEAD);
        assertEquals("NOT_MATCHED", two.get(0).outcome(), "with several files each goes by its own name");
        assertEquals("ion.popescu@e-uvt.ro", two.get(1).memberEmail());
    }

    @Test
    void aWorkbookThatIsNoFisaAndBytesThatAreNoWorkbookAreRefused() throws IOException {
        XSSFWorkbook other = new XSSFWorkbook();
        other.createSheet("Buget").createRow(0).createCell(0).setCellValue("Popescu Ion");
        List<ActivityUnitImportFacade.FileResult> results = facade.importFiles("departments", DEPARTMENT, List.of(
                new ActivityUnitImportFacade.UploadedFile("Popescu Ion.xlsx", GridWorkbooks.bytes(other)),
                new ActivityUnitImportFacade.UploadedFile("Popescu Ion 2.xlsx", new byte[]{1, 2, 3})), null, HEAD);

        assertEquals("REFUSED", results.get(0).outcome());
        assertEquals("REFUSED", results.get(1).outcome());
        verify(importService, never()).importFile(anyString(), anyString(), any(), anyString());
    }

    @Test
    void aFacultysSubmittedFilesLandCheckedAndTheirEventsAreRankedOnce() throws IOException {
        XSSFWorkbook pca = GridWorkbooks.officialForm("AC2025_Anexa5.1-Performanta_creatie_artistica-2025.xlsx");
        GridWorkbooks.set(pca.getSheetAt(0), 4, 1, "POPESCU Ion");
        XSSFWorkbook ab = GridWorkbooks.anexa5Filled();
        GridWorkbooks.set(ab.getSheetAt(0), 3, 1, "Ionescu Ana-Maria");
        ActivityFileImportService.ImportReport withLevels = new ActivityFileImportService.ImportReport(
                ActivityFileImportService.FileKind.CNFIS_ARTS, 2, 0, 0, 0, Map.of(), List.of(), List.of(), null, 0,
                List.of(new ActivityFileImportService.ReportedLevel("Festivalul X", "INTERNATIONAL")));
        when(importService.importFile(anyString(), anyString(), any(), eq(HEAD), any(ActivityFileImportService.ImportOptions.class)))
                .thenReturn(withLevels);
        when(facultyRanking.rank(anyList(), eq("Muzică"), eq(HEAD), eq(ActivityUnitImportFacade.FACULTY_REPORT_SOURCE)))
                .thenReturn(new ArtisticEventFacultyRanking.Outcome(1, 0, 0, 0, List.of()));

        ActivityUnitImportFacade.FacultyImportResult result = facade.importFacultySubmission("departments", DEPARTMENT, List.of(
                new ActivityUnitImportFacade.UploadedFile("Popescu_I_PCA.xlsx", GridWorkbooks.bytes(pca)),
                new ActivityUnitImportFacade.UploadedFile("Ionescu_A_AB.xlsx", GridWorkbooks.bytes(ab))), HEAD, "Muzică");

        assertEquals(List.of("ion.popescu@e-uvt.ro", "ana.ionescu@e-uvt.ro"),
                result.files().stream().map(ActivityUnitImportFacade.FileResult::memberEmail).toList(),
                "each file goes to the person its sheet names, initials in the file name notwithstanding");
        org.mockito.ArgumentCaptor<ActivityFileImportService.ImportOptions> options =
                org.mockito.ArgumentCaptor.forClass(ActivityFileImportService.ImportOptions.class);
        verify(importService, org.mockito.Mockito.times(2)).importFile(anyString(), anyString(), any(), eq(HEAD), options.capture());
        assertTrue(options.getAllValues().stream().allMatch(ActivityFileImportService.ImportOptions::facultySubmitted));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ActivityFileImportService.ReportedLevel>> levels =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(facultyRanking).rank(levels.capture(), eq("Muzică"), eq(HEAD), eq(ActivityUnitImportFacade.FACULTY_REPORT_SOURCE));
        assertEquals(2, levels.getValue().size(), "the levels of every file, ranked once");
        assertEquals(1, result.ranking().ranked());
    }

    @Test
    void theDomainOfTheDepartmentIsProposedForItsEvents() {
        ro.uvt.pokedex.core.model.registry.RegistryDomainExperts music = new ro.uvt.pokedex.core.model.registry.RegistryDomainExperts();
        music.setDomain("Muzică");
        music.setDepartmentIds(List.of(DEPARTMENT));
        ro.uvt.pokedex.core.model.registry.RegistryDomainExperts theatre = new ro.uvt.pokedex.core.model.registry.RegistryDomainExperts();
        theatre.setDomain("Teatru şi artele spectacolului");
        theatre.setDepartmentIds(List.of("dept-teatru"));
        when(domainExpertsRepository.findAll()).thenReturn(List.of(theatre, music));

        ActivityUnitImportFacade.FacultyImportForm form = facade.facultyImportForm("departments", DEPARTMENT);

        assertEquals(List.of("Muzică", "Teatru şi artele spectacolului"), form.domains());
        assertEquals("Muzică", form.defaultDomain());
        assertNull(facade.facultyImportForm("divisions", "div-fmt").defaultDomain(), "a faculty spans domains: the admin chooses");
    }

    @Test
    void anUnknownUnitHasNoPage() {
        when(departmentRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), facade.page("departments", "nope"));
        assertEquals(Optional.empty(), facade.page("groups", DEPARTMENT));
        assertEquals(List.of("Ionescu", "Iordănescu", "Popescu", "Popescu"),
                facade.page("departments", DEPARTMENT).orElseThrow().members().stream()
                        .map(ActivityUnitImportFacade.Member::lastName).toList());
    }
}
