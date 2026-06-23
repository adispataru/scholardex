package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.PublicationMetadataPatch;
import ro.uvt.pokedex.core.service.application.model.SuspiciousAuthorshipState;
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
    private final PublicationAuthorshipDecisionService publicationAuthorshipDecisionService;
    private final SuspiciousAuthorshipTriageService suspiciousAuthorshipTriageService;
    private final ScholardexSourceLinkService scholardexSourceLinkService;

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

    public Optional<UserPublicationsViewModel> buildWorkspacePublicationsView(String userEmail) {
        long startedAtNanos = System.nanoTime();
        log.info("Workspace publications load started: userEmail={}", userEmail);

        long publicationsFetchStartedAtNanos = System.nanoTime();
        List<ScholardexPublicationView> publications = dedupeAndSortPublications(
                effectiveAuthorshipReadService.findWorkspaceReviewPublicationsForUser(userEmail)
        );
        long publicationsFetchMs = nanosToMillis(System.nanoTime() - publicationsFetchStartedAtNanos);

        long relatedLookupStartedAtNanos = System.nanoTime();
        Map<String, PublicationAuthorshipReviewState> reviewStateByPublicationId =
                buildReviewStateByPublicationId(userEmail, publications);
        UserPublicationsViewModel viewModel = buildPublicationsViewModel(publications, reviewStateByPublicationId, userEmail);
        long relatedLookupMs = nanosToMillis(System.nanoTime() - relatedLookupStartedAtNanos);

        long totalMs = nanosToMillis(System.nanoTime() - startedAtNanos);
        log.info("Workspace publications load finished: userEmail={} publications={} forums={} citations={} timingsMs[publicationsFetch={}, relatedLookup={}, total={}]",
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

        List<String> affiliationIds = theAuthor.getAffiliationIds() != null ? theAuthor.getAffiliationIds() : List.of();
        List<ScholardexAffiliationView> affiliations = scholardexProjectionReadService.findAffiliationsByIdIn(affiliationIds);

        UserPublicationsViewModel baseVm = buildPublicationsViewModel(publications);
        return Optional.of(new UserPublicationsViewModel(
                baseVm.publications(), baseVm.hIndex(), baseVm.authorMap(), baseVm.forumMap(), baseVm.authorshipReviewStateByPublicationId(),
                baseVm.suspiciousAuthorshipByPublicationId(), baseVm.pendingReviewCount(), baseVm.suspiciousPendingCount(),
                baseVm.recommendedPendingCount(),
                baseVm.numCitations(), theAuthor, affiliations, baseVm.hIndices()
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

    private void aliasAuthorsBySourceIds(Map<String, ScholardexAuthorView> authorMap, Collection<String> referencedIds) {
        if (authorMap.isEmpty() || referencedIds == null || referencedIds.isEmpty()) {
            return;
        }
        List<ScholardexSourceLink> links = scholardexSourceLinkService.findByEntityTypeAndSourceRecordIds(
                ScholardexEntityType.AUTHOR, referencedIds);
        for (ScholardexSourceLink link : links) {
            String sourceId = link.getSourceRecordId();
            String canonicalId = link.getCanonicalEntityId();
            if (sourceId == null || canonicalId == null || authorMap.containsKey(sourceId)) {
                continue;
            }
            ScholardexAuthorView resolved = authorMap.get(canonicalId);
            if (resolved != null) {
                authorMap.put(sourceId, resolved);
            }
        }
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
        HIndexCalculator.HIndexBreakdown hIndices = HIndexCalculator.breakdown(publications);
        int hIndex = hIndices.scholardex();

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
        aliasAuthorsBySourceIds(authorMap, authorKeys);

        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        List<ScholardexForumView> forums = scholardexProjectionReadService.findForumsByIdIn(forumKeys);
        forums.forEach(f -> forumMap.put(f.getId(), f));

        return new UserPublicationsViewModel(
                publications,
                hIndex,
                authorMap,
                forumMap,
                Map.of(),
                Map.of(),
                0,
                0,
                0,
                numCitations.get(),
                null,
                List.of(),
                hIndices
        );
    }

    private UserPublicationsViewModel buildPublicationsViewModel(List<ScholardexPublicationView> publications,
                                                                 Map<String, PublicationAuthorshipReviewState> reviewStateByPublicationId) {
        return buildPublicationsViewModel(publications, reviewStateByPublicationId, null);
    }

    private UserPublicationsViewModel buildPublicationsViewModel(List<ScholardexPublicationView> publications,
                                                                 Map<String, PublicationAuthorshipReviewState> reviewStateByPublicationId,
                                                                 String userEmail) {
        UserPublicationsViewModel base = buildPublicationsViewModel(publications);
        Map<String, SuspiciousAuthorshipState> suspiciousAuthorshipByPublicationId = userEmail == null || userEmail.isBlank()
                ? Map.of()
                : suspiciousAuthorshipTriageService.evaluatePendingSuspiciousAuthorship(
                        userEmail,
                        publications,
                        reviewStateByPublicationId,
                        base.authorMap()
                );
        int pendingReviewCount = (int) reviewStateByPublicationId.values().stream()
                .filter(state -> state != null && state.status() == PublicationAuthorshipReviewState.Status.PENDING)
                .count();
        int suspiciousPendingCount = (int) suspiciousAuthorshipByPublicationId.keySet().stream()
                .filter(publicationId -> {
                    PublicationAuthorshipReviewState state = reviewStateByPublicationId.get(publicationId);
                    return state != null && state.status() == PublicationAuthorshipReviewState.Status.PENDING;
                })
                .count();
        int recommendedPendingCount = Math.max(0, pendingReviewCount - suspiciousPendingCount);
        return new UserPublicationsViewModel(
                base.publications(),
                base.hIndex(),
                base.authorMap(),
                base.forumMap(),
                reviewStateByPublicationId,
                suspiciousAuthorshipByPublicationId,
                pendingReviewCount,
                suspiciousPendingCount,
                recommendedPendingCount,
                base.numCitations(),
                base.profileAuthor(),
                base.affiliations(),
                base.hIndices()
        );
    }

    private Map<String, PublicationAuthorshipReviewState> buildReviewStateByPublicationId(String userEmail,
                                                                                          List<ScholardexPublicationView> publications) {
        List<String> publicationIds = publications.stream()
                .map(ScholardexPublicationView::getId)
                .filter(Objects::nonNull)
                .toList();
        List<PublicationAuthorshipDecision> decisions =
                publicationAuthorshipDecisionService.findDecisionsForUserAndPublications(userEmail, publicationIds);
        Map<String, PublicationAuthorshipReviewState> stateByPublicationId = new HashMap<>();
        for (ScholardexPublicationView publication : publications) {
            if (publication.getId() == null) {
                continue;
            }
            stateByPublicationId.put(publication.getId(), new PublicationAuthorshipReviewState(
                    PublicationAuthorshipReviewState.Status.PENDING,
                    null,
                    null
            ));
        }
        for (PublicationAuthorshipDecision decision : decisions) {
            if (decision.getPublicationId() == null || decision.getStatus() == null) {
                continue;
            }
            stateByPublicationId.put(decision.getPublicationId(), new PublicationAuthorshipReviewState(
                    toReviewStatus(decision.getStatus()),
                    decision.getReason(),
                    decision.getUpdatedAt()
            ));
        }
        return stateByPublicationId;
    }

    private PublicationAuthorshipReviewState.Status toReviewStatus(PublicationAuthorshipDecision.Status status) {
        return switch (status) {
            case CONFIRMED -> PublicationAuthorshipReviewState.Status.CONFIRMED;
            case REJECTED -> PublicationAuthorshipReviewState.Status.REJECTED;
        };
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
