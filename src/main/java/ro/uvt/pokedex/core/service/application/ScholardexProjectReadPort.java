package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.controller.dto.ScholardexProjectListItemResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexProjectPageResponse;

import java.util.List;

public interface ScholardexProjectReadPort {
    ScholardexProjectPageResponse search(int page, int size, String sort, String direction, String q);

    /** Resolve a single canonical project by id (for rendering a stored reference as a label), or null if absent. */
    ScholardexProjectListItemResponse findById(String id);

    /** H78 — canonical projects whose director name-signature equals {@code signature} (the "My projects" match).
     *  Empty for a blank signature. */
    List<ScholardexProjectListItemResponse> findByDirectorSignature(String signature);
}
