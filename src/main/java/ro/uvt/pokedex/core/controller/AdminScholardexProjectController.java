package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.UserDefinedProjectRequest;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;
import ro.uvt.pokedex.core.service.brainmap.ProjectRebuildService;
import ro.uvt.pokedex.core.service.brainmap.UserDefinedProjectService;
import ro.uvt.pokedex.core.service.cordis.CordisProject;
import ro.uvt.pokedex.core.service.cordis.CordisProjectClient;

import java.util.List;

/**
 * H64 slice 4b — admin CRUD for user-defined projects (the trusted-budget source merged into the canonical layer).
 * Manual entry here; the CORDIS-fetch endpoint (4b.3) pre-fills a payload from the live CORDIS XML.
 */
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
public class AdminScholardexProjectController {

    private final UserDefinedProjectService userDefinedProjectService;
    private final CordisProjectClient cordisProjectClient;
    private final ProjectRebuildService projectRebuildService;

    /** Legal-name fragment used to auto-suggest UVT's per-partner contribution from a CORDIS project. */
    @Value("${core.projects.cordis.org-name-match:Universitatea de Vest}")
    private String cordisOrgNameMatch;

    @GetMapping
    public ResponseEntity<List<UserDefinedProjectFact>> list() {
        return ResponseEntity.ok(userDefinedProjectService.findAll());
    }

    /** Scoped ops trigger: re-import the brainmap dump + re-derive the canonical project layer + projection. */
    @PostMapping("/rebuild")
    public ResponseEntity<ProjectRebuildService.ProjectRebuildResult> rebuild() {
        return ResponseEntity.ok(projectRebuildService.rebuild());
    }

    /**
     * Live CORDIS lookup by EU grant id → a preview with the full partner list and UVT's suggested € (its
     * {@code ecContribution}). The admin confirms and POSTs a create with the chosen budget.
     */
    @GetMapping("/cordis/{grantId}")
    public ResponseEntity<CordisPreviewResponse> cordisPreview(@PathVariable String grantId) {
        CordisProject project;
        try {
            project = cordisProjectClient.fetch(grantId);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        CordisProject.CordisOrg uvt = project.organizationFor(cordisOrgNameMatch);
        return ResponseEntity.ok(new CordisPreviewResponse(
                project,
                uvt == null ? null : uvt.legalName(),
                uvt == null ? null : uvt.ecContribution()));
    }

    /** CORDIS preview: the parsed project + the auto-matched UVT org name + its suggested budget. */
    public record CordisPreviewResponse(CordisProject project, String suggestedOrgName, Long suggestedBudget) {
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDefinedProjectFact> get(@PathVariable String id) {
        UserDefinedProjectFact fact = userDefinedProjectService.findById(id);
        return fact == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(fact);
    }

    @PostMapping
    public ResponseEntity<UserDefinedProjectFact> createOrUpdate(@RequestBody UserDefinedProjectRequest request) {
        if (isBlank(request.euGrantId()) && isBlank(request.code()) && isBlank(request.id())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(userDefinedProjectService.save(request.toFact(), currentUserEmail()));
    }

    private static String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return userDefinedProjectService.delete(id) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
