package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

public interface ScholardexAuthorReadPort {
    ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q);
}
