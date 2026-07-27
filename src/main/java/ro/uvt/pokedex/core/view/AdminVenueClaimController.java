package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationVenueClaim;
import ro.uvt.pokedex.core.service.application.PublicationVenueClaimService;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * H93 S1 — admin JSON endpoints for venue claims, mirroring the H84 merge endpoints they sit beside
 * (same {@code /admin/publications} surface, same PLATFORM_ADMIN gate from the security config).
 *
 * <p>Two ways to write a claim: {@code POST /venueClaims} queues it for review, {@code POST /venueClaim}
 * applies it on the spot. A claim raises the claimant's score and, because publications are shared, every
 * co-author's too — which is why the researcher flow (S3) only ever feeds the queue.</p>
 */
@RestController
@RequestMapping("/admin/publications")
@RequiredArgsConstructor
public class AdminVenueClaimController {

    private final PublicationVenueClaimService publicationVenueClaimService;

    /** Admin one-step: record an APPROVED claim and apply it now (projections dirty-marked). */
    @PostMapping("/venueClaim")
    public Map<String, Object> directClaim(@RequestParam String publicationId,
                                           @RequestParam String forumId,
                                           @RequestParam(defaultValue = "false") boolean workshopOf,
                                           @RequestParam(required = false) String workshopLabel,
                                           @RequestParam(required = false) String note,
                                           Principal principal) {
        PublicationVenueClaimService.ClaimApplyResult result = publicationVenueClaimService.directClaim(
                publicationId, forumId, workshopOf, workshopLabel, principalName(principal), note);
        return Map.of("outcome", result.outcome().name(),
                "publicationId", result.publicationId() == null ? "" : result.publicationId());
    }

    /** Queue a claim for review without applying it. Idempotent per publication. */
    @PostMapping("/venueClaims")
    public Map<String, Object> requestClaim(@RequestParam String publicationId,
                                            @RequestParam String forumId,
                                            @RequestParam(defaultValue = "false") boolean workshopOf,
                                            @RequestParam(required = false) String workshopLabel,
                                            @RequestParam(required = false) String note,
                                            Principal principal) {
        boolean existed = publicationVenueClaimService.findClaim(publicationId).isPresent();
        PublicationVenueClaim claim = publicationVenueClaimService.requestClaim(
                publicationId, forumId, workshopOf, workshopLabel, principalName(principal), null, note);
        return Map.of("created", !existed,
                "id", claim.getId(),
                "status", claim.getStatus().name(),
                "claimedForumId", claim.getClaimedForumId());
    }

    @PostMapping("/venueClaims/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id,
                                       @RequestParam(required = false) String note,
                                       Principal principal) {
        PublicationVenueClaimService.ClaimApplyResult result =
                publicationVenueClaimService.approve(id, principalName(principal), note);
        return Map.of("outcome", result.outcome().name(),
                "publicationId", result.publicationId() == null ? "" : result.publicationId());
    }

    /** Rejecting an applied claim reverts the displaced venue and evidence exactly. */
    @PostMapping("/venueClaims/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id,
                                      @RequestParam(required = false) String note,
                                      Principal principal) {
        PublicationVenueClaim claim = publicationVenueClaimService.reject(id, principalName(principal), note);
        return Map.of("id", claim.getId(), "status", claim.getStatus().name());
    }

    @GetMapping("/venueClaims")
    public List<PublicationVenueClaim> list(@RequestParam(required = false) PublicationVenueClaim.Status status) {
        return publicationVenueClaimService.listClaims(status);
    }

    private static String principalName(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
