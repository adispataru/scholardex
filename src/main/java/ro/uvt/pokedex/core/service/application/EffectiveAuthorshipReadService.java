package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EffectiveAuthorshipReadService {

    private final UserService userService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final PublicationAuthorshipDecisionRepository publicationAuthorshipDecisionRepository;

    public List<ScholardexPublicationView> findEffectivePublicationsForUser(String userEmail) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return List.of();
        }
        User user = userOpt.get();
        if (user.getResearcherProfile() == null) {
            return List.of();
        }

        List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile())
        );
        List<String> canonicalAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<ScholardexPublicationView> rawPublications = canonicalAuthorIds.isEmpty()
                ? List.of()
                : scholardexProjectionReadService.findAllPublicationsByAuthorsIn(canonicalAuthorIds);

        List<PublicationAuthorshipDecision> decisions =
                publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc(userEmail);
        if (decisions.isEmpty()) {
            return rawPublications;
        }

        DecisionSets decisionSets = splitDecisionIds(decisions);

        Map<String, ScholardexPublicationView> effectiveById = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : rawPublications) {
            if (publication == null || publication.getId() == null || decisionSets.rejectedIds().contains(publication.getId())) {
                continue;
            }
            effectiveById.putIfAbsent(publication.getId(), publication);
        }

        List<String> missingConfirmedIds = decisionSets.confirmedIds().stream()
                .filter(id -> !effectiveById.containsKey(id))
                .toList();
        if (!missingConfirmedIds.isEmpty()) {
            scholardexProjectionReadService.findAllPublicationsByIdIn(missingConfirmedIds).forEach(publication -> {
                if (publication != null && publication.getId() != null && !decisionSets.rejectedIds().contains(publication.getId())) {
                    effectiveById.putIfAbsent(publication.getId(), publication);
                }
            });
        }

        List<ScholardexPublicationView> effective = new ArrayList<>(effectiveById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(effective);
        return effective;
    }

    public List<ScholardexPublicationView> findConfirmedPublicationsForScoring(String userEmail) {
        List<PublicationAuthorshipDecision> decisions =
                publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc(userEmail);
        if (decisions.isEmpty()) {
            return List.of();
        }

        DecisionSets decisionSets = splitDecisionIds(decisions);
        if (decisionSets.confirmedIds().isEmpty()) {
            return List.of();
        }

        Map<String, ScholardexPublicationView> confirmedById = scholardexProjectionReadService
                .findAllPublicationsByIdIn(decisionSets.confirmedIds())
                .stream()
                .filter(Objects::nonNull)
                .filter(publication -> publication.getId() != null)
                .filter(publication -> !decisionSets.rejectedIds().contains(publication.getId()))
                .collect(LinkedHashMap::new,
                        (map, publication) -> map.putIfAbsent(publication.getId(), publication),
                        LinkedHashMap::putAll);

        List<ScholardexPublicationView> confirmed = new ArrayList<>(confirmedById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(confirmed);
        return confirmed;
    }

    public List<ScholardexPublicationView> findWorkspaceReviewPublicationsForUser(String userEmail) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return List.of();
        }
        User user = userOpt.get();
        if (user.getResearcherProfile() == null) {
            return List.of();
        }

        List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile())
        );
        List<String> canonicalAuthorIds = authors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, ScholardexPublicationView> reviewById = new LinkedHashMap<>();
        if (!canonicalAuthorIds.isEmpty()) {
            scholardexProjectionReadService.findAllPublicationsByAuthorsIn(canonicalAuthorIds).forEach(publication -> {
                if (publication != null && publication.getId() != null) {
                    reviewById.putIfAbsent(publication.getId(), publication);
                }
            });
        }

        List<PublicationAuthorshipDecision> decisions =
                publicationAuthorshipDecisionRepository.findByUserEmailOrderByUpdatedAtDesc(userEmail);
        if (!decisions.isEmpty()) {
            Set<String> decisionPublicationIds = decisions.stream()
                    .map(PublicationAuthorshipDecision::getPublicationId)
                    .filter(Objects::nonNull)
                    .filter(id -> !reviewById.containsKey(id))
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (!decisionPublicationIds.isEmpty()) {
                scholardexProjectionReadService.findAllPublicationsByIdIn(decisionPublicationIds).forEach(publication -> {
                    if (publication != null && publication.getId() != null) {
                        reviewById.putIfAbsent(publication.getId(), publication);
                    }
                });
            }
        }

        List<ScholardexPublicationView> reviewPublications = new ArrayList<>(reviewById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(reviewPublications);
        return reviewPublications;
    }

    public boolean hasConfirmedPublicationsForScoring(String userEmail) {
        return !findConfirmedPublicationsForScoring(userEmail).isEmpty();
    }

    public List<ScoringPublicationReadModel> findEffectiveScoringPublicationsForUser(String userEmail) {
        return findEffectivePublicationsForUser(userEmail).stream()
                .map(ScholardexPublicationView::toScoringPublication)
                .toList();
    }

    public boolean userEffectivelyOwnsPublication(String userEmail, String publicationId) {
        if (publicationId == null || publicationId.isBlank()) {
            return false;
        }
        return findEffectivePublicationsForUser(userEmail).stream()
                .map(ScholardexPublicationView::getId)
                .anyMatch(publicationId::equals);
    }

    private DecisionSets splitDecisionIds(List<PublicationAuthorshipDecision> decisions) {
        List<PublicationAuthorshipDecision> orderedDecisions = decisions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PublicationAuthorshipDecision::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Set<String> rejectedIds = new LinkedHashSet<>();
        Set<String> confirmedIds = new LinkedHashSet<>();
        for (PublicationAuthorshipDecision decision : orderedDecisions) {
            if (decision.getPublicationId() == null || decision.getStatus() == null) {
                continue;
            }
            if (decision.getStatus() == PublicationAuthorshipDecision.Status.REJECTED) {
                rejectedIds.add(decision.getPublicationId());
                confirmedIds.remove(decision.getPublicationId());
            } else if (decision.getStatus() == PublicationAuthorshipDecision.Status.CONFIRMED) {
                confirmedIds.add(decision.getPublicationId());
                rejectedIds.remove(decision.getPublicationId());
            }
        }
        return new DecisionSets(rejectedIds, confirmedIds);
    }

    private record DecisionSets(Set<String> rejectedIds, Set<String> confirmedIds) {
    }
}
