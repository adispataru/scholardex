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
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorshipFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexInstitutionFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScopusCitationFactRepository;
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
    private final ScopusCitationFactRepository scopusCitationFactRepository;
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
        rebuildCitationsV2();
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

        CanonicalGraphBuilder.PublicationBuildResult result =
                graphBuilder.buildPublications(scopusPubs, openAlexPubs, loadPubResolvers());

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

        // Edges (deduped in memory -> clean inserts). Wipe + bulk-insert each collection.
        bulkReplace(result.authorshipEdges(), ScholardexAuthorshipFact.class);
        bulkReplace(result.authorAffiliationEdges(), ScholardexAuthorAffiliationFact.class);
        bulkReplace(result.pubAuthorAffiliationEdges(), ScholardexPublicationAuthorAffiliationFact.class);

        log.info("V2 author derivation: scopusAuthors={} -> authors={} sourceLinks={} pubsWithAuthors={} "
                        + "authorshipEdges={} authorAffEdges={} pubAuthorAffEdges={}",
                scopusAuthors.size(), result.authors().size(), result.sourceLinks().size(), result.pubAuthorIds().size(),
                result.authorshipEdges().size(), result.authorAffiliationEdges().size(),
                result.pubAuthorAffiliationEdges().size());
    }

    /**
     * Build the pub-resolution lookups from the already-built forum registry + V2 affiliations: Scopus forum id +
     * OpenAlex venue id → {@code sforum_}, and afid → {@code saff_}. Forums are the existing engine's output (an
     * input to V2); affiliations were built earlier in {@link #rebuildCanonicalV2()}.
     */
    private CanonicalGraphBuilder.PubResolvers loadPubResolvers() {
        Map<String, String> scopusForumToCanonical = new HashMap<>();
        Map<String, String> openAlexVenueToCanonical = new HashMap<>();
        for (ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact f :
                mongoTemplate.findAll(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact.class)) {
            if (f.getScopusForumIds() != null) {
                f.getScopusForumIds().forEach(id -> scopusForumToCanonical.putIfAbsent(id, f.getId()));
            }
            if (f.getOpenAlexIds() != null) {
                f.getOpenAlexIds().forEach(id -> openAlexVenueToCanonical.putIfAbsent(id, f.getId()));
            }
        }
        Map<String, String> afidToCanonicalAffiliation = new HashMap<>();
        for (ScholardexAffiliationFact a : mongoTemplate.findAll(ScholardexAffiliationFact.class)) {
            if (a.getScopusAffiliationIds() != null) {
                a.getScopusAffiliationIds().forEach(afid -> afidToCanonicalAffiliation.putIfAbsent(afid, a.getId()));
            }
        }
        return new CanonicalGraphBuilder.PubResolvers(
                scopusForumToCanonical, openAlexVenueToCanonical, afidToCanonicalAffiliation);
    }

    /** Derive {@code scholardex.citation_facts}: internal pub→pub edges from OpenAlex referencedWorks + Scopus citations. */
    public void rebuildCitationsV2() {
        List<ScopusPublicationFact> scopusPubs = scopusPublicationFactRepository.findAll();
        List<OpenAlexPublicationFact> openAlexPubs = openAlexPublicationFactRepository.findAll();
        List<ScopusCitationFact> scopusCitations = scopusCitationFactRepository.findAll();

        List<ScholardexCitationFact> edges = graphBuilder.buildCitations(scopusPubs, openAlexPubs, scopusCitations);
        bulkReplace(edges, ScholardexCitationFact.class);
        log.info("V2 citation derivation: openAlexPubs={} scopusCitations={} -> citationEdges={}",
                openAlexPubs.size(), scopusCitations.size(), edges.size());
    }

    /** Wipe a collection and bulk-insert the given facts in 5k batches (no upsert: wipe-first guarantees inserts). */
    private <T> void bulkReplace(List<T> facts, Class<T> type) {
        mongoTemplate.remove(new Query(), type);
        for (int i = 0; i < facts.size(); i += 5000) {
            mongoTemplate.insert(facts.subList(i, Math.min(i + 5000, facts.size())), type);
        }
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
