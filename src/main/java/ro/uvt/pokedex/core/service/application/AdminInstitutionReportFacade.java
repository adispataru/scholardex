package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsExportViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminInstitutionPublicationsViewModel;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminInstitutionReportFacade {
    private final InstitutionRepository institutionRepository;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final IndividualReportRepository individualReportRepository;

    public Optional<AdminInstitutionPublicationsViewModel> buildInstitutionPublicationsView(String institutionId) {
        Institution institution = institutionRepository.findById(institutionId).orElse(null);
        if (institution == null) {
            return Optional.empty();
        }

        List<ScholardexPublicationView> publications = loadInstitutionPublications(institution);
        Map<String, ScholardexAuthorView> authorMap = loadAuthorMap(publications);
        Map<String, ScholardexForumView> forumMap = loadForumMap(publications);
        Map<Integer, List<ScholardexPublicationView>> publicationsByYear = publications.stream()
                .map(publication -> new AbstractMap.SimpleEntry<>(
                        publication,
                        PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)))
                .filter(entry -> entry.getValue().isPresent())
                .collect(Collectors.groupingBy(
                        entry -> entry.getValue().get(),
                        TreeMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));
        publicationsByYear.values().forEach(PublicationOrderingSupport::sortPublicationsInPlace);
        Map<Integer, Long> publicationsCountByYear = publications.stream()
                .map(publication -> PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.groupingBy(year -> year, TreeMap::new, Collectors.counting()));
        List<IndividualReport> individualReports = individualReportRepository.findAll();

        return Optional.of(new AdminInstitutionPublicationsViewModel(
                institution,
                publications,
                authorMap,
                forumMap,
                publicationsByYear,
                publicationsCountByYear,
                individualReports
        ));
    }

    public Optional<AdminInstitutionPublicationsExportViewModel> buildInstitutionPublicationsExport(String institutionId) {
        Institution institution = institutionRepository.findById(institutionId).orElse(null);
        if (institution == null) {
            return Optional.empty();
        }

        List<ScholardexPublicationView> publications = loadInstitutionPublications(institution);
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

    private List<ScholardexPublicationView> loadInstitutionPublications(Institution institution) {
        Map<String, ScholardexPublicationView> publicationsById = new LinkedHashMap<>();
        for (ScholardexAffiliationView affiliation : institution.getScopusAffiliations()) {
            findPublicationsByAffiliation(affiliation.getAfid())
                    .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication));
        }
        List<ScholardexPublicationView> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
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
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        return scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(ScholardexAuthorView::getId, author -> author));
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
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        return scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));
    }

    private Map<String, ScholardexForumView> loadForumMap(List<ScholardexPublicationView> publications, Map<String, List<ScholardexPublicationView>> citationMap) {
        Set<String> forumKeys = publications.stream().map(ScholardexPublicationView::getForum).collect(Collectors.toSet());
        citationMap.values().forEach(citingPublications ->
                citingPublications.forEach(citing -> forumKeys.add(citing.getForum())));
        return scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(ScholardexForumView::getId, forum -> forum));
    }

    private List<ScholardexPublicationView> findPublicationsByAffiliation(String affiliationId) {
        return scholardexProjectionReadService.findAllPublicationsByAffiliationsContaining(affiliationId);
    }
}
