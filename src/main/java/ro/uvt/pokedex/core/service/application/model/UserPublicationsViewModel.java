package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

public record UserPublicationsViewModel(
        List<Publication> publications,
        int hIndex,
        Map<String, Author> authorMap,
        Map<String, Forum> forumMap,
        int numCitations
) {
}
