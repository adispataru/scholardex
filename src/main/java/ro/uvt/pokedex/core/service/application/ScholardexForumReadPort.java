package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;

public interface ScholardexForumReadPort {
    ScopusForumPageResponse search(int page, int size, String sort, String direction, String q);
}
