package ro.uvt.pokedex.core.service.application.onboarding;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * H70 author-match (wizard step 4) — builds the candidate canonical author records for a researcher, each with
 * a publication-count + affiliation preview. Candidates are the authors that resolve from the researcher's own
 * identifiers (scopus / wos / scholar / already-confirmed), i.e. exactly the records that drive their reports.
 * Name-based discovery is deliberately excluded (names carry artefacts; author-id + affiliation is the signal).
 */
@Service
public class OnboardingAuthorCandidateService {

    private static final int MAX_AFFILIATION_LABELS = 4;

    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;

    public OnboardingAuthorCandidateService(
            ResearcherAuthorLookupService researcherAuthorLookupService,
            ScholardexProjectionReadService scholardexProjectionReadService) {
        this.researcherAuthorLookupService = researcherAuthorLookupService;
        this.scholardexProjectionReadService = scholardexProjectionReadService;
    }

    public List<AuthorCandidate> candidatesFor(User.ResearcherProfile profile) {
        if (profile == null) {
            return List.of();
        }
        List<String> lookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(profile);
        if (lookupKeys.isEmpty()) {
            return List.of();
        }
        Set<String> confirmed = new HashSet<>(profile.getConfirmedScholardexAuthorIds() == null
                ? List.of() : profile.getConfirmedScholardexAuthorIds());

        LinkedHashMap<String, AuthorCandidate> byId = new LinkedHashMap<>();
        for (ScholardexAuthorView author : scholardexProjectionReadService.findAuthorsByIdIn(lookupKeys)) {
            String id = author.getId();
            if (id == null || byId.containsKey(id)) {
                continue;
            }
            int publicationCount = scholardexProjectionReadService.countPublicationsByAuthor(id);
            byId.put(id, new AuthorCandidate(
                    id, author.getName(), publicationCount, affiliationLabels(author), confirmed.contains(id)));
        }

        List<AuthorCandidate> candidates = new ArrayList<>(byId.values());
        // Most-published first — the most likely "this is me" record leads.
        candidates.sort(Comparator.comparingInt(AuthorCandidate::publicationCount).reversed());
        return candidates;
    }

    private List<String> affiliationLabels(ScholardexAuthorView author) {
        // Author affiliations live in the author->affiliation edge table, not the denormalized author view.
        Set<String> affiliationIds = scholardexProjectionReadService.findAffiliationIdsByAuthorId(author.getId());
        if (affiliationIds.isEmpty()) {
            return List.of();
        }
        return scholardexProjectionReadService.findAffiliationsByIdIn(affiliationIds).stream()
                .map(OnboardingAuthorCandidateService::label)
                .limit(MAX_AFFILIATION_LABELS)
                .toList();
    }

    private static String label(ScholardexAffiliationView affiliation) {
        String name = affiliation.getName() != null ? affiliation.getName() : affiliation.getId();
        String loc = java.util.stream.Stream.of(affiliation.getCity(), affiliation.getCountry())
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return loc.isEmpty() ? name : name + " (" + loc + ")";
    }
}
