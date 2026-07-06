package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.service.application.DepartmentReportFacade;
import ro.uvt.pokedex.core.service.application.model.OrgUnitReportViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDepartmentReportsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDepartmentReportsControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentReportFacade departmentReportFacade;

    @Test
    void departmentReportViewRendersWithoutTheDepartmentColumn() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setId("rep-1");
        report.setTitle("CS 2026");
        report.setCriteria(List.of());
        OrgUnitReportViewModel vm = new OrgUnitReportViewModel(
                "dept-cs", "Computer Science", report, List.of(), Map.of(), Map.of(), List.of(),
                Map.of(), Map.of(), 0, 0, 0, null, null, null, null, List.of(),
                List.of(), Map.of(),
                new OrgUnitReportViewModel.DashboardTotals(0, 0, null, 0, 0), "{}");
        when(departmentReportFacade.buildView(eq("dept-cs"), eq("rep-1"), isNull()))
                .thenReturn(Optional.of(vm));

        String html = mockMvc.perform(get("/admin/departments/dept-cs/reports/rep-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(html.contains("<th>Department</th>"));
        assertTrue(html.contains("Computer Science"));
    }
}
