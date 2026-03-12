package ro.uvt.pokedex.core.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.service.application.ScholardexAffiliationQueryService;

@RestController
@Validated
@RequestMapping("/api/scopus")
@RequiredArgsConstructor
public class ScopusAffiliationApiController {

    private final ScholardexAffiliationQueryService scholardexAffiliationQueryService;

    @GetMapping("/affiliations")
    public ResponseEntity<ScopusAffiliationPageResponse> listScopusAffiliations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(scholardexAffiliationQueryService.search(page, size, sort, direction, q));
    }
}
