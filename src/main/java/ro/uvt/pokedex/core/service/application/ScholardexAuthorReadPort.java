package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScholardexAuthorPageResponse;

import java.util.Set;

public interface ScholardexAuthorReadPort {
    ScholardexAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q);

    /** H119: the same search limited to the given author ids (the public view); {@code null} = no limit. */
    ScholardexAuthorPageResponse search(String afid, int page, int size, String sort, String direction, String q,
                                        Set<String> onlyAuthorIds);
}
