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

    /**
     * H75 skip-smart: the canonical (stage-3) collections only — the subset of {@link #MANAGED_DERIVED_COLLECTIONS}
     * that a derive-only rebuild wipes. The source ledgers + stage-2 fact collections ({@code scopus.*}, {@code wos.*},
     * {@code user_defined.*}) and the OpenAlex source facts are deliberately spared so the canonical layer can be
     * re-derived from them without a re-ingest.
     */
    static final Set<String> CANONICAL_COLLECTIONS = Set.of(
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
    private final ro.uvt.pokedex.core.service.importing.DoajDataService doajDataService;
    private final ro.uvt.pokedex.core.service.importing.ErihDataService erihDataService;
    private final ForumReconcileService forumReconcileService;
    private final ro.uvt.pokedex.core.service.openalex.OpenAlexBulkImportService openAlexBulkImportService;
    private final ro.uvt.pokedex.core.service.brainmap.BrainmapProjectImportService brainmapProjectImportService;

    // H66B M8-B: the DOAJ/ERIH reference snapshots are match-only inputs to the forum build (read by the
    // ERIH/DOAJ onboarding inside the Scopus forum build). Folding their import into the unified DAG means one
    // rebuild produces the complete full-feed registry. Blank path skips (reference data persists the wipe).
    @org.springframework.beans.factory.annotation.Value("${h66.doaj.file:}")
    private String doajFile;
    @org.springframework.beans.factory.annotation.Value("${h66.erih.file:}")
    private String erihFile;
    @org.springframework.beans.factory.annotation.Value("${h66.reference.as-of:2026}")
    private String referenceAsOf;

    // H73 slice 1: file-driven OpenAlex bulk import (works + citers → source facts; referenced institutions →
    // ROR affiliation backbone). Blank paths skip the step (like the DOAJ/ERIH feeds). The backbone is derived
    // BEFORE the Scopus build so the Scopus affiliation canon (slice 2) can resolve afids into it.
    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.works-file:}")
    private String openAlexWorksFile;
    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.citers-file:}")
    private String openAlexCitersFile;
    @org.springframework.beans.factory.annotation.Value("${core.openalex.bulk.institutions-dir:}")
    private String openAlexInstitutionsDir;

    // H64 slice 1: file-driven brainmap projects import (data/brainmap/uvt_projects.jsonl → brainmap.project_facts,
    // a source collection re-imported from file like the OpenAlex facts). Blank path skips. The canonical project
    // derivation runs after the Scopus build (it resolves coordinators into the affiliation backbone).
    @org.springframework.beans.factory.annotation.Value("${core.brainmap.bulk.projects-file:}")
    private String brainmapProjectsFile;

    public PipelineRebuildService(
            ScopusBigBangMigrationService scopusRebuild,
            WosBigBangMigrationService wosRebuild,
            OwnedCollectionRegistry ownedCollectionRegistry,
            org.springframework.data.mongodb.core.MongoTemplate mongoTemplate,
            ro.uvt.pokedex.core.service.importing.DoajDataService doajDataService,
            ro.uvt.pokedex.core.service.importing.ErihDataService erihDataService,
            ForumReconcileService forumReconcileService,
            ro.uvt.pokedex.core.service.openalex.OpenAlexBulkImportService openAlexBulkImportService,
            ro.uvt.pokedex.core.service.brainmap.BrainmapProjectImportService brainmapProjectImportService) {
        this.scopusRebuild = scopusRebuild;
        this.wosRebuild = wosRebuild;
        this.ownedCollectionRegistry = ownedCollectionRegistry;
        this.mongoTemplate = mongoTemplate;
        this.doajDataService = doajDataService;
        this.erihDataService = erihDataService;
        this.forumReconcileService = forumReconcileService;
        this.openAlexBulkImportService = openAlexBulkImportService;
        this.brainmapProjectImportService = brainmapProjectImportService;
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
        return rebuildAllDerivedFromSource(false);
    }

    /**
     * Skip-smart full rebuild (H75). When {@code forceReingest} is false and the source/stage-2 facts are already
     * present (the common "iterate on canon logic" case), this skips the expensive re-ingest (WoS run, OpenAlex
     * bulk, Scopus ingest + fact-build) and re-derives the canonical layer + projections from the existing facts —
     * a ~5 min re-derive instead of a ~33 min from-scratch. Pass {@code forceReingest=true} (or have no source
     * facts) to run the full ingest + derive. Either way the rebuild is gated on the owned-collection safety rule.
     */
    public PipelineRebuildResult rebuildAllDerivedFromSource(boolean forceReingest) {
        ownedCollectionRegistry.assertAllWipeable(MANAGED_DERIVED_COLLECTIONS);

        if (!forceReingest && sourceFactsPresent()) {
            return deriveOnlyRebuild();
        }

        LOG.info("Pipeline rebuild starting (FULL ingest): {} managed derived collections, all owned. forceReingest={}",
                MANAGED_DERIVED_COLLECTIONS.size(), forceReingest);

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
        // H66B M8-B: refresh the DOAJ/ERIH reference snapshots before the Scopus forum build (its ERIH/DOAJ
        // onboarding reads them). These collections are not source-replayed (not in the managed wipe set), so
        // re-importing here keeps the unified rebuild's registry complete and current.
        ingestReferenceFeedsIfConfigured();
        // H73 slice 1: import the OpenAlex works/citers source facts + derive the ROR affiliation backbone
        // BEFORE the Scopus build, so Scopus affiliations can resolve into the backbone (slice 2).
        ingestOpenAlexBulkIfConfigured();
        // H64 slice 1: import brainmap project source facts (idempotent upsert; canonical derivation runs later).
        ingestBrainmapProjectsIfConfigured();
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult scopus = scopusRebuild.runFull();

        // H75: the post-rebuild author reconcile (forum dedup + author ORCID/fuzzy/over-split) is skipped — the V2
        // canon already produces core-deduped authors (ORCID + positional bridge), and the slow O(N^2) within-source
        // fuzzy/over-split reconcile is deferred to a future V2 pass. Projections already ran inside runFull.
        LOG.info("Pipeline rebuild complete.");
        return new PipelineRebuildResult(wos, scopus);
    }

    /**
     * Derive-only path: wipe ONLY the canonical (stage-3) collections and re-derive them + the projections from the
     * surviving source/stage-2 facts. No source reset (that would wipe {@code scopus.import_events} + the stage-2
     * facts — see {@code ScopusBigBangMigrationService.resetCanonicalState}), no WoS run, no OpenAlex bulk. The
     * canonical writes downstream are all wipe-first, so this canonical-only pre-wipe is the only cleanup needed.
     */
    private PipelineRebuildResult deriveOnlyRebuild() {
        LOG.info("Pipeline rebuild starting (DERIVE-ONLY): source facts present, skipping re-ingest. "
                + "Wiping {} canonical collections, sparing source/stage-2 facts.", CANONICAL_COLLECTIONS.size());
        long wiped = 0;
        for (String collection : CANONICAL_COLLECTIONS) {
            wiped += mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), collection)
                    .getDeletedCount();
        }
        LOG.info("Pipeline rebuild (derive-only): canonical wipe removed {} docs across {} collections.",
                wiped, CANONICAL_COLLECTIONS.size());
        ScopusBigBangMigrationService.ScopusBigBangMigrationResult scopus = scopusRebuild.runDeriveFromFacts();
        LOG.info("Pipeline rebuild complete (derive-only).");
        return new PipelineRebuildResult(null, scopus);
    }

    /**
     * True when the big source/stage-2 facts from a prior ingest are all present, so the canonical layer can be
     * re-derived without re-parsing source files. Gates the derive-only fast path; a clean DB (or any missing
     * source) forces the full ingest.
     */
    private boolean sourceFactsPresent() {
        org.springframework.data.mongodb.core.query.Query all = new org.springframework.data.mongodb.core.query.Query();
        long scopusPubs = mongoTemplate.count(all, "scopus.publication_facts");
        long openAlexPubs = mongoTemplate.count(all, "openalex.publication_facts");
        long wosIdentity = mongoTemplate.count(all, "wos.journal_identity");
        boolean present = scopusPubs > 0 && openAlexPubs > 0 && wosIdentity > 0;
        LOG.info("Source-facts presence gate: scopusPubs={} openAlexPubs={} wosJournalIdentity={} -> present={}",
                scopusPubs, openAlexPubs, wosIdentity, present);
        return present;
    }

    private void ingestReferenceFeedsIfConfigured() {
        long now = System.currentTimeMillis();
        if (isReadableFile(doajFile)) {
            var r = doajDataService.importDoajCsvFromPath(doajFile, "doaj-" + now, referenceAsOf);
            LOG.info("Unified rebuild DOAJ ingest ({}): processed={} imported={} updated={} errors={}",
                    doajFile, r.getProcessedCount(), r.getImportedCount(), r.getUpdatedCount(), r.getErrorCount());
        } else if (doajFile != null && !doajFile.isBlank()) {
            LOG.warn("Unified rebuild DOAJ ingest skipped: file not found ({})", doajFile);
        }
        if (isReadableFile(erihFile)) {
            var r = erihDataService.importErihJsonlFromPath(erihFile, "erih-" + now, referenceAsOf);
            LOG.info("Unified rebuild ERIH ingest ({}): processed={} imported={} updated={} errors={}",
                    erihFile, r.getProcessedCount(), r.getImportedCount(), r.getUpdatedCount(), r.getErrorCount());
        } else if (erihFile != null && !erihFile.isBlank()) {
            LOG.warn("Unified rebuild ERIH ingest skipped: file not found ({})", erihFile);
        }
    }

    private void ingestOpenAlexBulkIfConfigured() {
        boolean anyConfigured = (openAlexWorksFile != null && !openAlexWorksFile.isBlank())
                || (openAlexCitersFile != null && !openAlexCitersFile.isBlank())
                || (openAlexInstitutionsDir != null && !openAlexInstitutionsDir.isBlank());
        if (!anyConfigured) {
            return;
        }
        long now = System.currentTimeMillis();
        java.nio.file.Path works = pathOrNull(openAlexWorksFile);
        java.nio.file.Path citers = pathOrNull(openAlexCitersFile);
        java.nio.file.Path institutions = pathOrNull(openAlexInstitutionsDir);
        try {
            var r = openAlexBulkImportService.importAll(works, citers, institutions,
                    "openalex-bulk-" + now, "full-rebuild");
            LOG.info("Unified rebuild OpenAlex bulk ingest: works={} citers={} referencedInstitutions={} backbone={}",
                    r.worksImported(), r.citersImported(), r.referencedInstitutions(), r.backboneInstitutions());
        } catch (java.io.IOException e) {
            LOG.error("Unified rebuild OpenAlex bulk ingest failed", e);
            throw new IllegalStateException("OpenAlex bulk ingest failed", e);
        }
    }

    private void ingestBrainmapProjectsIfConfigured() {
        if (brainmapProjectsFile == null || brainmapProjectsFile.isBlank()) {
            return;
        }
        java.nio.file.Path projects = pathOrNull(brainmapProjectsFile);
        try {
            var r = brainmapProjectImportService.importAll(projects,
                    "brainmap-projects-" + System.currentTimeMillis(), "full-rebuild");
            LOG.info("Unified rebuild brainmap projects ingest: imported={} skipped={}",
                    r.projectsImported(), r.skipped());
        } catch (java.io.IOException e) {
            LOG.error("Unified rebuild brainmap projects ingest failed", e);
            throw new IllegalStateException("Brainmap projects ingest failed", e);
        }
    }

    private static java.nio.file.Path pathOrNull(String path) {
        return path == null || path.isBlank() ? null : java.nio.file.Path.of(path);
    }

    private boolean isReadableFile(String path) {
        return path != null && !path.isBlank() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path));
    }

    public record PipelineRebuildResult(
            WosBigBangMigrationService.WosBigBangMigrationResult wos,
            ScopusBigBangMigrationService.ScopusBigBangMigrationResult scopus) {
    }
}
