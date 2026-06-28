package ro.uvt.pokedex.core.controller;

import lombok.RequiredArgsConstructor;
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
import ro.uvt.pokedex.core.service.brainmap.UserDefinedProjectService;

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

    @GetMapping
    public ResponseEntity<List<UserDefinedProjectFact>> list() {
        return ResponseEntity.ok(userDefinedProjectService.findAll());
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
