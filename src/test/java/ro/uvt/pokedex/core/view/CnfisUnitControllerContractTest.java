package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisUnitSheet;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.CnfisReportingFacade;
import ro.uvt.pokedex.core.service.application.CnfisUnitFacade;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;
import ro.uvt.pokedex.core.service.application.model.CnfisUnitViewModel;
import ro.uvt.pokedex.core.service.security.OrgUnitAccessService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H129 slice 3 — the head's CNFIS page of a unit: the department's heads (and the faculty's) open the
 * department's; only the faculty's heads open the faculty's. Runs the real security chain and access rule.
 */
@WebMvcTest(CnfisUnitController.class)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, OrgUnitAccessService.class, GlobalControllerAdvice.class})
class CnfisUnitControllerContractTest {

    private static final String FACULTY = "div-fpse";
    private static final String DEPARTMENT = "dept-psy";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private DepartmentRepository departmentRepository;
    @MockitoBean
    private OrgDivisionRepository orgDivisionRepository;
    @MockitoBean
    private CnfisUnitFacade cnfisUnitFacade;
    @MockitoBean
    private CnfisReportingFacade cnfisReportingFacade;

    @BeforeEach
    void aFacultyWithADepartment() {
        OrgDivision faculty = new OrgDivision();
        faculty.setId(FACULTY);
        faculty.setName("FPSE");
        faculty.setHeadUserIds(new ArrayList<>(List.of("dean@uvt.ro")));
        Department department = new Department();
        department.setId(DEPARTMENT);
        department.setName("Psihologie");
        department.setDivisionId(FACULTY);
        department.setHeadUserIds(new ArrayList<>(List.of("director@uvt.ro")));
        when(orgDivisionRepository.findById(FACULTY)).thenReturn(Optional.of(faculty));
        when(departmentRepository.findById(DEPARTMENT)).thenReturn(Optional.of(department));

        CnfisEditionViewModel edition = new CnfisEditionViewModel(2025, 2021, 2024, 2023, false);
        when(cnfisReportingFacade.editions()).thenReturn(List.of(edition));
        when(cnfisReportingFacade.edition(null)).thenReturn(Optional.of(edition));
        when(cnfisReportingFacade.edition(2025)).thenReturn(Optional.of(edition));
        CnfisUnitViewModel unit = new CnfisUnitViewModel("DEPARTMENT", DEPARTMENT, "Psihologie", edition,
                List.of(new CnfisUnitViewModel.Member("ana@uvt.ro", "Ana Pop", "Psihologie", "Psihologie",
                                "s1", "2026-09-30T10:00:00Z", false, true, 4, 0),
                        new CnfisUnitViewModel.Member("ion@uvt.ro", "Ion Popescu", "Psihologie", null,
                                null, null, false, false, 0, 0)),
                List.of(new CnfisUnitViewModel.Table("t1", "2026-09-30T11:00:00Z", "director@uvt.ro", 1, 0,
                        List.of("Ion Popescu"), 4, 0)));
        when(cnfisUnitFacade.buildUnit(CnfisUnitSheet.UnitKind.DEPARTMENT, DEPARTMENT, 2025)).thenReturn(Optional.of(unit));
        when(cnfisUnitFacade.buildUnit(CnfisUnitSheet.UnitKind.DIVISION, FACULTY, 2025)).thenReturn(Optional.of(
                new CnfisUnitViewModel("DIVISION", FACULTY, "FPSE", edition, List.of(), List.of())));
    }

    @Test
    void theDirectorSeesTheDepartmentWithWhoHasASheetAndWhoHasNot() throws Exception {
        String html = mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/cnfis")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Ana Pop") && html.contains("Ion Popescu"));
        assertTrue(html.contains("name=\"member\" value=\"ion@uvt.ro\""), "a provisional sheet can be generated for Ion");
        assertTrue(!html.contains("name=\"member\" value=\"ana@uvt.ro\""), "not for Ana, who froze her own");
        assertTrue(html.contains("/supervisor/departments/" + DEPARTMENT + "/cnfis/2025/build"));
        assertTrue(html.contains("/supervisor/departments/" + DEPARTMENT + "/cnfis/tables/t1/export"));
        assertTrue(html.contains("/supervisor/departments/" + DEPARTMENT + "/cnfis/tables/t1/delete"));
    }

    @Test
    void theDeanOpensTheDepartmentAndTheFacultyTheDirectorOnlyTheDepartment() throws Exception {
        mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/cnfis")
                        .with(user("dean@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supervisor/divisions/" + FACULTY + "/cnfis")
                        .with(user("dean@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supervisor/divisions/" + FACULTY + "/cnfis")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void aSupervisorOfAnotherUnitIsRefusedEveryAction() throws Exception {
        mockMvc.perform(get("/supervisor/departments/" + DEPARTMENT + "/cnfis")
                        .with(user("other@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/custom-error?error=403"));
        mockMvc.perform(post("/supervisor/departments/" + DEPARTMENT + "/cnfis/2025/build").with(csrf())
                        .with(user("other@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/custom-error?error=403"));
        verify(cnfisUnitFacade, never()).buildTable(eq(CnfisUnitSheet.UnitKind.DEPARTMENT), eq(DEPARTMENT), eq(2025), eq("other@uvt.ro"));
    }

    @Test
    void theHeadGeneratesProvisionalSheetsAndBuildsTheTable() throws Exception {
        when(cnfisUnitFacade.generateProvisional(CnfisUnitSheet.UnitKind.DEPARTMENT, DEPARTMENT, 2025, "ion@uvt.ro", "director@uvt.ro"))
                .thenReturn(1);
        when(cnfisUnitFacade.buildTable(CnfisUnitSheet.UnitKind.DEPARTMENT, DEPARTMENT, 2025, "director@uvt.ro"))
                .thenReturn(Optional.of(new CnfisUnitSheet()));

        mockMvc.perform(post("/supervisor/departments/" + DEPARTMENT + "/cnfis/2025/provisional").with(csrf())
                        .param("member", "ion@uvt.ro")
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/supervisor/departments/" + DEPARTMENT + "/cnfis?edition=2025"));
        mockMvc.perform(post("/supervisor/departments/" + DEPARTMENT + "/cnfis/2025/build").with(csrf())
                        .with(user("director@uvt.ro").authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(redirectedUrl("/supervisor/departments/" + DEPARTMENT + "/cnfis?edition=2025"));

        verify(cnfisUnitFacade).generateProvisional(CnfisUnitSheet.UnitKind.DEPARTMENT, DEPARTMENT, 2025, "ion@uvt.ro", "director@uvt.ro");
        verify(cnfisUnitFacade).buildTable(CnfisUnitSheet.UnitKind.DEPARTMENT, DEPARTMENT, 2025, "director@uvt.ro");
    }
}
