package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminScopusFacade {
    private final ScopusProjectionReadService scopusProjectionReadService;

    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        List<Publication> publications = new ArrayList<>(
                scopusProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc(paperTitle));
        publications.sort(PublicationOrderingSupport.publicationComparator());
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        Map<String, Author> authorMap = scopusProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        return new AdminScopusPublicationSearchViewModel(publications, authorMap);
    }

    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        Optional<Publication> publicationOpt = scopusProjectionReadService.findPublicationByAnyId(publicationId);
        if (publicationOpt.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = publicationOpt.get();

        List<Citation> allByCited = scopusProjectionReadService.findAllCitationsByCitedId(publication.getId());
        List<String> citingIds = allByCited.stream().map(Citation::getCitingId).toList();
        List<Publication> citations = new ArrayList<>(scopusProjectionReadService.findAllPublicationsByIdIn(citingIds));
        PublicationOrderingSupport.sortPublicationsInPlace(citations);

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citations.forEach(citation -> {
            authorKeys.addAll(citation.getAuthors());
            forumKeys.add(citation.getForum());
        });

        Map<String, Author> authorMap = scopusProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        Map<String, Forum> forumMap = scopusProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

        Forum publicationForum = scopusProjectionReadService.findForumById(publication.getForum()).orElse(null);

        return Optional.of(new AdminScopusCitationsViewModel(
                publication,
                publicationForum,
                citations,
                authorMap,
                forumMap
        ));
    }
}
