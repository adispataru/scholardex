package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.reporting.OrgUnitReportRefreshEvent;
import ro.uvt.pokedex.core.service.application.OrgUnitReportRefreshService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOrgUnitReportRefreshController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminOrgUnitReportRefreshControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrgUnitReportRefreshService orgUnitReportRefreshService;

    @Test
    void divisionRefreshDefaultsToStaleScopeAndRedirectsBackWithASummary() throws Exception {
        when(orgUnitReportRefreshService.refreshAll(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrgUnitReportRefreshService.RefreshAllResult(3, 0, 0, 1, 4, 2000));

        mockMvc.perform(post("/admin/divisions/div-1/reports/rep-1/refresh-all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/divisions/div-1/reports/rep-1"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(orgUnitReportRefreshService).refreshAll(
                eq(OrgUnitReportRefreshEvent.UnitType.DIVISION), eq("div-1"), eq("rep-1"),
                eq(OrgUnitReportRefreshService.Scope.STALE), any(), any());
    }

    @Test
    void departmentRefreshPlumbsTheAllScopeAndLabel() throws Exception {
        when(orgUnitReportRefreshService.refreshAll(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrgUnitReportRefreshService.RefreshAllResult(2, 0, 0, 0, 2, 1000));

        mockMvc.perform(post("/admin/departments/dept-cs/reports/rep-1/refresh-all")
                        .param("scope", "all")
                        .param("label", "before evaluation"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/departments/dept-cs/reports/rep-1"));

        verify(orgUnitReportRefreshService).refreshAll(
                eq(OrgUnitReportRefreshEvent.UnitType.DEPARTMENT), eq("dept-cs"), eq("rep-1"),
                eq(OrgUnitReportRefreshService.Scope.ALL), eq("before evaluation"), any());
    }

    @Test
    void divisionProvisionalScoringRedirectsBackWithASummary() throws Exception {
        when(orgUnitReportRefreshService.scoreProvisionalUnlinked(any(), any(), any(), any(), any()))
                .thenReturn(new OrgUnitReportRefreshService.ProvisionalScoreResult(
                        3, 0, 1, 2, 6, 5000, java.util.List.of("Ana Pop", "Dan Ionescu")));

        mockMvc.perform(post("/admin/divisions/div-1/reports/rep-1/score-provisional"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/divisions/div-1/reports/rep-1"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(orgUnitReportRefreshService).scoreProvisionalUnlinked(
                eq(OrgUnitReportRefreshEvent.UnitType.DIVISION), eq("div-1"), eq("rep-1"), any(), any());
    }

    @Test
    void departmentProvisionalScoringPlumbsTheLabel() throws Exception {
        when(orgUnitReportRefreshService.scoreProvisionalUnlinked(any(), any(), any(), any(), any()))
                .thenReturn(new OrgUnitReportRefreshService.ProvisionalScoreResult(
                        1, 0, 0, 0, 1, 1000, java.util.List.of()));

        mockMvc.perform(post("/admin/departments/dept-cs/reports/rep-1/score-provisional")
                        .param("label", "first pass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/departments/dept-cs/reports/rep-1"));

        verify(orgUnitReportRefreshService).scoreProvisionalUnlinked(
                eq(OrgUnitReportRefreshEvent.UnitType.DEPARTMENT), eq("dept-cs"), eq("rep-1"),
                eq("first pass"), any());
    }

    @Test
    void provisionalSummaryNamesUnresolvedMembersWithACap() {
        String message = AdminOrgUnitReportRefreshController.summarizeProvisional(
                new OrgUnitReportRefreshService.ProvisionalScoreResult(2, 1, 3, 7, 13, 4000,
                        java.util.List.of("A", "B", "C", "D", "E", "F", "G")));

        org.junit.jupiter.api.Assertions.assertTrue(message.contains("Provisionally scored 2 of 13"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("1 failed"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("3 with confirmed publications skipped"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("A, B, C, D, E (+2 more)"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("link Scopus/WoS/Scholar/ORCID ids"));
    }

    @Test
    void unknownReportSurfacesAsAnErrorFlashInsteadOfAnErrorPage() throws Exception {
        when(orgUnitReportRefreshService.refreshAll(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown report: nope"));

        mockMvc.perform(post("/admin/divisions/div-1/reports/nope/refresh-all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/divisions/div-1/reports/nope"))
                .andExpect(flash().attribute("errorMessage", "Unknown report: nope"));
    }
}
