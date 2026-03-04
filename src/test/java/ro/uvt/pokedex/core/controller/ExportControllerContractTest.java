package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExportControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ForumExportFacade forumExportFacade;

    @Test
    void exportEndpointReturnsWorkbookResponseContract() throws Exception {
        when(forumExportFacade.buildBookAndBookSeriesExport()).thenReturn(new ForumExportViewModel(
                List.of(new ForumExportRow("Book A", "1234-5678", "8765-4321", "src-1", "Book"))
        ));

        byte[] content = mockMvc.perform(get("/api/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=forums.xlsx"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertTrue(content.length > 0);
    }

    @Test
    void exportEndpointFacadeFailureReturnsDeterministicServerError() throws Exception {
        when(forumExportFacade.buildBookAndBookSeriesExport()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/export"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("internal_server_error"))
                .andExpect(jsonPath("$.path").value("/api/export"));
    }
}
