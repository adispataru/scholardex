package ro.uvt.pokedex.core.service.derivation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexInstitutionFact;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final ScopusAuthorFactRepository scopusAuthorFactRepository;
    private final CanonicalGraphBuilder graphBuilder;
    private final ScholardexSourceLinkService sourceLinkService;
    private final MongoTemplate mongoTemplate;

    /**
     * The whole V2 canonical derivation (Stage 2 so far): affiliations → publications → authors, from source facts,
     * with bulk writes. Forums are assumed already built by the existing engine (an input); edges + citations follow.
     * Validate by invariants + spot-checks + "run it and look" (the DB is disposable; no V1-parity gate).
     */
    public void rebuildCanonicalV2() {
        long startedAt = System.currentTimeMillis();
        log.info("V2 canonical derivation starting (affiliations -> publications -> authors)");
        rebuildAffiliationsV2();
        rebuildPublicationsV2();
        rebuildAuthorsV2();
        log.info("V2 canonical derivation complete in {} ms", System.currentTimeMillis() - startedAt);
    }

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

    /**
     * Derive {@code scholardex.publication_facts} + PUBLICATION source-links from Scopus + OpenAlex pub source-facts.
     * forumId/affiliationIds/authorIds are filled by later derivation steps (forums already built; authors next).
     */
    public void rebuildPublicationsV2() {
        List<ScopusPublicationFact> scopusPubs = scopusPublicationFactRepository.findAll();
        List<OpenAlexPublicationFact> openAlexPubs = openAlexPublicationFactRepository.findAll();

        CanonicalGraphBuilder.PublicationBuildResult result = graphBuilder.buildPublications(scopusPubs, openAlexPubs);

        mongoTemplate.remove(new Query(), ScholardexPublicationFact.class);
        mongoTemplate.remove(
                Query.query(Criteria.where("entityType").is(ScholardexEntityType.PUBLICATION)), ScholardexSourceLink.class);
        for (int i = 0; i < result.facts().size(); i += 5000) {
            mongoTemplate.insert(result.facts().subList(i, Math.min(i + 5000, result.facts().size())), ScholardexPublicationFact.class);
        }
        if (!result.sourceLinks().isEmpty()) {
            sourceLinkService.batchUpsertWithState(result.sourceLinks(), new HashMap<>(), false);
        }
        log.info("V2 publication derivation: scopusPubs={} openAlexPubs={} -> facts={} sourceLinks={}",
                scopusPubs.size(), openAlexPubs.size(), result.facts().size(), result.sourceLinks().size());
    }

    /**
     * Derive {@code scholardex.author_facts} (core identity union-find: ORCID/OpenAlex-keyed, positional bridge),
     * the AUTHOR source-links, and back-fill {@code pub.authorIds[]} onto the already-written publication facts.
     */
    public void rebuildAuthorsV2() {
        List<ScopusAuthorFact> scopusAuthors = scopusAuthorFactRepository.findAll();
        List<ScopusPublicationFact> scopusPubs = scopusPublicationFactRepository.findAll();
        List<OpenAlexPublicationFact> openAlexPubs = openAlexPublicationFactRepository.findAll();

        CanonicalGraphBuilder.AuthorBuildResult result =
                graphBuilder.buildAuthors(scopusAuthors, scopusPubs, openAlexPubs);

        mongoTemplate.remove(new Query(), ScholardexAuthorFact.class);
        mongoTemplate.remove(
                Query.query(Criteria.where("entityType").is(ScholardexEntityType.AUTHOR)), ScholardexSourceLink.class);
        for (int i = 0; i < result.authors().size(); i += 5000) {
            mongoTemplate.insert(result.authors().subList(i, Math.min(i + 5000, result.authors().size())), ScholardexAuthorFact.class);
        }
        if (!result.sourceLinks().isEmpty()) {
            sourceLinkService.batchUpsertWithState(result.sourceLinks(), new HashMap<>(), false);
        }
        backfillPublicationAuthorIds(result.pubAuthorIds());
        log.info("V2 author derivation: scopusAuthors={} -> authors={} sourceLinks={} pubsWithAuthors={}",
                scopusAuthors.size(), result.authors().size(), result.sourceLinks().size(), result.pubAuthorIds().size());
    }

    /** Bulk-set {@code authorIds[]} on the canonical pubs (one unordered bulk op, no per-pub round trip). */
    private void backfillPublicationAuthorIds(Map<String, List<String>> pubAuthorIds) {
        if (pubAuthorIds.isEmpty()) {
            return;
        }
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, ScholardexPublicationFact.class);
        pubAuthorIds.forEach((pubId, authorIds) ->
                bulk.updateOne(Query.query(Criteria.where("_id").is(pubId)), new Update().set("authorIds", authorIds)));
        bulk.execute();
    }
}
