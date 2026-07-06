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
    void unknownReportSurfacesAsAnErrorFlashInsteadOfAnErrorPage() throws Exception {
        when(orgUnitReportRefreshService.refreshAll(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown report: nope"));

        mockMvc.perform(post("/admin/divisions/div-1/reports/nope/refresh-all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/divisions/div-1/reports/nope"))
                .andExpect(flash().attribute("errorMessage", "Unknown report: nope"));
    }
}
