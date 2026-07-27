package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.service.application.PublicationVenueClaimService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H93 S1 — the venue-claim admin endpoints. As with merges, the load-bearing assertion is the negative
 * one on the queue path: {@code POST /venueClaims} must NOT apply anything — a claim moves the claimant's
 * score and every co-author's, so only the reviewed paths write.
 */
@WebMvcTest(AdminVenueClaimController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminVenueClaimControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicationVenueClaimService claimService;

    private static PublicationVenueClaim claim(PublicationVenueClaim.Status status) {
        PublicationVenueClaim claim = new PublicationVenueClaim();
        claim.setId("claim-1");
        claim.setPublicationId("spub_1");
        claim.setClaimedForumId("sforum_eurosys");
        claim.setStatus(status);
        return claim;
    }

    @Test
    void requestClaimQueuesWithoutApplying() throws Exception {
        when(claimService.findClaim("spub_1")).thenReturn(Optional.empty());
        when(claimService.requestClaim(eq("spub_1"), eq("sforum_eurosys"), eq(true), eq("EuroMLSys"),
                eq("admin"), isNull(), eq("workshop of EuroSys")))
                .thenReturn(claim(PublicationVenueClaim.Status.PENDING));

        mockMvc.perform(post("/admin/publications/venueClaims")
                        .param("publicationId", "spub_1")
                        .param("forumId", "sforum_eurosys")
                        .param("workshopOf", "true")
                        .param("workshopLabel", "EuroMLSys")
                        .param("note", "workshop of EuroSys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(claimService, never()).approve(anyString(), anyString(), any());
        verify(claimService, never()).directClaim(anyString(), anyString(), anyBoolean(), any(), anyString(), any());
    }

    @Test
    void requestClaimSurfacesAnExistingClaimAsNotCreated() throws Exception {
        when(claimService.findClaim("spub_1")).thenReturn(Optional.of(claim(PublicationVenueClaim.Status.REJECTED)));
        when(claimService.requestClaim(any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(claim(PublicationVenueClaim.Status.REJECTED));

        mockMvc.perform(post("/admin/publications/venueClaims")
                        .param("publicationId", "spub_1")
                        .param("forumId", "sforum_eurosys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void approveAppliesAndReportsTheOutcome() throws Exception {
        when(claimService.approve("claim-1", "admin", null))
                .thenReturn(new PublicationVenueClaimService.ClaimApplyResult(
                        PublicationVenueClaimService.ClaimOutcome.APPLIED, "spub_1"));

        mockMvc.perform(post("/admin/publications/venueClaims/claim-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.publicationId").value("spub_1"));
    }

    @Test
    void rejectReportsTheFinalStatus() throws Exception {
        when(claimService.reject("claim-1", "admin", "wrong venue"))
                .thenReturn(claim(PublicationVenueClaim.Status.REJECTED));

        mockMvc.perform(post("/admin/publications/venueClaims/claim-1/reject")
                        .param("note", "wrong venue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
