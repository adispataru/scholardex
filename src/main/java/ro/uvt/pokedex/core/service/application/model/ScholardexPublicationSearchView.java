package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;

public record ScholardexPublicationSearchView(
        List<ScholardexPublicationView> publications,
        Map<String, ScholardexAuthorView> authorMap
) {
}
