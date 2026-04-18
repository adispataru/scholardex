package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.Researcher;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.PublicationAuthorshipReviewState;
import ro.uvt.pokedex.core.service.application.model.SuspiciousAuthorshipState;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SuspiciousAuthorshipTriageService {

    private final UserService userService;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationFactRepository;

    public Map<String, SuspiciousAuthorshipState> evaluatePendingSuspiciousAuthorship(
            String userEmail,
            List<ScholardexPublicationView> publications,
            Map<String, PublicationAuthorshipReviewState> reviewStateByPublicationId,
            Map<String, ScholardexAuthorView> publicationAuthorMap
    ) {
        Optional<User> userOpt = userService.getUserByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return Map.of();
        }

        Researcher researcher = Researcher.fromUser(userOpt.get());
        if (researcher == null) {
            return Map.of();
        }

        List<ScholardexAuthorView> researcherAuthors = scholardexProjectionReadService.findAuthorsByIdIn(
                researcherAuthorLookupService.resolveAuthorLookupKeys(researcher)
        );
        Set<String> researcherAuthorIds = researcherAuthors.stream()
                .map(ScholardexAuthorView::getId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        Set<String> researcherAffiliationScopeIds = researcherAffiliationScopeIds(researcher);
        boolean affiliationScopeConfirmationRequired = requiresAffiliationScopeConfirmation(researcher);
        Map<String, Map<String, Set<String>>> publicationAuthorAffiliationIds = preloadPublicationAuthorAffiliationIds(publications);

        Map<String, SuspiciousAuthorshipState> suspiciousByPublicationId = new LinkedHashMap<>();
        for (ScholardexPublicationView publication : publications) {
            if (publication == null || publication.getId() == null) {
                continue;
            }
            PublicationAuthorshipReviewState reviewState = reviewStateByPublicationId.get(publication.getId());
            if (reviewState != null && reviewState.status() != PublicationAuthorshipReviewState.Status.PENDING) {
                continue;
            }

            List<SuspiciousAuthorshipState.Flag> flags = new ArrayList<>();
            List<ScholardexAuthorView> matchedPublicationAuthors = safeList(publication.getAuthors()).stream()
                    .filter(researcherAuthorIds::contains)
                    .map(publicationAuthorMap::get)
                    .filter(Objects::nonNull)
                    .toList();

            if (isNameMismatch(researcher, matchedPublicationAuthors)) {
                flags.add(new SuspiciousAuthorshipState.Flag(
                        SuspiciousAuthorshipState.Code.NAME_MISMATCH,
                        "Matched author names do not align with your researcher profile."
                ));
            }
            if (hasAffiliationScopeMismatch(
                    publication,
                    matchedPublicationAuthors,
                    researcherAffiliationScopeIds,
                    publicationAuthorAffiliationIds.getOrDefault(publication.getId(), Map.of()),
                    affiliationScopeConfirmationRequired
            )) {
                flags.add(new SuspiciousAuthorshipState.Flag(
                        SuspiciousAuthorshipState.Code.AFFILIATION_SCOPE_MISMATCH,
                        "This publication is linked through affiliations outside your confirmed researcher affiliation scope."
                ));
            }
            if (isSecondaryIdOnly(researcher, publication, researcherAuthorIds)) {
                flags.add(new SuspiciousAuthorshipState.Flag(
                        SuspiciousAuthorshipState.Code.SECONDARY_ID_ONLY,
                        "This publication is linked through a secondary author identity, not your primary ScholarDex author."
                ));
            }

            if (!flags.isEmpty()) {
                suspiciousByPublicationId.put(publication.getId(), new SuspiciousAuthorshipState(flags));
            }
        }
        return suspiciousByPublicationId;
    }

    private boolean isNameMismatch(Researcher researcher, List<ScholardexAuthorView> matchedPublicationAuthors) {
        if (matchedPublicationAuthors.isEmpty()) {
            return false;
        }
        for (ScholardexAuthorView matchedAuthor : matchedPublicationAuthors) {
            if (namesLookLikeSamePerson(researcher, matchedAuthor.getName())
                    || safeList(matchedAuthor.getAlternativeNames()).stream().anyMatch(name -> namesLookLikeSamePerson(researcher, name))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAffiliationScopeMismatch(ScholardexPublicationView publication,
                                                List<ScholardexAuthorView> matchedPublicationAuthors,
                                                Set<String> researcherAffiliationIds,
                                                Map<String, Set<String>> authorAffiliationIdsByAuthorId,
                                                boolean affiliationScopeConfirmationRequired) {
        if (affiliationScopeConfirmationRequired || matchedPublicationAuthors.isEmpty()) {
            return false;
        }
        for (ScholardexAuthorView matchedAuthor : matchedPublicationAuthors) {
            Set<String> paperSpecificAffiliationIds = authorAffiliationIdsByAuthorId.get(matchedAuthor.getId());
            if (paperSpecificAffiliationIds != null && !paperSpecificAffiliationIds.isEmpty()) {
                if (overlaps(paperSpecificAffiliationIds, researcherAffiliationIds)) {
                    return false;
                }
                return true;
            }
        }
        Set<String> publicationAffiliationIds = new LinkedHashSet<>(safeList(publication.getAffiliations()));
        publicationAffiliationIds.removeIf(id -> id == null || id.isBlank());
        if (publicationAffiliationIds.isEmpty()) {
            return false;
        }
        return !overlaps(publicationAffiliationIds, researcherAffiliationIds);
    }

    private boolean isSecondaryIdOnly(Researcher researcher,
                                      ScholardexPublicationView publication,
                                      Set<String> researcherAuthorIds) {
        String primaryAuthorId = researcher.getPrimaryScholardexAuthorId();
        if (primaryAuthorId == null || primaryAuthorId.isBlank()) {
            return false;
        }
        List<String> publicationAuthorIds = safeList(publication.getAuthors());
        if (publicationAuthorIds.contains(primaryAuthorId)) {
            return false;
        }
        return publicationAuthorIds.stream().anyMatch(researcherAuthorIds::contains);
    }

    private boolean namesLookLikeSamePerson(Researcher researcher, String candidateName) {
        if (candidateName == null || candidateName.isBlank()) {
            return false;
        }
        NameParts researcherParts = NameParts.from(researcher.getFirstName(), researcher.getLastName());
        NameParts candidateParts = NameParts.from(candidateName);
        if (researcherParts.familyName().isBlank() || candidateParts.familyName().isBlank()) {
            return false;
        }
        if (!familyNamesAgree(researcherParts.familyName(), candidateParts.familyName())) {
            return false;
        }
        return givenNamesAgree(researcherParts.givenName(), candidateParts.givenName());
    }

    private boolean familyNamesAgree(String left, String right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private boolean givenNamesAgree(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equals(right) || left.charAt(0) == right.charAt(0);
    }

    private boolean overlaps(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private Set<String> researcherAffiliationScopeIds(Researcher researcher) {
        LinkedHashSet<String> affiliationIds = new LinkedHashSet<>();
        safeList(researcher.getCurrentAffiliationIds()).stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .forEach(affiliationIds::add);
        safeList(researcher.getPastAffiliationIds()).stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .forEach(affiliationIds::add);
        return affiliationIds;
    }

    private boolean requiresAffiliationScopeConfirmation(Researcher researcher) {
        boolean hasLinkedIdentity = researcher.getPrimaryScholardexAuthorId() != null
                && !researcher.getPrimaryScholardexAuthorId().isBlank();
        hasLinkedIdentity = hasLinkedIdentity
                || safeList(researcher.getScopusId()).stream().anyMatch(id -> id != null && !id.isBlank())
                || safeList(researcher.getWosId()).stream().anyMatch(id -> id != null && !id.isBlank());
        return hasLinkedIdentity && researcher.getAffiliationsConfirmedAt() == null;
    }

    private Map<String, Map<String, Set<String>>> preloadPublicationAuthorAffiliationIds(List<ScholardexPublicationView> publications) {
        List<String> publicationIds = (publications == null ? List.<ScholardexPublicationView>of() : publications).stream()
                .map(ScholardexPublicationView::getId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
        if (publicationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Set<String>>> byPublicationId = new LinkedHashMap<>();
        List<ScholardexPublicationAuthorAffiliationFact> edges = publicationAuthorAffiliationFactRepository.findByPublicationIdIn(publicationIds);
        if (edges == null || edges.isEmpty()) {
            return Map.of();
        }
        for (ScholardexPublicationAuthorAffiliationFact edge : edges) {
            if (edge.getPublicationId() == null || edge.getAuthorId() == null || edge.getAffiliationId() == null) {
                continue;
            }
            byPublicationId
                    .computeIfAbsent(edge.getPublicationId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(edge.getAuthorId(), ignored -> new LinkedHashSet<>())
                    .add(edge.getAffiliationId());
        }
        return byPublicationId;
    }

    private record NameParts(String givenName, String familyName) {
        private static NameParts from(String firstName, String lastName) {
            return new NameParts(normalize(firstName), normalize(lastName));
        }

        private static NameParts from(String fullName) {
            String raw = fullName == null ? "" : fullName.trim();
            if (raw.contains(",")) {
                String[] parts = raw.split(",", 2);
                return new NameParts(normalize(parts.length > 1 ? parts[1] : ""), normalize(parts[0]));
            }
            List<String> tokens = tokenize(fullName);
            if (tokens.isEmpty()) {
                return new NameParts("", "");
            }
            if (tokens.size() == 1) {
                return new NameParts(tokens.get(0), tokens.get(0));
            }
            return new NameParts(tokens.get(0), tokens.get(tokens.size() - 1));
        }

        private static List<String> tokenize(String value) {
            String normalized = normalize(value);
            if (normalized.isBlank()) {
                return List.of();
            }
            return List.of(normalized.split("\\s+"));
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
            return decomposed
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }
}
