package ro.uvt.pokedex.core.service.application;

import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAuthorFactRepository;
import ro.uvt.pokedex.core.service.openalex.OrcidSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ResearcherAuthorLookupService {

    private final ScholardexAuthorFactRepository authorFactRepository;

    public ResearcherAuthorLookupService(ScholardexAuthorFactRepository authorFactRepository) {
        this.authorFactRepository = authorFactRepository;
    }

    public List<String> resolveAuthorLookupKeys(User.ResearcherProfile profile) {
        if (profile == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, profile.getPrimaryScholardexAuthorId());
        addAll(keys, profile.getConfirmedScholardexAuthorIds());
        addAll(keys, profile.getScopusId());
        addAll(keys, profile.getWosId());
        add(keys, profile.getScholarId());
        // ORCID has no source_links rows (it is a merged attribute, not a source system), so an ORCID
        // can't ride the canonical-id resolution the other keys use. Resolve it here against the author
        // facts' orcidIds instead — an ORCID-only profile must still find its author record(s).
        addAll(keys, resolveAuthorIdsByOrcid(profile.getOrcid()));
        return new ArrayList<>(keys);
    }

    private List<String> resolveAuthorIdsByOrcid(String rawOrcid) {
        String orcid = OrcidSupport.normalize(rawOrcid);
        if (orcid == null) {
            return List.of();
        }
        // A duplicate/not-yet-merged situation can transiently put one ORCID on several author records —
        // include them all; the author-match step (and effective-authorship union) handles multiples.
        return authorFactRepository.findByOrcidIdsContains(orcid).stream()
                .map(ScholardexAuthorFact::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private void addAll(LinkedHashSet<String> keys, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            add(keys, value);
        }
    }

    private void add(LinkedHashSet<String> keys, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty()) {
            keys.add(normalized);
        }
    }
}
