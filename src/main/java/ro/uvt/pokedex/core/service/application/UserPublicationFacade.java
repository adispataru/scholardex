package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.Affiliation;
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
    private static final Logger log = LoggerFactory.getLogger(UserPublicationFacade.class);

    private final ResearcherService researcherService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;

    public Optional<UserPublicationsViewModel> buildUserPublicationsView(String researcherId) {
        long startedAtNanos = System.nanoTime();
        log.info("User publications load started: researcherId={}", researcherId);

        Optional<Researcher> researcherById = researcherService.findResearcherById(researcherId);
        if (researcherById.isEmpty()) {
            log.info("User publications load finished: researcherId={} status=not-found totalMs={}",
                    researcherId, nanosToMillis(System.nanoTime() - startedAtNanos));
            return Optional.empty();
        }

        Researcher researcher = researcherById.get();
        long lookupKeysStartedAtNanos = System.nanoTime();
        List<String> authorLookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(researcher);
        long lookupKeysMs = nanosToMillis(System.nanoTime() - lookupKeysStartedAtNanos);

        long authorsFetchStartedAtNanos = System.nanoTime();
        List<Author> byId = scholardexProjectionReadService.findAuthorsByIdIn(
                authorLookupKeys
        );
        long authorsFetchMs = nanosToMillis(System.nanoTime() - authorsFetchStartedAtNanos);

        long publicationsFetchStartedAtNanos = System.nanoTime();
        List<String> canonicalAuthorIds = byId.stream()
                .map(Author::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Publication> publications = dedupeAndSortPublications(
                scholardexProjectionReadService.findAllPublicationsByAuthorsIn(canonicalAuthorIds)
        );
        long publicationsFetchMs = nanosToMillis(System.nanoTime() - publicationsFetchStartedAtNanos);

        long relatedLookupStartedAtNanos = System.nanoTime();
        UserPublicationsViewModel viewModel = buildPublicationsViewModel(publications);
        long relatedLookupMs = nanosToMillis(System.nanoTime() - relatedLookupStartedAtNanos);

        long totalMs = nanosToMillis(System.nanoTime() - startedAtNanos);
        log.info("User publications load finished: researcherId={} lookupKeys={} resolvedAuthors={} canonicalAuthors={} publications={} forums={} citations={} timingsMs[lookupKeys={}, authorsFetch={}, publicationsFetch={}, relatedLookup={}, total={}]",
                researcherId,
                authorLookupKeys.size(),
                byId.size(),
                canonicalAuthorIds.size(),
                publications.size(),
                viewModel.forumMap().size(),
                viewModel.numCitations(),
                lookupKeysMs,
                authorsFetchMs,
                publicationsFetchMs,
                relatedLookupMs,
                totalMs);

        return Optional.of(viewModel);
    }

    public Optional<UserPublicationsViewModel> buildAuthorPublicationsView(String authorId) {
        Optional<Author> author = scholardexProjectionReadService.findAuthorById(authorId);
        if (author.isEmpty()) {
            return Optional.empty();
        }

        Author theAuthor = author.get();
        List<Publication> publications = dedupeAndSortPublications(
                scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(theAuthor.getId())
        );

        List<String> affiliationIds = theAuthor.getAffiliations() != null
                ? theAuthor.getAffiliations().stream()
                    .map(Affiliation::getAfid)
                    .filter(Objects::nonNull)
                    .toList()
                : List.of();
        List<Affiliation> affiliations = scholardexProjectionReadService.findAffiliationsByIdIn(affiliationIds);

        UserPublicationsViewModel baseVm = buildPublicationsViewModel(publications);
        return Optional.of(new UserPublicationsViewModel(
                baseVm.publications(), baseVm.hIndex(), baseVm.authorMap(), baseVm.forumMap(),
                baseVm.numCitations(), theAuthor, affiliations
        ));
    }

    public Optional<UserPublicationCitationsViewModel> buildCitationsView(String publicationId) {
        Optional<Publication> byId = scholardexProjectionReadService.findPublicationByAnyId(publicationId);
        if (byId.isEmpty()) {
            return Optional.empty();
        }

        Publication publication = byId.get();
        List<Citation> allByCited = scholardexProjectionReadService.findAllCitationsByCitedId(publication.getId());
        List<String> citations = new ArrayList<>();
        allByCited.forEach(c -> citations.add(c.getCitingId()));
        List<Publication> citationsPub = new ArrayList<>(scholardexProjectionReadService.findAllPublicationsByIdIn(citations));
        PublicationOrderingSupport.sortPublicationsInPlace(citationsPub);

        Optional<Forum> forumOpt = scholardexProjectionReadService.findForumById(publication.getForum());
        if (forumOpt.isEmpty()) {
            return Optional.empty();
        }

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citationsPub.forEach(p -> forumKeys.add(p.getForum()));

        List<Author> byIdIn = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys);
        List<Forum> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);

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
        return scholardexProjectionReadService.findPublicationByAnyId(publicationId);
    }

    // Uses canonical Mongo `id`; EID-based lookup belongs to importer/scopus integration paths.
    public void updatePublicationMetadata(String publicationId, Publication patch) {
        Optional<ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView> byId =
                scholardexProjectionReadService.findPublicationViewById(publicationId)
                        .or(() -> scholardexProjectionReadService.findPublicationByAnyId(publicationId)
                                .flatMap(p -> scholardexProjectionReadService.findPublicationViewById(p.getId())));
        byId.ifPresent(pub -> {
            pub.setSubtypeDescription(patch.getSubtypeDescription());
            pub.setSubtype(patch.getSubtype());
            scholardexProjectionReadService.savePublicationView(pub);
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

    private List<Publication> dedupeAndSortPublications(List<Publication> publications) {
        Map<String, Publication> dedupedPublicationsById = new LinkedHashMap<>();
        for (Publication publication : publications) {
            if (publication.getId() == null || publication.getId().isBlank()) {
                continue;
            }
            dedupedPublicationsById.putIfAbsent(publication.getId(), publication);
        }
        List<Publication> deduped = new ArrayList<>(dedupedPublicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(deduped);
        return deduped;
    }

    private UserPublicationsViewModel buildPublicationsViewModel(List<Publication> publications) {
        int hIndex = computeHIndex(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        AtomicInteger numCitations = new AtomicInteger();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
            numCitations.addAndGet(p.getCitedbyCount());
        });

        List<Author> byIdIn = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys);
        Map<String, Author> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, Forum> forumMap = new HashMap<>();
        List<Forum> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);
        forums.forEach(f -> forumMap.put(f.getId(), f));

        return new UserPublicationsViewModel(
                publications,
                hIndex,
                authorMap,
                forumMap,
                numCitations.get(),
                null,
                List.of()
        );
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
