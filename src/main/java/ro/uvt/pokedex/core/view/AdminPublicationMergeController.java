package ro.uvt.pokedex.core.view;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationMergeDecision;
import ro.uvt.pokedex.core.service.application.PublicationMergeService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionDirtyService;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * H84 S1 — admin JSON endpoints for publication merges. The queue UI (S2) and the researcher flagging flow (S3)
 * build on these; until then the direct-merge endpoint is the operational tool for known duplicate pairs.
 *
 * <p>Approve/direct-merge apply the merge immediately and then rebuild the dirty projections synchronously
 * (a batchless dirty marker escalates to the full view rebuild, which re-projects the re-keyed edges and drops
 * the retired publication's rows — the TRUNCATE+reload semantics). Merges are rare admin actions; the
 * minutes-long call mirrors the other synchronous initialization endpoints.</p>
 */
@RestController
@RequestMapping("/admin/publications")
@RequiredArgsConstructor
public class AdminPublicationMergeController {

    private final PublicationMergeService publicationMergeService;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    @PostMapping("/merge")
    public Map<String, Object> directMerge(@RequestParam String survivorId,
                                           @RequestParam String duplicateId,
                                           @RequestParam(required = false) String note,
                                           @RequestParam(defaultValue = "true") boolean rebuildProjections,
                                           Principal principal) {
        PublicationMergeService.MergeApplyResult result =
                publicationMergeService.directMerge(survivorId, duplicateId, principalName(principal), note);
        return withProjectionRebuild(result, rebuildProjections);
    }

    @GetMapping("/mergeRequests")
    public List<PublicationMergeDecision> list(@RequestParam(required = false) PublicationMergeDecision.Status status) {
        return publicationMergeService.listDecisions(status);
    }

    @PostMapping("/mergeRequests/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id,
                                       @RequestParam(required = false) String note,
                                       @RequestParam(defaultValue = "false") boolean swapSides,
                                       @RequestParam(defaultValue = "true") boolean rebuildProjections,
                                       Principal principal) {
        PublicationMergeService.MergeApplyResult result =
                publicationMergeService.approve(id, principalName(principal), note, swapSides);
        return withProjectionRebuild(result, rebuildProjections);
    }

    @PostMapping("/mergeRequests/{id}/reject")
    public PublicationMergeDecision reject(@PathVariable String id,
                                           @RequestParam(required = false) String note,
                                           Principal principal) {
        return publicationMergeService.reject(id, principalName(principal), note);
    }

    private Map<String, Object> withProjectionRebuild(PublicationMergeService.MergeApplyResult result,
                                                      boolean rebuildProjections) {
        String projections = "skipped";
        if (rebuildProjections && result.outcome() == PublicationMergeService.MergeOutcome.MERGED) {
            ScholardexProjectionDirtyService.ProjectionRebuildResult rebuild =
                    projectionDirtyService.rebuildDirtyProjections();
            projections = rebuild.hasFailures() ? "failed: " + rebuild.errors() : "rebuilt";
        }
        return Map.of(
                "outcome", result.outcome().name(),
                "survivorId", result.survivorId() == null ? "" : result.survivorId(),
                "duplicateId", result.duplicateId() == null ? "" : result.duplicateId(),
                "rowsMoved", result.rowsMoved(),
                "rowsDeduplicated", result.rowsDeduplicated(),
                "projections", projections
        );
    }

    private static String principalName(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
