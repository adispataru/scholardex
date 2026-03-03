package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

        MvcResult asyncResult = mockMvc.perform(get("/api/export"))
                .andExpect(request().asyncStarted())
                .andReturn();

        byte[] content = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=forums.xlsx"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertTrue(content.length > 0);
    }
}
