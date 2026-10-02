package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.repository.reporting.DomainRepository;

import java.util.List;
import java.util.Optional;

/**
 * H138 — keeps one {@code Domain} document per competition domain of {@link UefiscdiCompetitionDomains} in the
 * database, reconciled at startup from the bundled catalog (insert the missing ones, bring the category list and
 * the description of the others to the committed state; never deletes). The eligibility reports score their
 * indicators against the domain the researcher chose, so the documents must exist on every environment without a
 * data script — same spirit as the FEAA Anexa 1 publisher list.
 */
@Service
public class UefiscdiDomainCatalogService {

    private static final Logger log = LoggerFactory.getLogger(UefiscdiDomainCatalogService.class);

    private final DomainRepository domainRepository;

    public UefiscdiDomainCatalogService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    @PostConstruct
    void init() {
        try {
            reconcile();
        } catch (DataAccessException e) {
            // context-load smoke tests and a pod starting before Mongo: warn, reconcile on first use
            log.warn("UEFISCDI competition domains not reconcilable (database unreachable): {}", e.getMessage());
        }
    }

    /** The domain a domain-selectable report's journal indicators carry as their stored base: no category qualifies. */
    public static final String NO_DOMAIN_ID = UefiscdiCompetitionDomains.DOMAIN_ID_PREFIX + "(fără domeniu ales)";

    /** Inserts or updates the 13 documents plus the empty "no domain chosen" one; returns how many were written. */
    public int reconcile() {
        int written = 0;
        if (domainRepository.findById(NO_DOMAIN_ID).isEmpty()) {
            Domain none = new Domain();
            none.setId(NO_DOMAIN_ID);
            none.setName(NO_DOMAIN_ID);
            none.setDescription("H138: the stored base domain of a domain-selectable report's journal indicators — "
                    + "nothing qualifies until the researcher chooses a competition domain");
            none.setWosCategories(List.of());
            domainRepository.save(none);
            written++;
        }
        for (UefiscdiCompetitionDomains.CompetitionDomain cd : UefiscdiCompetitionDomains.all()) {
            String description = "PN-IV PD/TE 2026, Anexa 1, domeniul " + cd.code() + " (" + cd.ercPanels() + "); "
                    + "familia " + cd.family() + ", autor principal " + cd.principalAuthorRule()
                    + "; categoriile Web of Science în lectura platformei (H138)";
            Optional<Domain> existing = domainRepository.findById(cd.domainId());
            if (existing.isPresent()
                    && cd.wosCategories().equals(existing.get().getWosCategories())
                    && description.equals(existing.get().getDescription())) {
                continue;
            }
            Domain domain = existing.orElseGet(Domain::new);
            domain.setId(cd.domainId());
            domain.setName(cd.domainId());
            domain.setDescription(description);
            domain.setWosCategories(List.copyOf(cd.wosCategories()));
            domainRepository.save(domain);
            written++;
        }
        log.info("UEFISCDI competition domains reconciled: {} of {} written", written, UefiscdiCompetitionDomains.all().size());
        return written;
    }

    /** The {@code Domain} of a competition domain, reconciling first if it is missing. */
    public Optional<Domain> domainOf(int code) {
        Optional<UefiscdiCompetitionDomains.CompetitionDomain> cd = UefiscdiCompetitionDomains.byCode(code);
        if (cd.isEmpty()) {
            return Optional.empty();
        }
        Optional<Domain> domain = domainRepository.findById(cd.get().domainId());
        if (domain.isEmpty()) {
            reconcile();
            domain = domainRepository.findById(cd.get().domainId());
        }
        return domain;
    }
}
