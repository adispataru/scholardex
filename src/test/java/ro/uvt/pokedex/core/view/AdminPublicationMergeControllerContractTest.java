package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.service.application.PublicationMergeService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionDirtyService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin-side "queue for review" endpoint. Its whole reason to exist is that it must NOT apply the merge —
 * before it, an admin who spotted a duplicate could only reach {@code POST /merge}, which merges on the spot,
 * and that is the path the 2026-07-25 mis-merge went through. So the load-bearing assertions here are the
 * negative ones: nothing is applied, and nothing is re-projected.
 */
@WebMvcTest(AdminPublicationMergeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminPublicationMergeControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicationMergeService publicationMergeService;
    @MockitoBean
    private ScholardexProjectionDirtyService projectionDirtyService;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.PublicationMergeSweepService publicationMergeSweepService;

    private static PublicationMergeDecision decision(PublicationMergeDecision.Status status) {
        PublicationMergeDecision decision = new PublicationMergeDecision();
        decision.setId("dec-1");
        decision.setPairKey(PublicationMergeDecision.pairKeyOf("spub_surv", "spub_dup"));
        decision.setStatus(status);
        PublicationMergeDecision.Side survivor = new PublicationMergeDecision.Side();
        survivor.setCanonicalId("spub_surv");
        PublicationMergeDecision.Side duplicate = new PublicationMergeDecision.Side();
        duplicate.setCanonicalId("spub_dup");
        decision.setSurvivor(survivor);
        decision.setDuplicate(duplicate);
        return decision;
    }

    @Test
    void requestMergeQueuesThePairWithoutApplyingOrReprojectingIt() throws Exception {
        when(publicationMergeService.findDecision("spub_surv", "spub_dup")).thenReturn(Optional.empty());
        when(publicationMergeService.requestMerge(eq("spub_surv"), eq("spub_dup"), eq("admin"), isNull(),
                eq("same paper via two routes")))
                .thenReturn(decision(PublicationMergeDecision.Status.PENDING));

        mockMvc.perform(post("/admin/publications/mergeRequests")
                        .param("survivorId", "spub_surv")
                        .param("duplicateId", "spub_dup")
                        .param("note", "same paper via two routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").value("dec-1"))
                .andExpect(jsonPath("$.pairKey").value("spub_dup|spub_surv"))
                .andExpect(jsonPath("$.survivorId").value("spub_surv"))
                .andExpect(jsonPath("$.duplicateId").value("spub_dup"));

        // The point of the endpoint: review is requested, nothing is merged and no publication is deleted.
        verify(publicationMergeService, never()).apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(publicationMergeService, never()).directMerge(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(projectionDirtyService, never()).rebuildDirtyProjections();
    }

    @Test
    void requestMergeIsIdempotentAndReportsThatItDidNotCreateTheDecision() throws Exception {
        // A re-run of a sweep must not resurrect a pair an admin already rejected.
        when(publicationMergeService.findDecision("spub_surv", "spub_dup"))
                .thenReturn(Optional.of(decision(PublicationMergeDecision.Status.REJECTED)));
        when(publicationMergeService.requestMerge(eq("spub_surv"), eq("spub_dup"), eq("admin"), isNull(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(decision(PublicationMergeDecision.Status.REJECTED));

        mockMvc.perform(post("/admin/publications/mergeRequests")
                        .param("survivorId", "spub_surv")
                        .param("duplicateId", "spub_dup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void requestMergeTrimsTheIdsBeforeUsingThem() throws Exception {
        // Ids arrive pasted from a sweep report often enough to be worth pinning.
        when(publicationMergeService.findDecision("spub_surv", "spub_dup")).thenReturn(Optional.empty());
        when(publicationMergeService.requestMerge(eq("spub_surv"), eq("spub_dup"), eq("admin"), isNull(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(decision(PublicationMergeDecision.Status.PENDING));

        mockMvc.perform(post("/admin/publications/mergeRequests")
                        .param("survivorId", "  spub_surv ")
                        .param("duplicateId", "spub_dup  "))
                .andExpect(status().isOk());

        verify(publicationMergeService).findDecision("spub_surv", "spub_dup");
        verify(publicationMergeService).requestMerge(eq("spub_surv"), eq("spub_dup"), eq("admin"), isNull(),
                org.mockito.ArgumentMatchers.any());
    }
}
