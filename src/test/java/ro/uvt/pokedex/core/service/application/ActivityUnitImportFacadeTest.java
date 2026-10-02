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
import static org.mockito.ArgumentMatchers.any;
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
    private final ActivityUnitImportFacade facade =
            new ActivityUnitImportFacade(rosterService, importService, departmentRepository, divisionRepository);

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
    void anUnknownUnitHasNoPage() {
        when(departmentRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), facade.page("departments", "nope"));
        assertEquals(Optional.empty(), facade.page("groups", DEPARTMENT));
        assertEquals(List.of("Ionescu", "Iordănescu", "Popescu", "Popescu"),
                facade.page("departments", DEPARTMENT).orElseThrow().members().stream()
                        .map(ActivityUnitImportFacade.Member::lastName).toList());
    }
}
