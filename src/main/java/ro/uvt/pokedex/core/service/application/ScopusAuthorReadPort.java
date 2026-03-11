package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;

public interface ScopusAuthorReadPort {
    ScopusAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q);
}
