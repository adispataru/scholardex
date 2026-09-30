package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The institution page and its Excel export. Since H134 the corpus is the one of the institution's <b>staff</b>:
 * every faculty (division) of the institution, its current department affiliations, and the publications each
 * researcher's profile resolves to, with their own decisions applied ({@link EffectiveAuthorshipReadService}).
 * The earlier derivation through the Scopus affiliations linked to the institution record walked 18,712
 * publications of 4,466 authors for UVT (prod, 2026-09-30) and rendered all of them on one page; the record
 * had never been linked anyway, so the page showed nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminInstitutionReportFacade {
    private final InstitutionRepository institutionRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final OrgDivisionRepository orgDivisionRepository;
    private final OrgUnitRosterService orgUnitRosterService;
    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;

    /** The staff of every faculty and the union of their publications. */
    private record StaffCorpus(List<AdminInstitutionPublicationsViewModel.FacultySummary> faculties,
                               int staffCount,
                               List<ScholardexPublicationView> publications) {
    }

    /**
     * The page for one institution: counts per faculty and per year, and the publications of {@code year}
     * alone — the newest year with publications when {@code year} is null or has none.
     */
    public Optional<AdminInstitutionPublicationsViewModel> buildInstitutionPublicationsView(String institutionId, Integer year) {
        Institution institution = institutionRepository.findById(institutionId).orElse(null);
        if (institution == null) {
            return Optional.empty();
        }

        StaffCorpus corpus = loadStaffCorpus(institution.getId());
        Map<Integer, Long> countByYear = new TreeMap<>(Comparator.reverseOrder());
        for (ScholardexPublicationView publication : corpus.publications()) {
            countByYear.merge(yearOf(publication), 1L, Long::sum);
        }
        Integer selectedYear = year != null && countByYear.containsKey(year)
                ? year
                : countByYear.keySet().stream().findFirst().orElse(null);
        List<ScholardexPublicationView> ofYear = new ArrayList<>();
        if (selectedYear != null) {
            corpus.publications().stream().filter(p -> yearOf(p) == selectedYear).forEach(ofYear::add);
            PublicationOrderingSupport.sortPublicationsInPlace(ofYear);
        }

        return Optional.of(new AdminInstitutionPublicationsViewModel(
                institution,
                corpus.faculties(),
                corpus.staffCount(),
                corpus.publications().size(),
                countByYear,
                selectedYear,
                ofYear,
                loadAuthorMap(ofYear),
                loadForumMap(ofYear)
        ));
    }

    public Optional<AdminInstitutionPublicationsExportViewModel> buildInstitutionPublicationsExport(String institutionId) {
        Institution institution = institutionRepository.findById(institutionId).orElse(null);
        if (institution == null) {
            return Optional.empty();
        }

        List<ScholardexPublicationView> publications = loadStaffCorpus(institution.getId()).publications();
        Map<String, List<ScholardexPublicationView>> citationMap = loadCitationMap(publications);
        Map<String, ScholardexAuthorView> authorMap = loadAuthorMap(publications, citationMap);
        Map<String, ScholardexForumView> forumMap = loadForumMap(publications, citationMap);

        return Optional.of(new AdminInstitutionPublicationsExportViewModel(
                institution,
                publications,
                citationMap,
                authorMap,
                forumMap
        ));
    }

    /**
     * Faculties in name order; each one's roster is its departments' current staff (joint appointments
     * count once per faculty). A researcher's publications are resolved once even when two faculties list
     * them; the institution total counts every publication once.
     */
    private StaffCorpus loadStaffCorpus(String institutionId) {
        List<OrgDivision> divisions = new ArrayList<>(orgDivisionRepository.findByInstitutionId(institutionId));
        divisions.sort(Comparator.comparing(d -> d.getName() == null ? "" : d.getName(), String.CASE_INSENSITIVE_ORDER));
        Map<String, ScholardexPublicationView> publicationsById = new LinkedHashMap<>();
        Map<String, List<ScholardexPublicationView>> publicationsByUser = new HashMap<>();
        Set<String> staff = new HashSet<>();
        List<AdminInstitutionPublicationsViewModel.FacultySummary> faculties = new ArrayList<>();
        for (OrgDivision division : divisions) {
            Set<String> ofFaculty = new HashSet<>();
            List<OrgUnitRosterService.RosterMember> roster = orgUnitRosterService.divisionRoster(division.getId());
            for (OrgUnitRosterService.RosterMember member : roster) {
                String email = member.user().getEmail();
                staff.add(email);
                List<ScholardexPublicationView> own = publicationsByUser.computeIfAbsent(email,
                        effectiveAuthorshipReadService::findEffectivePublicationsForUser);
                for (ScholardexPublicationView publication : own) {
                    if (publication == null || publication.getId() == null) {
                        continue;
                    }
                    publicationsById.putIfAbsent(publication.getId(), publication);
                    ofFaculty.add(publication.getId());
                }
            }
            faculties.add(new AdminInstitutionPublicationsViewModel.FacultySummary(
                    division.getId(),
                    division.getName() == null ? division.getId() : division.getName(),
                    roster.size(),
                    ofFaculty.size()));
        }
        List<ScholardexPublicationView> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return new StaffCorpus(faculties, staff.size(), publications);
    }

    /** The publication's year, or 0 when the cover date does not give one. */
    private static int yearOf(ScholardexPublicationView publication) {
        return PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log).orElse(0);
    }

    private Map<String, List<ScholardexPublicationView>> loadCitationMap(List<ScholardexPublicationView> publications) {
        List<String> ids = publications.stream().map(ScholardexPublicationView::getId).toList();
        List<ScholardexCitationView> citations = scholardexProjectionReadService.findAllCitationsByCitedIdIn(ids);
        Map<String, List<ScholardexPublicationView>> citationMap = new HashMap<>();
        for (ScholardexCitationView citation : citations) {
            Optional<ScholardexPublicationView> citingPublication = scholardexProjectionReadService.findPublicationByAnyId(citation.getCitingId());
            if (citingPublication.isPresent()) {
                citationMap.putIfAbsent(citation.getCitedId(), new ArrayList<>());
                citationMap.get(citation.getCitedId()).add(citingPublication.get());
            }
        }
        citationMap.values().forEach(PublicationOrderingSupport::sortPublicationsInPlace);
        return citationMap;
    }

    private Map<String, ScholardexAuthorView> loadAuthorMap(List<ScholardexPublicationView> publications) {
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> {
            if (publication.getAuthors() != null) {
                authorKeys.addAll(publication.getAuthors());
            }
        });
        authorKeys.remove(null);
        if (authorKeys.isEmpty()) {
            return Map.of();
        }
        return scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author, (a, b) -> a));
    }

    private Map<String, ScholardexAuthorView> loadAuthorMap(List<ScholardexPublicationView> publications, Map<String, List<ScholardexPublicationView>> citationMap) {
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        citationMap.values().forEach(citingPublications ->
                citingPublications.forEach(citing -> authorKeys.addAll(citing.getAuthors())));
        return scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author));
    }

    private Map<String, ScholardexForumView> loadForumMap(List<ScholardexPublicationView> publications) {
        Set<String> forumKeys = publications.stream()
                .map(ScholardexPublicationView::getForum)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (forumKeys.isEmpty()) {
            return Map.of();
        }
        return scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum, (a, b) -> a));
    }

    private Map<String, ScholardexForumView> loadForumMap(List<ScholardexPublicationView> publications, Map<String, List<ScholardexPublicationView>> citationMap) {
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        citationMap.values().forEach(citingPublications ->
                citingPublications.forEach(citing -> forumKeys.add(citing.getForum())));
        return scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));
    }
}
