package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.service.application.IndividualReportsManagementFacade;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminIndividualReportsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminIndividualReportsControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IndividualReportsManagementFacade individualReportsManagementFacade;

    @Test
    void listDisplaysCriterionNamesWithFallback() throws Exception {
        IndividualReport report = new IndividualReport();
        report.setTitle("R1");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion named = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        named.setName("Research Impact");
        ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion unnamed = new ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion();
        unnamed.setName(" ");
        report.setCriteria(List.of(named, unnamed));
        report.setIndicators(new ArrayList<>());

        when(individualReportsManagementFacade.listIndividualReports()).thenReturn(List.of(report));
        when(individualReportsManagementFacade.listIndicatorsSortedByName()).thenReturn(List.of(new Indicator()));
        when(individualReportsManagementFacade.listInstitutions()).thenReturn(List.of());

        String html = mockMvc.perform(get("/admin/individualReports"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Research Impact"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Criterion 2"));
    }

    @Test
    void updateBindsCriterionNameAndPersistsIt() throws Exception {
        mockMvc.perform(post("/admin/individualReports/update")
                        .param("id", "rep-1")
                        .param("title", "T")
                        .param("description", "D")
                        .param("criteria[0].name", "Research Impact"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/individualReports"));

        ArgumentCaptor<IndividualReport> captor = ArgumentCaptor.forClass(IndividualReport.class);
        verify(individualReportsManagementFacade).saveIndividualReport(captor.capture());
        IndividualReport saved = captor.getValue();
        assertEquals("Research Impact", saved.getCriteria().getFirst().getName());
        assertEquals(new ArrayList<>(), saved.getCriteria().getFirst().getIndicatorIndices());
    }
}
