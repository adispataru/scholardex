package ro.uvt.pokedex.core.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private ForumExportFacade forumExportFacade;
    @MockitoBean
    private MeterRegistry meterRegistry;
    @MockitoBean
    private Counter counter;

    @Test
    void exportEndpointReturnsWorkbookResponseContract() throws Exception {
        when(meterRegistry.counter("core.export.forum.requests", "outcome", "success")).thenReturn(counter);
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
        verify(counter).increment();
    }

    @Test
    void exportEndpointFacadeFailureReturnsDeterministicServerError() throws Exception {
        when(meterRegistry.counter("core.export.forum.requests", "outcome", "failure")).thenReturn(counter);
        when(forumExportFacade.buildBookAndBookSeriesExport()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/export"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("internal_server_error"))
                .andExpect(jsonPath("$.path").value("/api/export"));
        verify(counter).increment();
    }
}
