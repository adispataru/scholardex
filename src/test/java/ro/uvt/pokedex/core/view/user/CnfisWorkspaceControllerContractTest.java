package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetHeader;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.CnfisReportingFacade;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;
import ro.uvt.pokedex.core.service.application.model.CnfisSheetViewModel;
import ro.uvt.pokedex.core.service.reporting.CnfisDomainCatalog;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H129 — the CNFIS entry of the sidebar: the editions, the person's Anexa 5, the head of the sheet, the copies. */
@WebMvcTest(CnfisWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class CnfisWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private CnfisReportingFacade cnfisReportingFacade;

    private final User user = new User();

    @BeforeEach
    void editions() {
        user.setEmail("u@e-uvt.ro");
        when(cnfisReportingFacade.editions()).thenReturn(List.of(
                new CnfisEditionViewModel(2027, 2023, 2026, 2025, true),
                new CnfisEditionViewModel(2025, 2021, 2024, 2023, false)));
        when(cnfisReportingFacade.edition(null)).thenReturn(Optional.of(new CnfisEditionViewModel(2027, 2023, 2026, 2025, true)));
        when(cnfisReportingFacade.edition(2025)).thenReturn(Optional.of(new CnfisEditionViewModel(2025, 2021, 2024, 2023, false)));
    }

    @Test
    void thePageShowsTheSheetOfTheChosenEdition() throws Exception {
        CnfisSheetHeader header = new CnfisSheetHeader();
        header.setDomainCode("2");
        header.setHirschScopus(7);
        CnfisSheetViewModel sheet = new CnfisSheetViewModel(
                new CnfisEditionViewModel(2025, 2021, 2024, 2023, false), header,
                List.of(new CnfisDomainCatalog.CnfisDomain("2", "Informatică", "Matematică")),
                List.of(new CnfisSheetViewModel.ReportChoice("rep-fv", "FV Info 2026")), 123.5,
                List.of(new CnfisSheetViewModel.Row("spub_1", "2023", "A reported paper", "Journal of Testing", "10.1/one", "WOS:1",
                        "ISI Q1", "AIS Q1 · MATHEMATICS-SCIE · list 2023", 2023, 3, 2)),
                List.of(new CnfisSheetViewModel.LeftOut("spub_2", "2022", "A letter", "Journal of Testing", "10.1/two",
                        "document type 'le': reported are Article, Review and Proceedings Paper")),
                List.of(new CnfisSheetViewModel.Patent("act-1", "2023", "Sistem de răcire", "EP123", "EPO", "European", 3, 2)),
                new CnfisSheetViewModel.Arts(true,
                        List.of(new CnfisSheetViewModel.ArtsRow("act-2", "2023", "Expoziție personală", "Bienala de la Veneția", "INTERNATIONAL_TOP", "INDIVIDUAL", 0)),
                        List.of(),
                        List.of(new CnfisSheetViewModel.CitationRow("act-4", "2019", "Concert pentru vioară <op. 3>",
                                "Revista Muzica, nr. 2, 2022", "2022")),
                        List.of(new CnfisSheetViewModel.LeftOut("act-5", "2021", "Suita a II-a", null, null,
                                "the year of the cited work is not declared"))),
                new CnfisSheetViewModel.Sport(true,
                        List.of(new CnfisSheetViewModel.SportRow("act-3", "2023", "Campionatul Național Universitar de atletism", "CNU 2023", "UNIVERSITY", "PLACE_1", "NATIONAL", 4)),
                        List.of()),
                new CnfisSheetViewModel.Humanities(true,
                        List.of(new CnfisSheetViewModel.HumanitiesRow("spub_h", "2023", "Studia Philologica", "", "", "1234-5678", "", "10.1/h",
                                "On something", "SCOPUS_Q2", 2023, "CiteScore Q2 · list 2023", null, 2, 1)),
                        List.of(), List.of(2023)),
                List.of("Ion Popescu"),
                List.of(new CnfisSheetViewModel.Snapshot("s1", "2026-09-30T10:00:00Z", 1, 1, false, false),
                        new CnfisSheetViewModel.Snapshot("s0", "2026-09-01T10:00:00Z", 1, 0, true, true)),
                new CnfisSheetViewModel.Counts(1, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        when(cnfisReportingFacade.buildSheet("u@e-uvt.ro", 2025)).thenReturn(Optional.of(sheet));

        String html = mockMvc.perform(get("/user/cnfis").param("edition", "2025").with(authenticated(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("A reported paper") && html.contains("ISI Q1") && html.contains("list 2023"));
        assertTrue(html.contains("A letter") && html.contains("Article, Review and Proceedings Paper"));
        assertTrue(html.contains("EP123") && html.contains("EPO"));
        assertTrue(html.contains("Ion Popescu"), "the co-author without an employment record is named");
        assertTrue(html.contains("123.50") || html.contains("123,50"), "the CNATDCU score of the chosen report");
        assertTrue(html.contains("value=\"2\" selected") || html.contains("selected=\"selected\" value=\"2\"") || html.contains("value=\"2\"\n"),
                "the stored domain is the selected one");
        assertTrue(html.contains("/user/cnfis/2025/export"));
        assertTrue(html.contains("Bienala de la Veneția") && html.contains("/user/cnfis/2025/export-arts"), "Anexa 5.1 for an arts domain");
        assertTrue(html.contains("Studia Philologica") && html.contains("/user/cnfis/2025/export-humanities"), "Anexa 5.3 for a humanities domain");
        assertTrue(html.contains("Concert pentru vioară &lt;op. 3&gt;") && html.contains("Revista Muzica, nr. 2, 2022"),
                "Anexa 4.1: the cited work and its citation, as text");
        assertTrue(html.contains("/user/cnfis/2025/export-citations") && html.contains("/user/cnfis/snapshots/s1/export-citations"));
        assertTrue(html.contains("the year of the cited work is not declared"), "a citation left out says why");
        assertTrue(html.contains("1 ianuarie 2025") || html.contains("1 January 2025"), "the whole career, up to the reference date");
        assertTrue(html.contains("/user/cnfis/2025/freeze"));
        assertTrue(html.contains("/user/cnfis/snapshots/s1/release"), "an unlocked copy can be released");
        assertTrue(!html.contains("/user/cnfis/snapshots/s0/release"), "a copy in an institutional table cannot");
        assertTrue(html.contains("/user/cnfis/snapshots/s0/export"));
        assertTrue(!html.contains("2,025") && !html.contains("2.025"));
    }

    @Test
    void savingTheHeaderGoesThroughTheFacadeAndBack() throws Exception {
        mockMvc.perform(post("/user/cnfis/2025/header").with(authenticated(user))
                        .param("domainCode", "2").param("scoreReportId", "rep-fv")
                        .param("hirschGoogleScholar", "12").param("hirschWebOfScience", "9").param("hirschScopus", "10"))
                .andExpect(redirectedUrl("/user/cnfis?edition=2025"));

        verify(cnfisReportingFacade).saveHeader(eq("u@e-uvt.ro"), eq(2025),
                eq(new CnfisReportingFacade.HeaderForm("2", "rep-fv", null, null, 12, 9, 10)));
    }

    @Test
    void freezingAndReleasingAreTheSignedInPersonsOwn() throws Exception {
        when(cnfisReportingFacade.release("u@e-uvt.ro", "s9")).thenReturn(CnfisReportingFacade.ReleaseResult.LOCKED);

        mockMvc.perform(post("/user/cnfis/2025/freeze").with(authenticated(user)))
                .andExpect(redirectedUrl("/user/cnfis?edition=2025"));
        mockMvc.perform(post("/user/cnfis/snapshots/s9/release").param("edition", "2025").with(authenticated(user)))
                .andExpect(redirectedUrl("/user/cnfis?edition=2025"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("errorMessage", "cnfis.release.locked"));

        verify(cnfisReportingFacade).freeze("u@e-uvt.ro", 2025);
    }

    @Test
    void aPersonWithoutAProfileSeesWhatToDoFirst() throws Exception {
        when(cnfisReportingFacade.buildSheet(eq("u@e-uvt.ro"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());

        String html = mockMvc.perform(get("/user/cnfis").with(authenticated(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // the default bundle is Romanian; either wording proves the empty state rendered
        assertTrue(html.contains("Complete your researcher profile") || html.contains("Completați mai întâi profilul"));
    }

    @Test
    void aVisitorIsSentToSignIn() throws Exception {
        mockMvc.perform(get("/user/cnfis")).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/user/cnfis/2025/freeze")).andExpect(redirectedUrl("/login"));
    }

    private static RequestPostProcessor authenticated(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
