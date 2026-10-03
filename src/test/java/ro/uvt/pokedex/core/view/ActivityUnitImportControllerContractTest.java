package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.ActivityFileImportService;
import ro.uvt.pokedex.core.service.application.ActivityUnitImportFacade;
import ro.uvt.pokedex.core.service.application.ArtisticEventFacultyRanking;
import ro.uvt.pokedex.core.service.security.OrgUnitAccessService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H142 slice 2 — a head imports the fișe of the unit's members at once; the access rule of the unit's CNFIS page
 * applies. Runs the real security chain and access rule.
 */
@WebMvcTest(ActivityUnitImportController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, OrgUnitAccessService.class, GlobalControllerAdvice.class})
class ActivityUnitImportControllerContractTest {

    private static final String FACULTY = "div-fmt";
    private static final String DEPARTMENT = "dept-muz";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private DepartmentRepository departmentRepository;
    @MockitoBean
    private OrgDivisionRepository orgDivisionRepository;
    @MockitoBean
    private ActivityUnitImportFacade facade;

    @BeforeEach
    void aFacultyWithADepartment() {
        OrgDivision faculty = new OrgDivision();
        faculty.setId(FACULTY);
        faculty.setName("FMT");
        faculty.setHeadUserIds(new ArrayList<>(List.of("dean@uvt.ro")));
        Department department = new Department();
        department.setId(DEPARTMENT);
        department.setName("Muzică");
        department.setDivisionId(FACULTY);
        department.setHeadUserIds(new ArrayList<>(List.of("director@uvt.ro")));
        when(orgDivisionRepository.findById(FACULTY)).thenReturn(Optional.of(faculty));
        when(departmentRepository.findById(DEPARTMENT)).thenReturn(Optional.of(department));
        when(facade.page("departments", DEPARTMENT)).thenReturn(Optional.of(new ActivityUnitImportFacade.UnitPage(
                "departments", DEPARTMENT, "Muzică", List.of(
                        new ActivityUnitImportFacade.Member("ion.popescu@e-uvt.ro", "Popescu", "Ion"),
                        new ActivityUnitImportFacade.Member("ana.ionescu@e-uvt.ro", "Ionescu", "Ana Maria")))));
        when(facade.page("divisions", FACULTY)).thenReturn(Optional.of(new ActivityUnitImportFacade.UnitPage(
                "divisions", FACULTY, "FMT", List.of())));
    }

