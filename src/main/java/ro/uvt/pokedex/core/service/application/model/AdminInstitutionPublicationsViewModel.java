package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;

import java.util.List;
import java.util.Map;

/**
 * The institution page: the staff of its faculties and their publications, counted per faculty and per year,
 * with the list of ONE year only ({@code selectedYear}) so the page stays small whatever the corpus.
 * {@code publicationsCountByYear} runs from the newest year down; a publication without a readable year
 * counts under year {@code 0}.
 */
public record AdminInstitutionPublicationsViewModel(
        Institution institution,
        List<FacultySummary> faculties,
        int staffCount,
        int publicationCount,
        Map<Integer, Long> publicationsCountByYear,
        Integer selectedYear,
        List<ScholardexPublicationView> publications,
        Map<String, ScholardexAuthorView> authorMap,
        Map<String, ScholardexForumView> forumMap
) {
    /** One faculty (division) of the institution: how many staff it has and how many distinct publications they have. */
    public record FacultySummary(String id, String name, int staff, int publications) {
    }
}
