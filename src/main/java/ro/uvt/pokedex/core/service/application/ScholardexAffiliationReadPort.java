package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScholardexAffiliationPageResponse;

public interface ScholardexAffiliationReadPort {
    ScholardexAffiliationPageResponse search(int page, int size, String sort, String direction, String q);
}
