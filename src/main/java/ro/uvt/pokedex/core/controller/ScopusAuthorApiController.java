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
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.service.application.ScholardexAuthorQueryService;

@RestController
@Validated
@RequestMapping("/api/scopus")
@RequiredArgsConstructor
public class ScopusAuthorApiController {

    private final ScholardexAuthorQueryService scholardexAuthorQueryService;

    @GetMapping("/authors")
    public ResponseEntity<ScopusAuthorPageResponse> listScopusAuthors(
            @RequestParam(defaultValue = "60000434") String afid,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(scholardexAuthorQueryService.search(afid, page, size, sort, direction, q));
    }
}
