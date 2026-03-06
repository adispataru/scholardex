package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.Author;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.ResearcherService;
import ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class UserPublicationFacade {
    private final ResearcherService researcherService;
    private final ScopusProjectionReadService scopusProjectionReadService;

    public Optional<UserPublicationsViewModel> buildUserPublicationsView(String researcherId) {
        Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
        if (researcherById.isEmpty()) {
            return Optional.empty();
        }

        Researcher researcher = researcherById.get();
        List<Author> byId = scopusProjectionReadService.findAuthorsByIdIn(researcher.getScopusId());
        Map<String, Publication> publicationsById = new LinkedHashMap<>();
        byId.forEach(author -> scopusProjectionReadService.findAllPublicationsByAuthorsContaining(author.getId())
                .forEach(publication -> publicationsById.putIfAbsent(publication.getId(), publication)));
        List<Publication> publications = new ArrayList<>(publicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(publications);

        int hIndex = computeHIndex(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        AtomicInteger numCitations = new AtomicInteger();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
            numCitations.addAndGet(p.getCitedbyCount());
        });

        List<Author> byIdIn = scopusProjectionReadService.findAuthorsByIdIn(authorKeys);
        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, Forum> forumMap = new HashMap<>();
        List<Forum> forums = scopusProjectionReadService.findForumsByIdIn(forumKeys);
        forums.forEach(f -> forumMap.put(f.getId(), f));

        return Optional.of(new UserPublicationsViewModel(
                publications,
                hIndex,
                authorMap,
                forumMap,
                numCitations.get()
        ));
    }

    public Optional<UserPublicationCitationsViewModel> buildCitationsView(String publicationId) {
        Optional<Publication> byId = scopusProjectionReadService.findPublicationByAnyId(publicationId);
        if (byId.isEmpty()) {
            return Optional.empty();
        }

        Publication publication = byId.get();
        List<Citation> allByCited = scopusProjectionReadService.findAllCitationsByCitedId(publication.getId());
        List<String> citations = new ArrayList<>();
        allByCited.forEach(c -> citations.add(c.getCitingId()));
        List<Publication> citationsPub = new ArrayList<>(scopusProjectionReadService.findAllPublicationsByIdIn(citations));
        PublicationOrderingSupport.sortPublicationsInPlace(citationsPub);

        Optional<Forum> forumOpt = scopusProjectionReadService.findForumById(publication.getForum());
        if (forumOpt.isEmpty()) {
            return Optional.empty();
        }

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citationsPub.forEach(p -> forumKeys.add(p.getForum()));

        List<Author> byIdIn = scopusProjectionReadService.findAuthorsByIdIn(authorKeys);
        List<Forum> forums = scopusProjectionReadService.findForumsByIdIn(forumKeys);

        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, Forum> forumMap = new HashMap<>();
        forums.forEach(f -> forumMap.put(f.getId(), f));

        return Optional.of(new UserPublicationCitationsViewModel(
                publication,
                citationsPub,
                forumOpt.get(),
                authorMap,
                forumMap
        ));
    }

    // Uses canonical Mongo `id`; EID-based lookup belongs to importer/scopus integration paths.
    public Optional<Publication> findPublicationForEdit(String publicationId) {
        return scopusProjectionReadService.findPublicationByAnyId(publicationId);
    }

    // Uses canonical Mongo `id`; EID-based lookup belongs to importer/scopus integration paths.
    public void updatePublicationMetadata(String publicationId, Publication patch) {
        Optional<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView> byId =
                scopusProjectionReadService.findPublicationViewById(publicationId)
                        .or(() -> scopusProjectionReadService.findPublicationByAnyId(publicationId)
                                .flatMap(p -> scopusProjectionReadService.findPublicationViewById(p.getId())));
        byId.ifPresent(pub -> {
            pub.setSubtypeDescription(patch.getSubtypeDescription());
            pub.setSubtype(patch.getSubtype());
            scopusProjectionReadService.savePublicationView(pub);
        });
    }

    private int computeHIndex(List<Publication> publications) {
        int n = publications.size();
        int[] citationCounts = new int[n + 1];

        for (Publication pub : publications) {
            int citedByCount = pub.getCitedbyCount();
            if (citedByCount > n) {
                citationCounts[n]++;
            } else {
                citationCounts[citedByCount]++;
            }
        }

        int totalPapers = 0;
        for (int i = n; i >= 0; i--) {
            totalPapers += citationCounts[i];
            if (totalPapers >= i) {
                return i;
            }
        }

        return 0;
    }
}
