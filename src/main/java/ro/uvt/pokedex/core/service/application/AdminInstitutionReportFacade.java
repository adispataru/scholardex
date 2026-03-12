package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Institution;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
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
    private final ScholardexProjectionReadService scopusProjectionReadService;
    private final IndividualReportRepository individualReportRepository;

    public Optional<AdminInstitutionPublicationsViewModel> buildInstitutionPublicationsView(String institutionId) {
        Institution institution = institutionRepository.findById(institutionId).orElse(null);
        if (institution == null) {
            return Optional.empty();
        }

        List<Publication> publications = loadInstitutionPublications(institution);
        Map<String, Author> authorMap = loadAuthorMap(publications);
        Map<String, Forum> forumMap = loadForumMap(publications);
        Map<Integer, List<Publication>> publicationsByYear = publications.stream()
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

        List<Publication> publications = loadInstitutionPublications(institution);
        Map<String, List<Publication>> citationMap = loadCitationMap(publications);
        Map<String, Author> authorMap = loadAuthorMap(publications, citationMap);
        Map<String, Forum> forumMap = loadForumMap(publications, citationMap);

        return Optional.of(new AdminInstitutionPublicationsExportViewModel(
                institution,
                publications,
                citationMap,
                authorMap,
                forumMap
        ));
    }

    private List<Publication> loadInstitutionPublications(Institution institution) {
        Map<String, Publication> publicationsById = new LinkedHashMap<>();
        for (Affiliation affiliation : institution.getScopusAffiliations()) {
            findPublicationsByAffiliation(affiliation.getAfid())
                    .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication));
        }
        List<Publication> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);
        return publications;
    }

    private Map<String, List<Publication>> loadCitationMap(List<Publication> publications) {
        List<String> ids = publications.stream().map(Publication::getId).toList();
        List<Citation> citations = scopusProjectionReadService.findAllCitationsByCitedIdIn(ids);
        Map<String, List<Publication>> citationMap = new HashMap<>();
        for (Citation citation : citations) {
            Optional<Publication> citingPublication = scopusProjectionReadService.findPublicationByAnyId(citation.getCitingId());
            if (citingPublication.isPresent()) {
                citationMap.putIfAbsent(citation.getCitedId(), new ArrayList<>());
                citationMap.get(citation.getCitedId()).add(citingPublication.get());
            }
        }
        citationMap.values().forEach(PublicationOrderingSupport::sortPublicationsInPlace);
        return citationMap;
    }

    private Map<String, Author> loadAuthorMap(List<Publication> publications) {
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        return scopusProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
    }

    private Map<String, Author> loadAuthorMap(List<Publication> publications, Map<String, List<Publication>> citationMap) {
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        citationMap.values().forEach(citingPublications ->
                citingPublications.forEach(citing -> authorKeys.addAll(citing.getAuthors())));
        return scopusProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
    }

    private Map<String, Forum> loadForumMap(List<Publication> publications) {
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        return scopusProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));
    }

    private Map<String, Forum> loadForumMap(List<Publication> publications, Map<String, List<Publication>> citationMap) {
        Set<String> forumKeys = publications.stream().map(Publication::getForum).collect(Collectors.toSet());
        citationMap.values().forEach(citingPublications ->
                citingPublications.forEach(citing -> forumKeys.add(citing.getForum())));
        return scopusProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));
    }

    private List<Publication> findPublicationsByAffiliation(String affiliationId) {
        return scopusProjectionReadService.findAllPublicationsByAffiliationsContaining(affiliationId);
    }
}
