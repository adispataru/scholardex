package ro.uvt.pokedex.core.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingReadPort;

@RestController
@Validated
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class WosRankingApiController {

    private final ObjectProvider<PostgresWosRankingReadPort> postgresWosRankingReadPortProvider;

    @GetMapping("/wos")
    public ResponseEntity<WosRankingPageResponse> listWosRankings(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String q
    ) {
        PostgresWosRankingReadPort port = postgresWosRankingReadPortProvider.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("Postgres WoS ranking read port is not available.");
        }
        return ResponseEntity.ok(port.search(page, size, sort, direction, q));
    }
}
