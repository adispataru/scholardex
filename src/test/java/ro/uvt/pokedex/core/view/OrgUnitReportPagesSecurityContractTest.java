package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.DepartmentReportFacade;
import ro.uvt.pokedex.core.service.application.DivisionReportFacade;
import ro.uvt.pokedex.core.service.application.OrgUnitReportRefreshService;
import ro.uvt.pokedex.core.service.application.ReportVisibilityService;
import ro.uvt.pokedex.core.service.security.OrgUnitAccessService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The report pages of a faculty or department, with the <b>real</b> security rules and the real per-unit
 * check. The page tests of these controllers run with the filters switched off, which is how heads stayed
 * locked out unnoticed: the handlers said "supervisors allowed" while the URL rule for {@code /admin/**}
 * sent every supervisor to the access-denied page.
 *
 * <p>Cast: a faculty with two departments. The dean heads the faculty, a director heads one department,
 * another director heads the other, and a researcher heads nothing.</p>
 */
@WebMvcTest({AdminDepartmentReportsController.class, AdminDivisionReportsController.class,
        AdminOrgUnitReportRefreshController.class, AdminReportVisibilityController.class})
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, OrgUnitAccessService.class, GlobalControllerAdvice.class})
class OrgUnitReportPagesSecurityContractTest {

    private static final String DENIED = "/custom-error?error=403";
    private static final String FACULTY = "div-fpse";
    private static final String PSYCHOLOGY = "dept-psy";
    private static final String EDUCATION = "dept-edu";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private DepartmentRepository departmentRepository;
    @MockitoBean
    private OrgDivisionRepository orgDivisionRepository;
    @MockitoBean
    private DepartmentReportFacade departmentReportFacade;
    @MockitoBean
    private DivisionReportFacade divisionReportFacade;
    @MockitoBean
    private OrgUnitReportRefreshService orgUnitReportRefreshService;
    @MockitoBean
    private ReportVisibilityService reportVisibilityService;
    @MockitoBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.reporting.OrgUnitPromotionBoardService orgUnitPromotionBoardService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.reporting.OrgUnitReportComparisonService orgUnitReportComparisonService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.ReportComparisonFacade reportComparisonFacade;

    @BeforeEach
    void aFacultyWithTwoDepartments() {
        OrgDivision faculty = new OrgDivision();
        faculty.setId(FACULTY);
        faculty.setName("Facultatea de Psihologie și Științe ale Educației");
        faculty.setHeadUserIds(new ArrayList<>(List.of("dean@uvt.ro")));
        Department psychology = department(PSYCHOLOGY, "Departamentul de Psihologie", "director.psy@uvt.ro");
        Department education = department(EDUCATION, "Departamentul de Științe ale Educației", "director.edu@uvt.ro");

        when(orgDivisionRepository.findById(FACULTY)).thenReturn(Optional.of(faculty));
        when(departmentRepository.findById(PSYCHOLOGY)).thenReturn(Optional.of(psychology));
        when(departmentRepository.findById(EDUCATION)).thenReturn(Optional.of(education));
        when(departmentReportFacade.findDepartment(PSYCHOLOGY)).thenReturn(Optional.of(psychology));
        when(departmentReportFacade.listReportsVisibleForDepartment(PSYCHOLOGY)).thenReturn(List.of());
        when(divisionReportFacade.findDivision(FACULTY)).thenReturn(Optional.of(faculty));
        when(divisionReportFacade.listReportsVisibleForDivision(FACULTY)).thenReturn(List.of());
    }

    private static Department department(String id, String name, String director) {
        Department department = new Department();
        department.setId(id);
        department.setName(name);
        department.setDivisionId(FACULTY);
        department.setHeadUserIds(new ArrayList<>(List.of(director)));
        return department;
    }

    /** Signed in the way the login handler leaves a user: stored roles, plus the right that comes with a post. */
    private static RequestPostProcessor as(String email, boolean head, UserRole... stored) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(new HashSet<>(Set.of(stored)));
        user.setSupervisorByPosition(head);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static RequestPostProcessor dean() {
        return as("dean@uvt.ro", true, UserRole.RESEARCHER);
    }

    private static RequestPostProcessor psychologyDirector() {
        return as("director.psy@uvt.ro", true, UserRole.RESEARCHER);
    }

    private static RequestPostProcessor educationDirector() {
        return as("director.edu@uvt.ro", true, UserRole.RESEARCHER);
    }

    private static RequestPostProcessor researcher() {
        return as("researcher@uvt.ro", false, UserRole.RESEARCHER);
    }

    private static RequestPostProcessor admin() {
        return as("admin@uvt.ro", false, UserRole.PLATFORM_ADMIN, UserRole.RESEARCHER);
    }

    private void assertDenied(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(DENIED));
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ department pages

    @Test
    void theDirectorOpensTheReportsOfTheDepartment() throws Exception {
        String html = page(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(psychologyDirector()));

        assertTrue(html.contains("Departamentul de Psihologie"));
    }

    @Test
    void theDeanOpensTheReportsOfEveryDepartmentInTheFaculty() throws Exception {
        page(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(dean()));
    }

    @Test
    void theDirectorOfAnotherDepartmentIsRefused() throws Exception {
        assertDenied(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(educationDirector()));
        assertDenied(get("/admin/departments/" + PSYCHOLOGY + "/reports/r1").with(educationDirector()));
        assertDenied(get("/admin/departments/" + PSYCHOLOGY + "/reports/r1/promotions").with(educationDirector()));
        assertDenied(get("/admin/departments/" + PSYCHOLOGY + "/reports/r1/compare").with(educationDirector()));
    }

