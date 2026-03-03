package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.repository.scopus.ScopusAuthorRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusCitationRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusForumRepository;
import ro.uvt.pokedex.core.repository.scopus.ScopusPublicationRepository;
import ro.uvt.pokedex.core.service.application.model.AdminScopusCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.AdminScopusPublicationSearchViewModel;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminScopusFacade {
    private final ScopusPublicationRepository scopusPublicationRepository;
    private final ScopusAuthorRepository scopusAuthorRepository;
    private final ScopusCitationRepository scopusCitationRepository;
    private final ScopusForumRepository scopusForumRepository;

    public AdminScopusPublicationSearchViewModel buildPublicationSearchView(String paperTitle) {
        List<Publication> publications = scopusPublicationRepository.findByTitleContains(paperTitle);
        Set<String> authorKeys = new HashSet<>();
        publications.forEach(publication -> authorKeys.addAll(publication.getAuthors()));
        Map<String, Author> authorMap = scopusAuthorRepository.findByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        return new AdminScopusPublicationSearchViewModel(publications, authorMap);
    }

    public Optional<AdminScopusCitationsViewModel> buildPublicationCitationsView(String publicationId) {
        Optional<Publication> publicationOpt = scopusPublicationRepository.findById(publicationId);
        if (publicationOpt.isEmpty()) {
            return Optional.empty();
        }
        Publication publication = publicationOpt.get();

        List<Citation> allByCited = scopusCitationRepository.findAllByCitedId(publication.getId());
        List<String> citingIds = allByCited.stream().map(Citation::getCitingId).toList();
        List<Publication> citations = scopusPublicationRepository.findAllByIdIn(citingIds);

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citations.forEach(citation -> {
            authorKeys.addAll(citation.getAuthors());
            forumKeys.add(citation.getForum());
        });

        Map<String, Author> authorMap = scopusAuthorRepository.findByIdIn(authorKeys).stream()
                .collect(Collectors.toMap(Author::getId, author -> author));
        Map<String, Forum> forumMap = scopusForumRepository.findByIdIn(forumKeys).stream()
                .collect(Collectors.toMap(Forum::getId, forum -> forum));

        Forum publicationForum = scopusForumRepository.findById(publication.getForum()).orElse(null);

        return Optional.of(new AdminScopusCitationsViewModel(
                publication,
                publicationForum,
                citations,
                authorMap,
                forumMap
        ));
    }
}