    @Test
    void theDirectorSeesTheUploadFormAndTheMembers() throws Exception {
        String html = mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("action=\"/supervisor/departments/" + DEPARTMENT + "/activity-import\""));
        assertTrue(html.contains("enctype=\"multipart/form-data\""));
        assertTrue(html.contains("Ion Popescu") && html.contains("Ana Maria Ionescu"));
        assertTrue(html.contains("value=\"ion.popescu@e-uvt.ro\""), "a member can be chosen for a single file");
        assertTrue(!html.contains("id=\"activity-import-results\""), "no result before an upload");
    }

    @Test
    void theDeanOpensTheFacultyTheDirectorDoesNot() throws Exception {
        mockMvc.perform(get("/supervisor/divisions/" + FACULTY + "/activity-import")
                        .with(user("dean@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .with(user("dean@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supervisor/divisions/" + FACULTY + "/activity-import")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void aSupervisorOfAnotherUnitCannotImport() throws Exception {
        mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .with(user("other@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(multipart("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .file(new MockMultipartFile("files", "Fisa Ion Popescu.xlsx", XLSX, new byte[]{1, 2, 3}))
                        .with(csrf())
                        .with(user("other@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/custom-error?error=403"));
        verify(facade, never()).importFiles(anyString(), anyString(), anyList(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theUploadImportsTheSpreadsheetsAndNamesTheFilesItRefused() throws Exception {
        ActivityFileImportService.ImportReport report = new ActivityFileImportService.ImportReport(
                ActivityFileImportService.FileKind.MUSIC_GRID, 12, 3, 2, 4, Map.of("CS 1.1", 12),
                List.of("RIA: 9.9 Altceva"), List.of(), "Lect.univ.dr. POPESCU ION", 0);
        when(facade.importFiles(eq("departments"), eq(DEPARTMENT), anyList(), isNull(), eq("director@uvt.ro")))
                .thenReturn(List.of(
                        new ActivityUnitImportFacade.FileResult("Fisa Ion Popescu.xlsx", "ion.popescu@e-uvt.ro", "Ion Popescu", "IMPORTED", report),
                        new ActivityUnitImportFacade.FileResult("fisa.xlsx", null, null, "NOT_MATCHED", null)));

        String html = mockMvc.perform(multipart("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .file(new MockMultipartFile("files", "Fisa Ion Popescu.xlsx", XLSX, new byte[]{1, 2, 3}))
                        .file(new MockMultipartFile("files", "fisa.xlsx", XLSX, new byte[]{4}))
                        .file(new MockMultipartFile("files", "scan.pdf", "application/pdf", new byte[]{5}))
                        .with(csrf())
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<List<ActivityUnitImportFacade.UploadedFile>> uploaded = ArgumentCaptor.forClass(List.class);
        verify(facade).importFiles(eq("departments"), eq(DEPARTMENT), uploaded.capture(), isNull(), eq("director@uvt.ro"));
        assertEquals(List.of("Fisa Ion Popescu.xlsx", "fisa.xlsx"),
                uploaded.getValue().stream().map(ActivityUnitImportFacade.UploadedFile::name).toList());
        assertTrue(html.contains("id=\"activity-import-results\""));
        assertTrue(html.contains("scan.pdf"), "the refused file is named");
        assertTrue(html.contains("RIA: 9.9 Altceva"), "the rows nobody recognised are listed");
        assertTrue(html.contains("Ion Popescu"));
    }

    @Test
    void aMemberChosenForASingleFileIsPassedOn() throws Exception {
        when(facade.importFiles(eq("departments"), eq(DEPARTMENT), anyList(), eq("ana.ionescu@e-uvt.ro"), eq("director@uvt.ro")))
                .thenReturn(List.of());
        mockMvc.perform(multipart("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .file(new MockMultipartFile("files", "fisa.xlsx", XLSX, new byte[]{1}))
                        .param("member", "ana.ionescu@e-uvt.ro")
                        .with(csrf())
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        verify(facade).importFiles(eq("departments"), eq(DEPARTMENT), anyList(), eq("ana.ionescu@e-uvt.ro"), eq("director@uvt.ro"));
    }

    @Test
    void onlyAPlatformAdminSeesTheFacultyReportOption() throws Exception {
        when(facade.facultyImportForm("departments", DEPARTMENT)).thenReturn(new ActivityUnitImportFacade.FacultyImportForm(
                List.of("Muzică", "Teatru şi artele spectacolului"), "Muzică"));

        String admin = mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .with(user("admin@uvt.ro").authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String director = mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(admin.contains("id=\"activity-import-faculty\"") && admin.contains("name=\"facultySubmitted\""));
        assertTrue(admin.matches("(?s).*<option value=\"Muzică\"[^>]*selected[^>]*>Muzică</option>.*"),
                "the department's domain is proposed");
        assertTrue(!director.contains("activity-import-faculty"), "a head imports the usual way only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFacultyReportOptionImportsCheckedAndRanksTheEventsForAnAdminOnly() throws Exception {
        when(facade.facultyImportForm(anyString(), anyString())).thenReturn(
                new ActivityUnitImportFacade.FacultyImportForm(List.of("Muzică"), "Muzică"));
        ActivityFileImportService.ImportReport report = new ActivityFileImportService.ImportReport(
                ActivityFileImportService.FileKind.CNFIS_ARTS, 4, 0, 0, 0, Map.of("CNFIS 5.1", 4), List.of(), List.of(),
                "POPESCU Ion", 0);
        when(facade.importFacultySubmission(eq("departments"), eq(DEPARTMENT), anyList(), eq("admin@uvt.ro"), eq("Muzică")))
                .thenReturn(new ActivityUnitImportFacade.FacultyImportResult(
                        List.of(new ActivityUnitImportFacade.FileResult("Popescu_I_PCA.xlsx", "ion.popescu@e-uvt.ro", "Ion Popescu",
                                "IMPORTED", report)),
                        new ArtisticEventFacultyRanking.Outcome(3, 1, 2, 0,
                                List.of("Festivalul Disputat (Raportare CNFIS 2025: 1 × internațional, 1 × național)"))));

        String html = mockMvc.perform(multipart("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .file(new MockMultipartFile("files", "Popescu_I_PCA.xlsx", XLSX, new byte[]{1, 2, 3}))
                        .param("facultySubmitted", "true")
                        .param("domain", "Muzică")
                        .with(csrf())
                        .with(user("admin@uvt.ro").authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(facade).importFacultySubmission(eq("departments"), eq(DEPARTMENT), anyList(), eq("admin@uvt.ro"), eq("Muzică"));
        verify(facade, never()).importFiles(anyString(), anyString(), anyList(), any(), anyString());
        assertTrue(html.contains("id=\"activity-import-ranking\"") && html.contains("Festivalul Disputat"));
        assertTrue(html.contains("Ion Popescu"));

        when(facade.importFiles(eq("departments"), eq(DEPARTMENT), anyList(), isNull(), eq("director@uvt.ro"))).thenReturn(List.of());
        mockMvc.perform(multipart("/supervisor/departments/" + DEPARTMENT + "/activity-import")
                        .file(new MockMultipartFile("files", "Popescu_I_PCA.xlsx", XLSX, new byte[]{1, 2, 3}))
                        .param("facultySubmitted", "true")
                        .param("domain", "Muzică")
                        .with(csrf())
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        verify(facade).importFiles(eq("departments"), eq(DEPARTMENT), anyList(), isNull(), eq("director@uvt.ro"));
        verify(facade, org.mockito.Mockito.times(1)).importFacultySubmission(anyString(), anyString(), anyList(), anyString(), any());
    }
}
