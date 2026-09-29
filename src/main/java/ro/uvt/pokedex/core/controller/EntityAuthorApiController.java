package ro.uvt.pokedex.core.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.ScholardexAuthorPageResponse;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;
import ro.uvt.pokedex.core.service.application.PublicCatalogScope;

import java.util.Set;

@RestController
@Validated
@RequestMapping("/api/entities")
@RequiredArgsConstructor
public class EntityAuthorApiController {

    private final PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    private final PublicCatalogScope publicCatalogScope;

    @GetMapping("/authors")
    public ResponseEntity<ScholardexAuthorPageResponse> listAuthors(
            @RequestParam(required = false) String afid,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q,
            Authentication authentication
    ) {
        // H119: a visitor sees the university's own authors only; signed-in users see the whole directory.
        Set<String> onlyAuthorIds = publicCatalogScope.restrictionFor(authentication).orElse(null);
        if (onlyAuthorIds != null) {
            return ResponseEntity.ok(
                    postgresScholardexAuthorReadPort.search(afid, page, size, sort, direction, q, onlyAuthorIds));
        }
        return ResponseEntity.ok(postgresScholardexAuthorReadPort.search(afid, page, size, sort, direction, q));
    }
}
