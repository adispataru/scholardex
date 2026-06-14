package ro.uvt.pokedex.core.service.application;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Single guarded entry point for rebuilding all derived data from source (H54.6a).
 *
 * <p>The per-stage rebuild already exists as {@link ScopusBigBangMigrationService} and
 * {@link WosBigBangMigrationService} (ingest source files → ledger → stage-2 facts → stage-3
 * canonical → stage-4 Postgres projections). They were triggered piecemeal from several places;
 * this facade unifies them behind one call and gates every rebuild on the
 * {@link OwnedCollectionRegistry} safety rule so a rebuild can only ever touch collections this app
 * owns (the shared dev Mongo also hosts other apps — see {@code docs/data-ownership-inventory.md}).
 *
 * <p>The existing wipes are repo-typed {@code deleteAll()} (already owned-scoped), so the guard is
 * defense-in-depth: it makes the ownership rule executable and fails fast if the declared
 * derived-collection set ever names a foreign/typo'd collection.
 */
@Service
public class PipelineRebuildService {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRebuildService.class);

    /**
     * The Mongo collections a full rebuild reconstructs (stage-1 ledger through stage-3 canonical).
     * Stage-4 reporting projections live in Postgres, not Mongo. Asserted owned before any rebuild.
     */
    static final Set<String> MANAGED_DERIVED_COLLECTIONS = Set.of(
            "scopus.import_events",
            "scopus.publication_facts",
            "scopus.author_facts",
            "scopus.affiliation_facts",
            "scopus.citation_facts",
            "scopus.forum_facts",
            "scopus.funding_facts",
            "wos.import_events",
            "wos.category_facts",
            "wos.metric_facts",
            "wos.journal_identity",
            "wos.fact_conflicts",
            "wos.identity_conflicts",
            "user_defined.publication_facts",
            "user_defined.forum_facts",
            "scholardex.publication_facts",
            "scholardex.author_facts",
            "scholardex.affiliation_facts",
            "scholardex.citation_facts",
            "scholardex.forum_facts",
            "scholardex.authorship_facts",
            "scholardex.author_affiliation_facts",
            "scholardex.publication_author_affiliation_facts",
            "scholardex.source_links",
            "scholardex.identity_conflicts",
            "scholardex.publication_link_conflicts");

    private final ScopusBigBangMigrationService scopusRebuild;
    private final WosBigBangMigrationService wosRebuild;
    private final OwnedCollectionRegistry ownedCollectionRegistry;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public PipelineRebuildService(
            ScopusBigBangMigrationService scopusRebuild,
            WosBigBangMigrationService wosRebuild,
            OwnedCollectionRegistry ownedCollectionRegistry,
            org.springframework.data.mongodb.core.MongoTemplate mongoTemplate) {
        this.scopusRebuild = scopusRebuild;
        this.wosRebuild = wosRebuild;
        this.ownedCollectionRegistry = ownedCollectionRegistry;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Fail fast at startup if the declared managed set ever names a collection this app does not own
     * (a typo or a foreign collection) — so the safety invariant is verified at boot, not first wipe.
     */
    @PostConstruct
    void validateManagedCollectionsAreOwned() {
        ownedCollectionRegistry.assertAllWipeable(MANAGED_DERIVED_COLLECTIONS);
    }

    /**
     * Rebuild all derived data from source: WoS (files → ledger → facts → projections) then Scopus
     * (file → ledger → facts → canonical → projections). Gated on the owned-collection safety rule.
     */
    public PipelineRebuildResult rebuildAllDerivedFromSource() {
        ownedCollectionRegistry.assertAllWipeable(MANAGED_DERIVED_COLLECTIONS);

        LOG.info("Pipeline rebuild starting: {} managed derived collections, all owned.",
                MANAGED_DERIVED_COLLECTIONS.size());

        // Stage-4 (Postgres views) + fact-build checkpoints + the per-source Mongo wipes are handled by the
        // canonical-state resets. Order mirrors the proven admin chain (scopus reset then wos reset).
        scopusRebuild.resetCanonicalState();
        wosRebuild.resetCanonicalState();

        // Cross-source safety net (H58-class): the per-source resets wipe canonical (scholardex.*) rows by
        // SOURCE, so a canonical entity contributed by a source the reset doesn't scope (e.g. WoS-source
        // forums historically) could survive. A full deleteAll over every managed Mongo collection here
        // guarantees a clean slate regardless of source attribution — this is THE full-rebuild entry point,
        // so it must not depend on per-source reset coverage being exhaustive.
        long wiped = 0;
        for (String collection : MANAGED_DERIVED_COLLECTIONS) {
            wiped += mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), collection)
                    .getDeletedCount();
        }
        LOG.info("Pipeline rebuild: full Mongo wipe removed {} residual docs across {} managed collections.",
                wiped, MANAGED_DERIVED_COLLECTIONS.size());

        WosBigBangMigrationService.WosBigBangMigrationResult wos = wosRebuild.run(false, null);
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult scopus = scopusRebuild.runFull();

        LOG.info("Pipeline rebuild complete.");
        return new PipelineRebuildResult(wos, scopus);
    }

    public record PipelineRebuildResult(
            WosBigBangMigrationService.WosBigBangMigrationResult wos,
            ScopusBigBangMigrationService.ScopusBigBangMigrationResult scopus) {
    }
}
