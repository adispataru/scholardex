package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.service.application.PublicationMergeAdminFacade;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminPublicationMergeViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class AdminPublicationMergeViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicationMergeAdminFacade publicationMergeAdminFacade;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade venueClaimAdminFacade;

    @org.junit.jupiter.api.BeforeEach
    void emptyClaimQueueByDefault() {
        // The template renders the claims half unconditionally; without this every render test 500s on a
        // null claimQueue. Individual tests override with content when they assert on it.
        org.mockito.Mockito.lenient().when(venueClaimAdminFacade.queue()).thenReturn(
                new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.ClaimQueueView(
                        java.util.List.of(), java.util.List.of(), java.util.List.of()));
    }

    private static PublicationMergeAdminFacade.Row row(String id, String status, boolean duplicateExists) {
        return new PublicationMergeAdminFacade.Row(
                id, status,
                new PublicationMergeAdminFacade.SideView("spub_surv", true,
                        "An analysis of mOSAIC ontology for cloud resources annotation",
                        "SCOPUS_PYTHON_AUTHOR_WORKS", "2011-12-14", null, "2-s2.0-83155184718", 163, "FEDCSIS"),
                new PublicationMergeAdminFacade.SideView("spub_dup", duplicateExists,
                        "An analysis of mOSAIC ontology for Cloud resources annotation",
                        "OPENALEX", "2011-01-01", null, null, 161, "FEDCSIS"),
                "florin@test · 2026-07-25 10:00",
                "admin@test · 2026-07-25 11:00",
                "2026-07-25 11:01",
                "same paper twice",
                "confirmed duplicate");
    }

    @Test
    void queuePageRendersAllSectionsAndActionEndpoints() throws Exception {
        when(publicationMergeAdminFacade.queue()).thenReturn(new PublicationMergeAdminFacade.MergeQueueView(
                List.of(row("dec1", "PENDING", true)),
                List.of(row("dec2", "APPROVED", false)),
                List.of(row("dec3", "REJECTED", true))
        ));

        mockMvc.perform(get("/admin/publication-merges"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/publication-merges"))
                .andExpect(content().string(containsString("Direct merge")))
                .andExpect(content().string(containsString("Pending requests")))
                .andExpect(content().string(containsString("Approved merges")))
                .andExpect(content().string(containsString("Rejected requests")))
                // side-by-side compare renders both sides with their provenance
                .andExpect(content().string(containsString("An analysis of mOSAIC ontology for cloud resources annotation")))
                .andExpect(content().string(containsString("spub_surv")))
                .andExpect(content().string(containsString("spub_dup")))
                .andExpect(content().string(containsString("EID 2-s2.0-83155184718")))
                .andExpect(content().string(containsString("no DOI")))
                .andExpect(content().string(containsString("163 cites")))
                .andExpect(content().string(containsString("FEDCSIS")))
                // an applied merge (duplicate gone) is labeled as such, a live duplicate awaits re-apply
                .andExpect(content().string(containsString("applied")))
                .andExpect(content().string(containsString("no longer exists — merged")))
                // action endpoints wired
                .andExpect(content().string(containsString("/admin/publication-merges/merge")))
                .andExpect(content().string(containsString("/admin/publication-merges/dec1/approve")))
                .andExpect(content().string(containsString("/admin/publication-merges/dec1/reject")))
                .andExpect(content().string(containsString("swap sides")))
                .andExpect(content().string(containsString("rebuild projections now")));
    }

    @Test
    void emptyQueueRendersEmptyStatesWithoutRejectedSection() throws Exception {
        when(publicationMergeAdminFacade.queue()).thenReturn(
                new PublicationMergeAdminFacade.MergeQueueView(List.of(), List.of(), List.of()));

        mockMvc.perform(get("/admin/publication-merges"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No pending requests")))
                .andExpect(content().string(containsString("No approved merges yet")));
    }

    @Test
    void directMergeDelegatesAndFlashesTheOutcome() throws Exception {
        when(publicationMergeAdminFacade.directMerge(eq("spub_surv"), eq("spub_dup"), anyString(), eq("dup"), eq(false)))
                .thenReturn(new PublicationMergeAdminFacade.OperationResult(true, "Merged spub_dup into spub_surv"));

        mockMvc.perform(post("/admin/publication-merges/merge")
                        .param("survivorId", " spub_surv ")
                        .param("duplicateId", "spub_dup")
                        .param("note", "dup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/publication-merges"))
                .andExpect(flash().attribute("successMessage", "Merged spub_dup into spub_surv"));
    }

    @Test
    void approveAndRejectDelegateWithDecisionId() throws Exception {
        when(publicationMergeAdminFacade.approve(eq("dec1"), anyString(), eq("ok"), eq(true), eq(false)))
                .thenReturn(new PublicationMergeAdminFacade.OperationResult(true, "Merged"));

        mockMvc.perform(post("/admin/publication-merges/dec1/approve")
                        .param("note", "ok")
                        .param("swapSides", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Merged"));

        mockMvc.perform(post("/admin/publication-merges/dec1/reject").param("note", "not a duplicate"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/publication-merges"));
        verify(publicationMergeAdminFacade).reject(eq("dec1"), anyString(), eq("not a duplicate"));
    }

    @Test
    void unresolvableSurvivorSurfacesAsErrorFlash() throws Exception {
        when(publicationMergeAdminFacade.directMerge(eq("spub_x"), eq("spub_y"), anyString(), eq(null), eq(false)))
                .thenThrow(new IllegalArgumentException("survivor publication not found: spub_x"));

        mockMvc.perform(post("/admin/publication-merges/merge")
                        .param("survivorId", "spub_x")
                        .param("duplicateId", "spub_y"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "survivor publication not found: spub_x"));
    }

    /* ── H93 S2: the venue-claims half of the page ─────────────────────────── */

    private static ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.Row claimRow(String id) {
        return new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.Row(
                id, "PENDING", "spub_claim", "Hybrid Task Scheduling for Constrained Systems",
                "EuroMLSys @ EUROSYS", true, "florin@test · 2026-07-27 10:00", "—", "—",
                "workshop of EuroSys", null);
    }

    @Test
    void queuePageRendersTheClaimsSectionsAndTheirActionEndpoints() throws Exception {
        when(publicationMergeAdminFacade.queue()).thenReturn(
                new PublicationMergeAdminFacade.MergeQueueView(List.of(), List.of(), List.of()));
        ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.Row approvedRow =
                new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.Row(
                        "claim2", "APPROVED", "spub_other", "Some Approved Paper", "AINA", false,
                        "florin@test · 2026-07-27 10:00", "admin · 2026-07-27 10:05", "2026-07-27 10:05",
                        null, null);
        when(venueClaimAdminFacade.queue()).thenReturn(
                new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.ClaimQueueView(
                        List.of(claimRow("claim1")), List.of(approvedRow), List.of()));

        mockMvc.perform(get("/admin/publication-merges"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Direct venue claim")))
                .andExpect(content().string(containsString("Pending venue claims")))
                .andExpect(content().string(containsString("Hybrid Task Scheduling for Constrained Systems")))
                .andExpect(content().string(containsString("EuroMLSys @ EUROSYS")))
                .andExpect(content().string(containsString("/admin/publication-merges/venue-claim")))
                .andExpect(content().string(containsString("/admin/publication-merges/venue-claims/claim1/approve")))
                .andExpect(content().string(containsString("/admin/publication-merges/venue-claims/claim1/reject")))
                // An APPROVED claim is revocable from the page — unlike a merge, the revert is cheap and
                // exact (displaced venue + evidence restored), so the affordance belongs here.
                .andExpect(content().string(containsString("Decided venue claims")))
                .andExpect(content().string(containsString("/admin/publication-merges/venue-claims/claim2/reject")))
                .andExpect(content().string(containsString("Revoke")));
    }

    @Test
    void claimApproveAndRejectDelegateWithTheClaimId() throws Exception {
        when(venueClaimAdminFacade.approve("claim1", "admin", "ok", false)).thenReturn(
                new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.OperationResult(
                        true, "Venue claim applied to spub_claim; projections marked dirty."));

        mockMvc.perform(post("/admin/publication-merges/venue-claims/claim1/approve").param("note", "ok"))
                .andExpect(status().is3xxRedirection());
        org.mockito.Mockito.verify(venueClaimAdminFacade).approve("claim1", "admin", "ok", false);

        mockMvc.perform(post("/admin/publication-merges/venue-claims/claim1/reject").param("note", "no"))
                .andExpect(status().is3xxRedirection());
        org.mockito.Mockito.verify(venueClaimAdminFacade).reject("claim1", "admin", "no");
    }

    @Test
    void directVenueClaimDelegatesTrimmedIdsAndTheWorkshopFlag() throws Exception {
        when(venueClaimAdminFacade.directClaim("spub_claim", "sforum_eurosys", true, "EuroMLSys",
                "admin", null, false)).thenReturn(
                new ro.uvt.pokedex.core.service.application.PublicationVenueClaimAdminFacade.OperationResult(
                        true, "Venue claim applied to spub_claim."));

        mockMvc.perform(post("/admin/publication-merges/venue-claim")
                        .param("publicationId", " spub_claim ")
                        .param("forumId", " sforum_eurosys ")
                        .param("workshopOf", "true")
                        .param("workshopLabel", "EuroMLSys"))
                .andExpect(status().is3xxRedirection());
        org.mockito.Mockito.verify(venueClaimAdminFacade)
                .directClaim("spub_claim", "sforum_eurosys", true, "EuroMLSys", "admin", null, false);
    }
}
