package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.controller.dto.WosCategoryPageResponse;

@Service
@RequiredArgsConstructor
public class WosCategoryQueryService {

    private final PostgresWosCategoryReadPort postgresWosCategoryReadPort;

    public WosCategoryPageResponse search(int page, int size, String sort, String direction, String q) {
        return postgresWosCategoryReadPort.search(page, size, sort, direction, q);
    }
}
