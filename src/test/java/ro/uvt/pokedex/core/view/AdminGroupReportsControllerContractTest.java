package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.GroupReport;
import ro.uvt.pokedex.core.service.application.GroupReportsManagementFacade;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminGroupReportsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminGroupReportsControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GroupReportsManagementFacade groupReportsManagementFacade;

    @Test
    void listDisplaysCriterionNamesWithFallback() throws Exception {
        GroupReport report = new GroupReport();
        report.setTitle("GR1");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion named = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        named.setName("Teaching Output");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion unnamed = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        unnamed.setName("");
        report.setCriteria(List.of(named, unnamed));
        report.setIndicators(new ArrayList<>());
        report.setGroups(new ArrayList<>());

        when(groupReportsManagementFacade.listGroupReports()).thenReturn(List.of(report));
        when(groupReportsManagementFacade.listIndicators()).thenReturn(List.of(new Indicator()));
        when(groupReportsManagementFacade.listGroups()).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/groupReports"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Teaching Output"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Criterion 2"));
    }

    @Test
    void updateBindsCriterionNameAndPersistsIt() throws Exception {
        mockMvc.perform(post("/admin/groupReports/update")
                        .param("id", "rep-1")
                        .param("title", "T")
                        .param("description", "D")
                        .param("criteria[0].name", "Teaching Output"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/groupReports"));

        ArgumentCaptor<GroupReport> captor = ArgumentCaptor.forClass(GroupReport.class);
        verify(groupReportsManagementFacade).saveGroupReport(captor.capture());
        GroupReport saved = captor.getValue();
        assertEquals("Teaching Output", saved.getCriteria().getFirst().getName());
        assertEquals(new ArrayList<>(), saved.getCriteria().getFirst().getIndicatorIndices());
    }
}
