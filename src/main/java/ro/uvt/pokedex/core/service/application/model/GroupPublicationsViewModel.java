package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.Group;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;

public record GroupPublicationsViewModel(
        Group group,
        List<Researcher> researchers,
        List<ScholardexPublicationView> publications,
        Map<String, ScholardexAuthorView> authorMap,
        Map<String, ScholardexForumView> forumMap,
        Map<Integer, List<ScholardexPublicationView>> publicationsByYear,
        Map<Integer, Long> publicationsCountByYear,
        Map<Integer, Map<String, Long>> venueClassCountByYear,
        List<IndividualReport> individualReports
) {
}
