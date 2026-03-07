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
        when(conflictOperationsFacade.findWosIdentityConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.findWosFactConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.findScopusLinkConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/conflicts"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conflicts"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/wos/identity/clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/wos/fact/clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/conflicts/scopus/link/clear")));
    }

    @Test
    void conflictsPageAcceptsFilterAndPaginationParams() throws Exception {
        when(conflictOperationsFacade.findWosIdentityConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.findWosFactConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(conflictOperationsFacade.findScopusLinkConflicts(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/conflicts")
                        .param("wosIdentityPage", "2")
                        .param("wosIdentitySize", "25")
                        .param("wosIdentitySourceVersion", "v2020")
                        .param("wosIdentitySourceFile", "AIS_2020")
                        .param("wosIdentityConflictType", "AMBIGUOUS_MATCH")
                        .param("wosFactPage", "1")
                        .param("wosFactSize", "30")
                        .param("wosFactSourceVersion", "v2020")
                        .param("wosFactType", "METRIC")
                        .param("wosFactConflictReason", "source-precedence")
                        .param("scopusLinkPage", "3")
                        .param("scopusLinkSize", "40")
                        .param("scopusLinkEnrichmentSource", "WOSEXTRACTOR")
                        .param("scopusLinkKeyType", "wosId")
                        .param("scopusLinkConflictReason", "ENRICHMENT_KEY_ALREADY_ASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conflicts"));

        verify(conflictOperationsFacade).findWosIdentityConflicts(2, 25, "v2020", "AIS_2020", "AMBIGUOUS_MATCH");
        verify(conflictOperationsFacade).findWosFactConflicts(1, 30, "v2020", "METRIC", "source-precedence");
        verify(conflictOperationsFacade).findScopusLinkConflicts(3, 40, "WOSEXTRACTOR", "wosId", "ENRICHMENT_KEY_ALREADY_ASSIGNED");
    }

    @Test
    void clearEndpointsRedirectAndDelegateWhenResetConfirmationProvided() throws Exception {
        when(conflictOperationsFacade.clearWosIdentityConflicts()).thenReturn(10L);
        when(conflictOperationsFacade.clearWosFactConflicts()).thenReturn(20L);
        when(conflictOperationsFacade.clearScopusLinkConflicts()).thenReturn(30L);

        mockMvc.perform(post("/admin/conflicts/wos/identity/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/fact/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/scopus/link/clear").param("confirmation", "RESET"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));

        verify(conflictOperationsFacade).clearWosIdentityConflicts();
        verify(conflictOperationsFacade).clearWosFactConflicts();
        verify(conflictOperationsFacade).clearScopusLinkConflicts();
    }

    @Test
    void clearEndpointsRequireResetConfirmation() throws Exception {
        mockMvc.perform(post("/admin/conflicts/wos/identity/clear").param("confirmation", "nope"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/wos/fact/clear"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));
        mockMvc.perform(post("/admin/conflicts/scopus/link/clear").param("confirmation", " reset "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conflicts"));

        org.mockito.Mockito.verifyNoInteractions(conflictOperationsFacade);
    }
}
