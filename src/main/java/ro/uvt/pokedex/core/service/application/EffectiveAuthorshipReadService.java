package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.PublicationAuthorshipDecision;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.PublicationAuthorshipDecisionRepository;
import ro.uvt.pokedex.core.service.UserService;

import java.util.ArrayList;
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
        Researcher researcher = Researcher.fromUser(userOpt.get());
        if (researcher == null) {
            return List.of();
        }

        List<ScholardexAuthorView> authors = scholardexProjectionReadService.findAuthorsByIdIn(
                researcherAuthorLookupService.resolveAuthorLookupKeys(researcher)
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

        Set<String> rejectedIds = new LinkedHashSet<>();
        Set<String> confirmedIds = new LinkedHashSet<>();
        for (PublicationAuthorshipDecision decision : decisions) {
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

        Map<String, ScholardexPublicationView> effectiveById = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : rawPublications) {
            if (publication == null || publication.getId() == null || rejectedIds.contains(publication.getId())) {
                continue;
            }
            effectiveById.putIfAbsent(publication.getId(), publication);
        }

        List<String> missingConfirmedIds = confirmedIds.stream()
                .filter(id -> !effectiveById.containsKey(id))
                .toList();
        if (!missingConfirmedIds.isEmpty()) {
            scholardexProjectionReadService.findAllPublicationsByIdIn(missingConfirmedIds).forEach(publication -> {
                if (publication != null && publication.getId() != null && !rejectedIds.contains(publication.getId())) {
                    effectiveById.putIfAbsent(publication.getId(), publication);
                }
            });
        }

        List<ScholardexPublicationView> effective = new ArrayList<>(effectiveById.values());
        PublicationOrderingSupport.sortPublicationsInPlace(effective);
        return effective;
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
}
