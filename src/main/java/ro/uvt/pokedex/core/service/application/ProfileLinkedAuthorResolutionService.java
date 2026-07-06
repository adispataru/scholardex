package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.service.openalex.OrcidSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the canonical authors a researcher profile is ALREADY linked to — by identifier only
 * (Scopus/WoS/Scholar lookup keys plus ORCID), never by name. This is the resolution basis for
 * provisional org-unit scoring: it answers "which canonical authors do this person's linked ids
 * point at today", without requiring the researcher to have confirmed any publications.
 */
@Service
@RequiredArgsConstructor
public class ProfileLinkedAuthorResolutionService {

    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ScholardexAuthorFactRepository scholardexAuthorFactRepository;

    /** Distinct canonical author ids linked via profile identifiers; empty when nothing is linked. */
    public List<String> resolveCanonicalAuthorIds(User.ResearcherProfile profile) {
        if (profile == null) {
            return List.of();
        }
        Set<String> canonicalIds = new LinkedHashSet<>();
        List<String> lookupKeys = researcherAuthorLookupService.resolveAuthorLookupKeys(profile);
        if (!lookupKeys.isEmpty()) {
            scholardexProjectionReadService.findAuthorsByIdIn(lookupKeys).stream()
                    .map(ScholardexAuthorView::getId)
                    .filter(Objects::nonNull)
                    .forEach(canonicalIds::add);
        }
        String orcid = OrcidSupport.normalize(profile.getOrcid());
        if (orcid != null) {
            // An ORCID can transiently sit on several author facts (pre-reconcile duplicates) —
            // treat every match as a linked author rather than guessing which one is right.
            scholardexAuthorFactRepository.findByOrcidIdsContains(orcid).stream()
                    .map(ScholardexAuthorFact::getId)
                    .filter(Objects::nonNull)
                    .forEach(canonicalIds::add);
        }
        return new ArrayList<>(canonicalIds);
    }
}
