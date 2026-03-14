package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.UserDefinedTriageFacade;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminUserDefinedTriageController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminUserDefinedTriageControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserDefinedTriageFacade userDefinedTriageFacade;

    @Test
    void triagePageRendersTemplateAndDelegatesSnapshot() throws Exception {
        when(userDefinedTriageFacade.snapshot(50, 50)).thenReturn(new UserDefinedTriageFacade.UserDefinedTriageSnapshot(
                3L,
                2L,
                new UserDefinedTriageFacade.SourceLinkStateSummary(1L, 1L, 1L, 0L),
                new UserDefinedTriageFacade.ConflictStateSummary(2L, 0L, 0L),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/admin/user-defined-triage"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-defined-triage"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("USER_DEFINED Triage")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/source-links?source=USER_DEFINED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts?incomingSource=USER_DEFINED")));

        verify(userDefinedTriageFacade).snapshot(50, 50);
    }
}
