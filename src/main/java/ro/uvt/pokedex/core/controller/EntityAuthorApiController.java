package ro.uvt.pokedex.core.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.ScholardexAuthorPageResponse;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;

@RestController
@Validated
@RequestMapping("/api/entities")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class EntityAuthorApiController {

    private final PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;

    @GetMapping("/authors")
    public ResponseEntity<ScholardexAuthorPageResponse> listAuthors(
            @RequestParam(required = false) String afid,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        return ResponseEntity.ok(postgresScholardexAuthorReadPort.search(afid, page, size, sort, direction, q));
    }
}
