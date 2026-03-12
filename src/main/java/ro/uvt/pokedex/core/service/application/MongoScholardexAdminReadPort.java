package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MongoScholardexAdminReadPort implements ScholardexAdminReadPort {

    private final ScholardexProjectionReadService scholardexProjectionReadService;

    @Override
    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        List<Publication> publications = new ArrayList<>(
                scholardexProjectionReadService.findPublicationsByTitleContainingIgnoreCaseOrderByCoverDateDesc(paperTitle));
        publications.sort(PublicationOrderingSupport.publicationComparator());
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        Map<String, Author> authorMap = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        return new AdminScopusPublicationSearchViewModel(publications, authorMap);
    }

    @Override
    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        Optional<Publication> publicationOpt = scholardexProjectionReadService.findPublicationByAnyId(publicationId);
        if (publicationOpt.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = publicationOpt.get();

        List<Citation> allByCited = scholardexProjectionReadService.findAllCitationsByCitedId(publication.getId());
        List<String> citingIds = allByCited.stream().map(Citation::getCitingId).toList();
        List<Publication> citations = new ArrayList<>(scholardexProjectionReadService.findAllPublicationsByIdIn(citingIds));
        PublicationOrderingSupport.sortPublicationsInPlace(citations);

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citations.forEach(citation -> {
            authorKeys.addAll(citation.getAuthors());
            forumKeys.add(citation.getForum());
        });

        Map<String, Author> authorMap = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        Map<String, Forum> forumMap = scholardexProjectionReadService.findForumsByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

        Forum publicationForum = scholardexProjectionReadService.findForumById(publication.getForum()).orElse(null);

        return Optional.of(new AdminScopusCitationsViewModel(
                publication,
                publicationForum,
                citations,
                authorMap,
                forumMap
        ));
    }
}
