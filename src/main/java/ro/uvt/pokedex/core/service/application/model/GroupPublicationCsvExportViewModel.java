package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record GroupPublicationCsvExportViewModel(
        List<ScholardexPublicationView> publications,
        Map<String, ScholardexAuthorView> authorMap,
        Map<String, ScholardexForumView> forumMap,
        Set<String> affiliatedAuthorIds
) {
}
