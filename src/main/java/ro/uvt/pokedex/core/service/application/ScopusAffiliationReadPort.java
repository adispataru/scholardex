package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;

public interface ScopusAffiliationReadPort {
    ScopusAffiliationPageResponse search(int page, int size, String sort, String direction, String q);
}
