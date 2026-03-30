package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;

public interface ScholardexForumReadPort {
    ScholardexForumPageResponse search(int page, int size, String sort, String direction, String q);
}
