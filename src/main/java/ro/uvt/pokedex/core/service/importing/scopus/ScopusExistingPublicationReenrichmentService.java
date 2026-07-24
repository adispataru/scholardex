package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionDirtyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H82 — heal-path for already-imported publications. A FULL Scopus author sync used to be a no-op for
 * existing pubs: the ingestion layer dedupes identical payloads ("Imported 0 items"), so the
 * canonicalization that would claim Scopus-precedence fields never re-ran, stranding every merge-rule
 * improvement (e.g. the {@code 069153f3} coverDate precedence) until a full derived-data rebuild.
 *
 * <p><b>Narrow-first by design</b> (user decision 2026-07-24): only the precedence-governed fields are
 * re-claimed — {@code coverDate}/{@code coverDisplayDate}, where the Scopus issue date beats OpenAlex's
 * first-online date. Broad re-enrichment (title/authors/forum) is deliberately out: it risks re-clobbering
 * DBLP-evidence forums and admin corrections (the OpenAlex-refresh clobber incidents).</p>
 *
 * <p>Changed pubs are dirty-marked with their own {@code sourceBatchId} so the projection dirty service
 * can refresh the Postgres read model via its per-batch partial path.</p>
 */
@Service
@RequiredArgsConstructor
public class ScopusExistingPublicationReenrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ScopusExistingPublicationReenrichmentService.class);
    private static final int BATCH = 200;

    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ScholardexProjectionDirtyService projectionDirtyService;

    /** @return number of canonical publications whose precedence fields actually changed. */
    public int reclaimPrecedenceFields(Collection<String> eids, String reason) {
        if (eids == null || eids.isEmpty()) {
            return 0;
        }
        List<String> distinct = eids.stream().filter(e -> e != null && !e.isBlank()).distinct().toList();
        int changed = 0;
        for (int from = 0; from < distinct.size(); from += BATCH) {
            List<String> slice = distinct.subList(from, Math.min(from + BATCH, distinct.size()));
            Map<String, ScopusPublicationFact> scopusByEid = new HashMap<>();
            scopusPublicationFactRepository.findByEidIn(slice)
                    .forEach(f -> scopusByEid.putIfAbsent(f.getEid(), f));
            List<ScholardexPublicationFact> toSave = new ArrayList<>();
            for (ScholardexPublicationFact canonical : scholardexPublicationFactRepository.findAllByEidIn(slice)) {
                ScopusPublicationFact scopus = scopusByEid.get(canonical.getEid());
                if (scopus == null || scopus.getCoverDate() == null) {
                    continue;
                }
                boolean dateDiffers = !scopus.getCoverDate().equals(canonical.getCoverDate());
                if (!dateDiffers) {
                    continue;
                }
                canonical.setCoverDate(scopus.getCoverDate());
                canonical.setCoverDisplayDate(scopus.getCoverDisplayDate());
                canonical.setUpdatedAt(Instant.now());
                toSave.add(canonical);
                projectionDirtyService.markDirty(
                        ScholardexEntityType.PUBLICATION,
                        canonical.getId(),
                        canonical.getSourceBatchId(),
                        null,
                        null,
                        reason
                );
            }
            if (!toSave.isEmpty()) {
                scholardexPublicationFactRepository.saveAll(toSave);
                changed += toSave.size();
            }
        }
        if (changed > 0) {
            log.info("H82 re-enrichment: {} existing publication(s) re-claimed Scopus precedence fields ({})",
                    changed, reason);
        }
        return changed;
    }
}
