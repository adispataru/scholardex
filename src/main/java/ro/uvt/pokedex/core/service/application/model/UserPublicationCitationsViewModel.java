package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

public record UserPublicationCitationsViewModel(
        Publication publication,
        List<Publication> citations,
        Forum forum,
        Map<String, Author> authorMapping,
        Map<String, Forum> forumMap
) {
}
