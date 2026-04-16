package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.model.PublicationMetadataPatch;
import ro.uvt.pokedex.core.service.application.model.UserPublicationCitationsViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class UserPublicationFacade {
    private static final Logger log = LoggerFactory.getLogger(UserPublicationFacade.class);

    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final EffectiveAuthorshipReadService effectiveAuthorshipReadService;

    public Optional<UserPublicationsViewModel> buildUserPublicationsView(String userEmail) {
        long startedAtNanos = System.nanoTime();
        log.info("User publications load started: userEmail={}", userEmail);

        long publicationsFetchStartedAtNanos = System.nanoTime();
        List<ScholardexPublicationView> publications = dedupeAndSortPublications(
                effectiveAuthorshipReadService.findEffectivePublicationsForUser(userEmail)
        );
        long publicationsFetchMs = nanosToMillis(System.nanoTime() - publicationsFetchStartedAtNanos);

        long relatedLookupStartedAtNanos = System.nanoTime();
        UserPublicationsViewModel viewModel = buildPublicationsViewModel(publications);
        long relatedLookupMs = nanosToMillis(System.nanoTime() - relatedLookupStartedAtNanos);

        long totalMs = nanosToMillis(System.nanoTime() - startedAtNanos);
        log.info("User publications load finished: userEmail={} publications={} forums={} citations={} timingsMs[publicationsFetch={}, relatedLookup={}, total={}]",
                userEmail,
                publications.size(),
                viewModel.forumMap().size(),
                viewModel.numCitations(),
                publicationsFetchMs,
                relatedLookupMs,
                totalMs);

        return Optional.of(viewModel);
    }

    public Optional<UserPublicationsViewModel> buildAuthorPublicationsView(String authorId) {
        Optional<ScholardexAuthorView> author = scholardexProjectionReadService.findAuthorById(authorId);
        if (author.isEmpty()) {
            return Optional.empty();
        }

        ScholardexAuthorView theAuthor = author.get();
        List<ScholardexPublicationView> publications = dedupeAndSortPublications(
                scholardexProjectionReadService.findAllPublicationsByAuthorsContaining(theAuthor.getId())
        );

        List<String> affiliationIds = theAuthor.getAffiliations() != null
                ? theAuthor.getAffiliations().stream()
                    .map(ScholardexAffiliationView::getAfid)
                    .filter(Objects::nonNull)
                    .toList()
                : List.of();
        List<ScholardexAffiliationView> affiliations = scholardexProjectionReadService.findAffiliationsByIdIn(affiliationIds);

        UserPublicationsViewModel baseVm = buildPublicationsViewModel(publications);
        return Optional.of(new UserPublicationsViewModel(
                baseVm.publications(), baseVm.hIndex(), baseVm.authorMap(), baseVm.forumMap(),
                baseVm.numCitations(), theAuthor, affiliations
        ));
    }

    public Optional<UserPublicationCitationsViewModel> buildCitationsView(String userEmail, String publicationId) {
        Optional<ScholardexPublicationView> byId = scholardexProjectionReadService.findPublicationByAnyId(publicationId);
        if (byId.isEmpty()) {
            return Optional.empty();
        }

        ScholardexPublicationView publication = byId.get();
        if (!effectiveAuthorshipReadService.userEffectivelyOwnsPublication(userEmail, publication.getId())) {
            return Optional.empty();
        }
        List<ScholardexCitationView> allByCited = scholardexProjectionReadService.findAllCitationsByCitedId(publication.getId());
        List<String> citations = new ArrayList<>();
        allByCited.forEach(c -> citations.add(c.getCitingId()));
        List<ScholardexPublicationView> citationsPub = new ArrayList<>(scholardexProjectionReadService.findAllPublicationsByIdIn(citations));
        PublicationOrderingSupport.sortPublicationsInPlace(citationsPub);

        Optional<ScholardexForumView> forumOpt = scholardexProjectionReadService.findForumById(publication.getForum());
        if (forumOpt.isEmpty()) {
            return Optional.empty();
        }

        Set<String> authorKeys = new HashSet<>(publication.getAuthors());
        Set<String> forumKeys = new HashSet<>();
        citationsPub.forEach(p -> forumKeys.add(p.getForum()));

        List<ScholardexAuthorView> byIdIn = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys);
        List<ScholardexForumView> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);

        Map<String, ScholardexAuthorView> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, ScholardexForumView> forumMap = new HashMap<>();
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
    public Optional<ScholardexPublicationView> findPublicationForEdit(String publicationId) {
        return scholardexProjectionReadService.findPublicationByAnyId(publicationId);
    }

    // Uses canonical Mongo `id`; EID-based lookup belongs to importer/scopus integration paths.
    public void updatePublicationMetadata(String publicationId, PublicationMetadataPatch patch) {
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

    private int computeHIndex(List<ScholardexPublicationView> publications) {
        int n = publications.size();
        int[] citationCounts = new int[n + 1];

        for (ScholardexPublicationView pub : publications) {
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

    private List<ScholardexPublicationView> dedupeAndSortPublications(List<ScholardexPublicationView> publications) {
        Map<String, ScholardexPublicationView> dedupedPublicationsById = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : publications) {
            if (publication.getId() == null || publication.getId().isBlank()) {
                continue;
            }
            dedupedPublicationsById.putIfAbsent(publication.getId(), publication);
        }
        List<ScholardexPublicationView> deduped = new ArrayList<>(dedupedPublicationsById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(deduped);
        return deduped;
    }

    private UserPublicationsViewModel buildPublicationsViewModel(List<ScholardexPublicationView> publications) {
        int hIndex = computeHIndex(publications);

        Set<String> authorKeys = new HashSet<>();
        Set<String> forumKeys = new HashSet<>();
        AtomicInteger numCitations = new AtomicInteger();
        publications.forEach(p -> {
            authorKeys.addAll(p.getAuthors());
            forumKeys.add(p.getForum());
            numCitations.addAndGet(p.getCitedbyCount());
        });

        List<ScholardexAuthorView> byIdIn = scholardexProjectionReadService.findAuthorsByIdIn(authorKeys);
        Map<String, ScholardexAuthorView> authorMap = new HashMap<>();
        byIdIn.forEach(a -> authorMap.put(a.getId(), a));

        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        List<ScholardexForumView> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);
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
