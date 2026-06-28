package ro.uvt.pokedex.core.service.brainmap;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.UserDefinedProjectFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.UserDefinedProjectFactRepository;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;

import java.time.Instant;
import java.util.List;

/**
 * H64 slice 4b — admin/CORDIS-entered project facts (the trusted-budget source). Upserts into the precious
 * {@code user_defined.project_facts} collection; the canonical layer is re-derived from it on the next project
 * canon rebuild (merge by EU grant id / code, user-defined wins for budget).
 */
@Service
@RequiredArgsConstructor
public class UserDefinedProjectService {

    private static final Logger log = LoggerFactory.getLogger(UserDefinedProjectService.class);
    private static final String BUILDER_VERSION = "user-defined-project-v1";
    private static final String SOURCE = "USER_DEFINED";

    private final UserDefinedProjectFactRepository repository;

    /** Stable id: the EU grant id when present (so re-entry upserts), else a code-derived id, else a content hash. */
    static String idFor(String euGrantId, String code) {
        if (euGrantId != null && !euGrantId.isBlank()) {
            return euGrantId.trim();
        }
        if (code != null && !code.isBlank()) {
            return "udp_" + CanonicalizationSupport.shortHash("code:" + code.trim());
        }
        return "udp_" + CanonicalizationSupport.shortHash("anon:" + System.nanoTime());
    }

    /** Create or update a user-defined project (upsert by id). Preserves createdAt/submitter on update. */
    public UserDefinedProjectFact save(UserDefinedProjectFact incoming, String submitterEmail) {
        String id = incoming.getId() != null && !incoming.getId().isBlank()
                ? incoming.getId()
                : idFor(incoming.getEuGrantId(), incoming.getCode());
        UserDefinedProjectFact fact = repository.findById(id).orElseGet(UserDefinedProjectFact::new);
        boolean isNew = fact.getCreatedAt() == null;

        fact.setId(id);
        fact.setEuGrantId(blankToNull(incoming.getEuGrantId()));
        fact.setCode(blankToNull(incoming.getCode()));
        fact.setTitle(incoming.getTitle());
        fact.setFunder(incoming.getFunder());
        fact.setBudget(incoming.getBudget());
        fact.setCurrency(incoming.getCurrency());
        fact.setDirectorFirst(incoming.getDirectorFirst());
        fact.setDirectorLast(incoming.getDirectorLast());
        fact.setCoordinatorName(incoming.getCoordinatorName());
        fact.setStartYear(incoming.getStartYear());
        fact.setEndYear(incoming.getEndYear());
        fact.setOrigin(incoming.getOrigin() != null ? incoming.getOrigin() : "MANUAL");

        Instant now = Instant.now();
        if (isNew) {
            fact.setCreatedAt(now);
            fact.setSubmitterEmail(submitterEmail);
            fact.setSubmittedAt(now);
            fact.setSource(SOURCE);
            fact.setSourceRecordId(id);
        }
        fact.setUpdatedAt(now);
        fact.setBuilderVersion(BUILDER_VERSION);
        UserDefinedProjectFact saved = repository.save(fact);
        log.info("User-defined project {}: id={} euGrantId={} budget={} origin={}",
                isNew ? "created" : "updated", id, saved.getEuGrantId(), saved.getBudget(), saved.getOrigin());
        return saved;
    }

    public List<UserDefinedProjectFact> findAll() {
        return repository.findAll();
    }

    public UserDefinedProjectFact findById(String id) {
        return repository.findById(id).orElse(null);
    }

    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
