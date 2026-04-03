package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;

public record UserPublicationCitationsViewModel(
        ScholardexPublicationView publication,
        List<ScholardexPublicationView> citations,
        ScholardexForumView forum,
        Map<String, ScholardexAuthorView> authorMapping,
        Map<String, ScholardexForumView> forumMap
) {
}
