package ro.uvt.pokedex.core.service.derivation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

import java.util.HashMap;
import java.util.List;

/**
 * H75 — the V2 canonical derivation engine (batch ETL): Load (source facts → memory) → Build (pure in-memory graph
 * via {@link CanonicalGraphBuilder}) → Write (wipe + bulk insert). Built alongside V1, behind no production wiring yet
 * (invoked by the differential tests); it grows one entity at a time, each gated on byte-parity with V1.
 *
 * <p>Stage 1.a: {@link #rebuildAffiliationsV2()} (the ROR backbone + Scopus afid resolution). The inline load/write
 * here are extracted into {@code CanonicalSourceLoader} / {@code BulkCanonicalWriter} when forums + pubs (S1.b/c)
 * start sharing them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalDerivationV2Service {

    private final OpenAlexInstitutionFactRepository institutionFactRepository;
    private final ScopusAffiliationFactRepository scopusAffiliationFactRepository;
    private final CanonicalGraphBuilder graphBuilder;
    private final ScholardexSourceLinkService sourceLinkService;
    private final MongoTemplate mongoTemplate;

    /** Derive {@code scholardex.affiliation_facts} + their AFFILIATION source-links from source facts, V2-style. */
    public void rebuildAffiliationsV2() {
        // Load
        List<OpenAlexInstitutionFact> institutions = institutionFactRepository.findAll();
        List<ScopusAffiliationFact> scopusAffiliations = scopusAffiliationFactRepository.findAll();

        // Build (pure, in-memory)
        CanonicalGraphBuilder.AffiliationBuildResult result =
                graphBuilder.buildAffiliations(institutions, scopusAffiliations);

        // Write: wipe what this build owns, then bulk-insert. A wiped collection means inserts (no upsert checks).
        mongoTemplate.remove(new Query(), ScholardexAffiliationFact.class);
        mongoTemplate.remove(
                Query.query(Criteria.where("entityType").is(ScholardexEntityType.AFFILIATION)), ScholardexSourceLink.class);
        if (!result.facts().isEmpty()) {
            mongoTemplate.insert(result.facts(), ScholardexAffiliationFact.class);
        }
        if (!result.sourceLinks().isEmpty()) {
            // Clean slate -> empty preload + allowFallbackLookup=false = pure inserts, no per-command existence reads.
            sourceLinkService.batchUpsertWithState(result.sourceLinks(), new HashMap<>(), false);
        }
        log.info("V2 affiliation derivation: institutions={} scopusAffiliations={} -> facts={} sourceLinks={}",
                institutions.size(), scopusAffiliations.size(), result.facts().size(), result.sourceLinks().size());
    }
}
