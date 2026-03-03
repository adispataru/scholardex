package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

public record AdminScopusPublicationSearchViewModel(
        List<Publication> publications,
        Map<String, Author> authorMap
) {
}
