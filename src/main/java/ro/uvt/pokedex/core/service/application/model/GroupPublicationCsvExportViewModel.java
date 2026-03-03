package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record GroupPublicationCsvExportViewModel(
        List<Publication> publications,
        Map<String, Author> authorMap,
        Map<String, Forum> forumMap,
        Set<String> affiliatedAuthorIds
) {
}
