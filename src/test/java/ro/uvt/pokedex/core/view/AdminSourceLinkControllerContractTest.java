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
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.application.SourceLinkOperationsFacade;

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

@WebMvcTest(AdminSourceLinkController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminSourceLinkControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceLinkOperationsFacade sourceLinkOperationsFacade;

    @Test
    void sourceLinksPageRendersTemplate() throws Exception {
        when(sourceLinkOperationsFacade.findSourceLinks(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(sourceLinkOperationsFacade.replayEligibilitySummary())
                .thenReturn(new ScholardexSourceLinkService.ReplayEligibilitySummary(1, 2, 3));
        when(sourceLinkOperationsFacade.findByCanonical(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/source-links"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/source-links"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Source Link Ledger")));
    }

    @Test
    void sourceLinksPageDelegatesFilters() throws Exception {
        when(sourceLinkOperationsFacade.findSourceLinks(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(sourceLinkOperationsFacade.replayEligibilitySummary())
                .thenReturn(new ScholardexSourceLinkService.ReplayEligibilitySummary(0, 0, 0));
        when(sourceLinkOperationsFacade.findByCanonical(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/source-links")
                        .param("page", "1")
                        .param("size", "25")
                        .param("entityType", "PUBLICATION")
                        .param("source", "USER_DEFINED")
                        .param("linkState", "CONFLICT")
                        .param("sourceBatchId", "b1")
                        .param("sourceCorrelationId", "c1")
                        .param("sourceEventId", "e1")
                        .param("updatedFrom", "2026-03-01")
                        .param("updatedTo", "2026-03-08"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/source-links"));

        verify(sourceLinkOperationsFacade).findSourceLinks(eq(1), eq(25), eq("PUBLICATION"), eq("USER_DEFINED"),
                eq("CONFLICT"), eq("b1"), eq("c1"), eq("e1"), any(), any());
    }

    @Test
    void reconcileRequiresConfirmation() throws Exception {
        mockMvc.perform(post("/admin/source-links/reconcile").param("confirmation", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/source-links"));
    }

    @Test
    void reconcileDelegatesWhenConfirmationIsValid() throws Exception {
        when(sourceLinkOperationsFacade.reconcileSourceLinks())
                .thenReturn(new ScholardexSourceLinkService.ImportRepairSummary(2L, 1L, 0L));

        mockMvc.perform(post("/admin/source-links/reconcile").param("confirmation", "RECONCILE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/source-links"));

        verify(sourceLinkOperationsFacade).reconcileSourceLinks();
    }
}
