package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;

public interface WosRankingReadPort {
    WosRankingPageResponse search(int page, int size, String sort, String direction, String q);
}
