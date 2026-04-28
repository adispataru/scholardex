package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;

public record ScholardexPublicationDetailViewModel(
        ScholardexPublicationView publication,
        List<AuthorRef> authors,
        String forumName,
        String year
) {
    public record AuthorRef(String id, String name) {}
}
