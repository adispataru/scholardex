package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.ConflictOperationsFacade;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminConflictController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminConflictControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConflictOperationsFacade conflictOperationsFacade;

    @Test
    void conflictsPageRendersTemplateAndEndpoints() throws Exception {
        when(conflictOperationsFacade.findIdentityConflicts(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.summarizeIdentityConflicts())
                .thenReturn(new ConflictOperationsFacade.ConflictSummary(0, 0, 0));

        mockMvc.perform(get("/admin/conflicts"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conflicts"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/identity/open/clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/wos/identity/clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/wos/fact/clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/scopus/link/clear")));
    }

    @Test
    void conflictsPageAcceptsFilterAndPaginationParams() throws Exception {
        when(conflictOperationsFacade.findIdentityConflicts(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.summarizeIdentityConflicts())
                .thenReturn(new ConflictOperationsFacade.ConflictSummary(1, 2, 3));

        mockMvc.perform(get("/admin/conflicts")
                        .param("page", "2")
                        .param("size", "25")
                        .param("entityType", "PUBLICATION")
                        .param("incomingSource", "USER_DEFINED")
                        .param("reasonCode", "SOURCE_ID_COLLISION")
                        .param("status", "OPEN")
                        .param("detectedFrom", "2026-01-01")
                        .param("detectedTo", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conflicts"));

        verify(conflictOperationsFacade).findIdentityConflicts(eq(2), eq(25), eq("PUBLICATION"), eq("USER_DEFINED"), eq("SOURCE_ID_COLLISION"), eq("OPEN"), any(), any());
    }

    @Test
    void resolveDismissAndBulkEndpointsRedirectAndDelegate() throws Exception {
        when(conflictOperationsFacade.updateConflictStatus("c1", "RESOLVED", "")).thenReturn(1L);
        when(conflictOperationsFacade.updateConflictStatus("c2", "DISMISSED", "")).thenReturn(1L);
        when(conflictOperationsFacade.bulkUpdateConflictStatus(any(), eq("RESOLVED"), eq(""))).thenReturn(2L);

        mockMvc.perform(post("/admin/conflicts/resolve").param("id", "c1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/dismiss").param("id", "c2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/bulkStatus")
                        .param("ids", "a", "b")
                        .param("action", "resolve"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));

        verify(conflictOperationsFacade).updateConflictStatus("c1", "RESOLVED", "");
        verify(conflictOperationsFacade).updateConflictStatus("c2", "DISMISSED", "");
        verify(conflictOperationsFacade).bulkUpdateConflictStatus(any(), eq("RESOLVED"), eq(""));
    }

    @Test
    void clearEndpointsRedirectAndDelegateWhenResetConfirmationProvided() throws Exception {
        when(conflictOperationsFacade.clearOpenIdentityConflicts()).thenReturn(7L);
        when(conflictOperationsFacade.clearWosIdentityConflicts()).thenReturn(10L);
        when(conflictOperationsFacade.clearWosFactConflicts()).thenReturn(20L);
        when(conflictOperationsFacade.clearScopusLinkConflicts()).thenReturn(30L);

        mockMvc.perform(post("/admin/conflicts/identity/open/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/identity/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/fact/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/scopus/link/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));

        verify(conflictOperationsFacade).clearOpenIdentityConflicts();
        verify(conflictOperationsFacade).clearWosIdentityConflicts();
        verify(conflictOperationsFacade).clearWosFactConflicts();
        verify(conflictOperationsFacade).clearScopusLinkConflicts();
    }

    @Test
    void clearEndpointsRequireResetConfirmation() throws Exception {
        mockMvc.perform(post("/admin/conflicts/identity/open/clear").param("confirmation", "nope"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/identity/clear").param("confirmation", "nope"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/fact/clear"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/scopus/link/clear").param("confirmation", " reset "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
    }
}