    @Test
    void aResearcherWhoHeadsNothingIsRefused() throws Exception {
        assertDenied(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(researcher()));
        assertDenied(get("/admin/divisions/" + FACULTY + "/reports").with(researcher()));
    }

    @Test
    void aPlatformAdminOpensAnyUnit() throws Exception {
        page(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(admin()));
        page(get("/admin/divisions/" + FACULTY + "/reports").with(admin()));
    }

    @Test
    void aVisitorIsSentToSignIn() throws Exception {
        mockMvc.perform(get("/admin/departments/" + PSYCHOLOGY + "/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertTrue(
                        String.valueOf(result.getResponse().getRedirectedUrl()).contains("/login"),
                        "redirected to " + result.getResponse().getRedirectedUrl()));
    }

    // ------------------------------------------------------------------ faculty pages

    @Test
    void theDeanOpensTheReportsOfTheFaculty() throws Exception {
        String html = page(get("/admin/divisions/" + FACULTY + "/reports").with(dean()));

        assertTrue(html.contains("Facultatea de Psihologie"));
    }

    @Test
    void aDirectorDoesNotSeeTheFacultyRollUp() throws Exception {
        assertDenied(get("/admin/divisions/" + FACULTY + "/reports").with(psychologyDirector()));
        assertDenied(get("/admin/divisions/" + FACULTY + "/reports/r1").with(psychologyDirector()));
    }

    // ------------------------------------------------------------------ scoring and refresh

    @Test
    void theDirectorScoresTheDepartmentProvisionally() throws Exception {
        when(orgUnitReportRefreshService.scoreProvisionalUnlinked(any(), any(), any(), any(), any()))
                .thenReturn(new OrgUnitReportRefreshService.ProvisionalScoreResult(3, 0, 0, 0, 5, 0, List.of()));

        mockMvc.perform(post("/admin/departments/" + PSYCHOLOGY + "/reports/r1/score-provisional")
                        .with(psychologyDirector()).with(csrf()))
                .andExpect(redirectedUrl("/admin/departments/" + PSYCHOLOGY + "/reports/r1"));

        // …and the run records who asked for it.
        verify(orgUnitReportRefreshService).scoreProvisionalUnlinked(
                eq(OrgUnitReportRefreshEvent.UnitType.DEPARTMENT), eq(PSYCHOLOGY), eq("r1"), any(),
                eq("director.psy@uvt.ro"));
    }

    @Test
    void nobodyScoresOrRefreshesAUnitTheyDoNotHead() throws Exception {
        assertDenied(post("/admin/departments/" + PSYCHOLOGY + "/reports/r1/score-provisional")
                .with(educationDirector()).with(csrf()));
        assertDenied(post("/admin/departments/" + PSYCHOLOGY + "/reports/r1/refresh-all")
                .with(educationDirector()).with(csrf()));
        assertDenied(post("/admin/divisions/" + FACULTY + "/reports/r1/score-provisional")
                .with(psychologyDirector()).with(csrf()));
        assertDenied(post("/admin/divisions/" + FACULTY + "/reports/r1/refresh-all")
                .with(psychologyDirector()).with(csrf()));

        verify(orgUnitReportRefreshService, never()).scoreProvisionalUnlinked(any(), any(), any(), any(), any());
        verify(orgUnitReportRefreshService, never()).refreshAll(any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ choosing and hiding reports

    @Test
    void choosingAndHidingReportsReachTheirOwnPermissionCheck() throws Exception {
        // These two pages decide inside the handler (ReportVisibilityService); what matters here is that
        // the URL rule no longer stops a head before that decision is taken.
        when(adminCatalogFacade.findOrgDivisionById(FACULTY)).thenReturn(Optional.empty());
        when(adminCatalogFacade.findDepartmentById(PSYCHOLOGY)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/divisions/" + FACULTY + "/reports/select").with(dean()))
                .andExpect(redirectedUrl("/admin/divisions"));
        mockMvc.perform(get("/admin/departments/" + PSYCHOLOGY + "/reports/visibility").with(psychologyDirector()))
                .andExpect(redirectedUrl("/admin/departments"));
        assertDenied(get("/admin/divisions/" + FACULTY + "/reports/select").with(researcher()));
    }

    // ------------------------------------------------------------------ the rest of the admin area

    @Test
    void everythingElseUnderAdminStaysWithPlatformAdmins() throws Exception {
        for (String path : List.of("/admin", "/admin/users", "/admin/divisions", "/admin/departments",
                "/admin/divisions/" + FACULTY + "/edit-data", "/admin/departments/" + PSYCHOLOGY + "/edit-data",
                "/admin/indicators", "/admin/individualReports")) {
            assertDenied(get(path).with(dean()));
        }
        assertDenied(post("/admin/departments/update").with(dean()).with(csrf()));
        assertDenied(post("/admin/divisions/import").with(dean()).with(csrf()));
    }

    // ------------------------------------------------------------------ what the page offers a head

    @Test
    void aHeadIsNotOfferedLinksTheyCannotOpen() throws Exception {
        String forDirector = page(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(psychologyDirector()));

        assertTrue(forDirector.contains("href=\"/supervisor\""), "back should lead to the supervisor cockpit");
        assertFalse(forDirector.contains("href=\"/admin/departments\""), "the list of all departments is admin-only");
        assertFalse(forDirector.contains("href=\"/admin/users\""), "the admin sidebar was rendered for a head");

        String forAdmin = page(get("/admin/departments/" + PSYCHOLOGY + "/reports").with(admin()));
        assertTrue(forAdmin.contains("href=\"/admin/departments\""));
        assertTrue(forAdmin.contains("href=\"/admin/users\""));
    }
}
