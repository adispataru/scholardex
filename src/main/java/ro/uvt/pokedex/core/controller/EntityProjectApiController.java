package ro.uvt.pokedex.core.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectPageResponse;
import ro.uvt.pokedex.core.service.application.PostgresScholardexProjectReadPort;

/**
 * H64 slice 2 — canonical project picker API (mirrors {@link EntityAffiliationApiController}). Backs the workspace
 * autocomplete that fills an activity's {@code PROJECT_GRANT_ID} reference; {@code /{id}} resolves a stored reference
 * back to a human label.
 */
@RestController
@Validated
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntityProjectApiController {

    private final PostgresScholardexProjectReadPort postgresScholardexProjectReadPort;

    @GetMapping("/projects")
    public ResponseEntity<ScholardexProjectPageResponse> listProjects(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "title") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(postgresScholardexProjectReadPort.search(page, size, sort, direction, q));
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<ScholardexProjectListItemResponse> getProject(@PathVariable String id) {
        ScholardexProjectListItemResponse project = postgresScholardexProjectReadPort.findById(id);
        return project == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(project);
    }
}
