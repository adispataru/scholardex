package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.List;
import java.util.Map;

public record AdminInstitutionPublicationsViewModel(
        Institution institution,
        List<Publication> publications,
        Map<String, Author> authorMap,
        Map<String, Forum> forumMap,
        Map<Integer, List<Publication>> publicationsByYear,
        Map<Integer, Long> publicationsCountByYear,
        List<IndividualReport> individualReports
) {
}
