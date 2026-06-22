package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.application.CanonicalWriteProvenance;
import ro.uvt.pokedex.core.service.application.ScholardexEdgeWriterService;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationWriter;
import ro.uvt.pokedex.core.service.application.ScholardexSourceLinkService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import ro.uvt.pokedex.core.service.openalex.OpenAlexAuthorResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H66B Phase 4a — canonicalize OpenAlex publication source-facts into {@code scholardex.publication_facts}.
 *
 * <p>Mirrors {@link UserDefinedCanonicalizationService} but tuned for OpenAlex's identity model. Resolution is
 * DOI-primary (Decision 0): an OpenAlex work whose normalized DOI already names a canonical publication
 * <em>links</em> to it (an {@code OPENALEX} source-link only — the canonical fact's richer Scopus content is left
 * untouched, never clobbered). A work with no canonical DOI match is <em>minted</em> via
 * {@code buildCanonicalPublicationId} (DOI-keyed, so a later Scopus build of the same DOI converges on the same id).
 * In both cases one {@code OPENALEX} authorship edge per syncing researcher is written so the work is visible in
 * their workspace even if Scopus never attributed it (the edge is {@code (publicationId, authorId, source)}-keyed,
 * so it coexists with any Scopus edge). Co-author bridging against OpenAlex's fragmented author entities is deferred.
 */
@Service
@RequiredArgsConstructor
public class OpenAlexCanonicalizationService {

    public static final String SOURCE_OPENALEX = "OPENALEX";
    private static final String SOURCE_SCOPUS = "SCOPUS";
    private static final String LINK_REASON_OPENALEX_PUBLICATION = "openalex-fact-bridge";
    private static final String LINK_REASON_OPENALEX_AUTHORSHIP = "openalex-authorship-self";
    // H73 slice 3: the "authored this paper while at this institution" edge from OpenAlex authorship institutions.
    private static final String LINK_REASON_OPENALEX_AFFILIATION = "openalex-authorship-affiliation";
    // H73 S2.2 (author inversion): a positional AU-ID -> OpenAlex-author source-link so the later Scopus canon
    // resolves the Scopus AU-ID into this OpenAlex author instead of minting a separate Scopus-keyed twin.
    private static final String LINK_REASON_AUTHOR_INVERSION = "openalex-author-inversion";
    private static final String REASON_PUBLICATION_DOI_AMBIGUOUS = "OPENALEX_PUBLICATION_DOI_AMBIGUOUS";

    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final ScholardexPublicationFactRepository scholardexPublicationFactRepository;
    private final ScholardexPublicationWriter publicationWriter;
    private final ScholardexSourceLinkService sourceLinkService;
    private final ScholardexEdgeWriterService edgeWriterService;
    private final ScholardexPublicationCanonicalizationService publicationCanonicalizationService;
    private final ro.uvt.pokedex.core.service.openalex.OpenAlexAuthorResolver authorResolver;
    private final ro.uvt.pokedex.core.service.application.OpenAlexForumOnboardingService forumOnboardingService;
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository forumFactRepository;
    // H73 S2.2 (author inversion): Scopus publication + author source-facts feed the positional AU-ID bridge.
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScopusPublicationFactRepository scopusPublicationFactRepository;
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScopusAuthorFactRepository scopusAuthorFactRepository;

    /**
     * Full-rebuild replay: canonicalize every OpenAlex source-fact. Runs in <b>bulk mode</b> (H73 slice 3):
     * an in-memory author index (no per-author repo lookups) + batched affiliation-edge writes — necessary at
     * the citer-full scale (~113k pubs, ~645k authorships).
     */
    public ImportProcessingResult rebuildCanonicalFacts() {
        return canonicalize(new ArrayList<>(openAlexPublicationFactRepository.findAll()), true);
    }

    /** On-demand (Tier-2): canonicalize just the source-facts touched by a sync batch (per-call repo path). */
    public ImportProcessingResult resolve(Collection<String> sourceRecordIds) {
        if (sourceRecordIds == null || sourceRecordIds.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        return canonicalize(new ArrayList<>(openAlexPublicationFactRepository.findBySourceRecordIdIn(sourceRecordIds)), false);
    }

    private ImportProcessingResult canonicalize(List<OpenAlexPublicationFact> sources, boolean bulk) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        // Stage 3: resolve/mint the host-venue forums first (ISSN-gated, flushed) so canonicalizeOne can stamp forumId.
        forumOnboardingService.onboard(sources);
        sources.sort(Comparator.comparing(OpenAlexPublicationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
        BulkCanonState state = bulk ? new BulkCanonState(authorResolver.primeBulkContext()) : null;
        if (state != null) {
            buildScopusInversionIndexes(state);
        }
        for (OpenAlexPublicationFact source : sources) {
            canonicalizeOne(source, result, state);
        }
        if (state != null) {
            authorResolver.flushBulkContext(state.authorCtx);
            flushBulkEdges(state);
        }
        return result;
    }

    /**
     * H73 slice 3: per-run bulk accumulators — the in-memory author index plus the affiliation-edge commands,
     * deduped by natural key so a recurring (author, affiliation) edge is written once across the whole run
     * (the batch insert path assumes the canonical edges were wiped first, which the full rebuild guarantees).
     */
    private static final class BulkCanonState {
        private final OpenAlexAuthorResolver.BulkContext authorCtx;
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> authorship = new LinkedHashMap<>();
        private final Set<String> correspondingAuthorshipKeys = new LinkedHashSet<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> authorAffiliation = new LinkedHashMap<>();
        private final Map<String, ScholardexEdgeWriterService.EdgeWriteCommand> publicationAuthorAffiliation = new LinkedHashMap<>();

        // H73 S2.2 (author inversion): normalized DOI -> ordered Scopus AU-IDs (from Scopus pub source-facts;
        // ambiguous shared/container DOIs excluded), AU-ID -> Scopus display name (surname guard), and the
        // resulting AU-ID -> OpenAlex-author source-links (deduped by AU-ID; conflicting positions dropped).
        private final Map<String, List<String>> scopusAuthorsByDoi = new HashMap<>();
        private final Map<String, String> scopusNameByAuid = new HashMap<>();
        private final Map<String, ScholardexSourceLinkService.SourceLinkUpsertCommand> authorInversionLinks = new LinkedHashMap<>();
        private final Set<String> conflictedAuids = new HashSet<>();

        BulkCanonState(OpenAlexAuthorResolver.BulkContext authorCtx) {
            this.authorCtx = authorCtx;
        }
    }

    /**
     * H73 S2.2 (author inversion): index the Scopus publication source-facts by normalized DOI -> ordered Scopus
     * AU-IDs, and the Scopus author source-facts by AU-ID -> display name (for the per-position surname guard).
     * Shared/container DOIs (more than one Scopus pub) are ambiguous for positional bridging and excluded.
     */
    private void buildScopusInversionIndexes(BulkCanonState state) {
        Set<String> ambiguousDoi = new HashSet<>();
        for (ro.uvt.pokedex.core.model.scopus.canonical.ScopusPublicationFact pub : scopusPublicationFactRepository.findAll()) {
            String doi = ScholardexPublicationCanonicalizationService.normalizeDoi(pub.getDoi());
            if (doi == null || pub.getAuthors() == null || pub.getAuthors().isEmpty() || ambiguousDoi.contains(doi)) {
                continue;
            }
            if (state.scopusAuthorsByDoi.containsKey(doi)) {
                state.scopusAuthorsByDoi.remove(doi); // second pub with this DOI -> ambiguous, blocklist it.
                ambiguousDoi.add(doi);
                continue;
            }
            state.scopusAuthorsByDoi.put(doi, new ArrayList<>(pub.getAuthors()));
        }
        for (ro.uvt.pokedex.core.model.scopus.canonical.ScopusAuthorFact author : scopusAuthorFactRepository.findAll()) {
            if (!isBlank(author.getAuthorId()) && !isBlank(author.getName())) {
                state.scopusNameByAuid.putIfAbsent(author.getAuthorId(), author.getName());
            }
        }
    }

    private static final int EDGE_FLUSH_CHUNK = 5000;

    private void flushBulkEdges(BulkCanonState state) {
        // Authorship edges (S3.5): batched, with the corresponding flag stamped from the accumulated keys.
        flushEdgeChunks(new ArrayList<>(state.authorship.values()),
                chunk -> edgeWriterService.batchUpsertAuthorshipEdges(chunk, state.correspondingAuthorshipKeys, null, false));
        flushEdgeChunks(new ArrayList<>(state.authorAffiliation.values()),
                chunk -> edgeWriterService.batchUpsertAuthorAffiliationEdges(chunk, null, false));
        flushEdgeChunks(new ArrayList<>(state.publicationAuthorAffiliation.values()),
                chunk -> edgeWriterService.batchUpsertPublicationAuthorAffiliationEdges(chunk, null, false));
        // H73 S2.2: flush the AU-ID -> OpenAlex-author inversion source-links. allowFallbackLookup=false with an
        // empty preload assumes the source-link collection was wiped (full rebuild) so these are clean inserts.
        if (!state.authorInversionLinks.isEmpty()) {
            sourceLinkService.batchUpsertWithState(
                    new ArrayList<>(state.authorInversionLinks.values()), new HashMap<>(), false);
        }
    }

    private void flushEdgeChunks(List<ScholardexEdgeWriterService.EdgeWriteCommand> commands,
                                 java.util.function.Consumer<List<ScholardexEdgeWriterService.EdgeWriteCommand>> flush) {
        for (int i = 0; i < commands.size(); i += EDGE_FLUSH_CHUNK) {
            flush.accept(commands.subList(i, Math.min(i + EDGE_FLUSH_CHUNK, commands.size())));
        }
    }

    /** Stage 3: resolve a publication's host venue to its canonical forum via the venue's openAlexIds tag. */
    private String resolveForumId(String hostVenueOpenAlexId) {
        if (hostVenueOpenAlexId == null || hostVenueOpenAlexId.isBlank()) {
            return null;
        }
        return forumFactRepository.findByOpenAlexIdsContaining(hostVenueOpenAlexId)
                .map(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact::getId).orElse(null);
    }

    private void canonicalizeOne(OpenAlexPublicationFact source, ImportProcessingResult result, BulkCanonState state) {
        result.markProcessed();
        String workId = source.getSourceRecordId();
        if (isBlank(workId)) {
            result.markSkipped("openalex publication missing sourceRecordId");
            return;
        }
        String doiNormalized = ScholardexPublicationCanonicalizationService.normalizeDoi(source.getDoi());

        List<ScholardexPublicationFact> byDoi = doiNormalized == null
                ? List.of()
                : new ArrayList<>(scholardexPublicationFactRepository.findAllByDoiNormalized(doiNormalized));

        String canonicalPublicationId;
        if (byDoi.size() > 1) {
            // Shared/container DOI (Decision 0 quarantine surface): can't pick one — defer, don't guess.
            sourceLinkService.markConflict(
                    ScholardexEntityType.PUBLICATION,
                    SOURCE_OPENALEX,
                    workId,
                    REASON_PUBLICATION_DOI_AMBIGUOUS,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    false);
            result.markSkipped("openalex publication ambiguous doi workId=" + workId);
            return;
        } else if (byDoi.size() == 1) {
            ScholardexPublicationFact target = byDoi.getFirst();
            canonicalPublicationId = target.getId();
            if (SOURCE_OPENALEX.equals(target.getSource())) {
                // We own this pub (OpenAlex minted it). OpenAlex is its authority, so REFRESH it in place
                // (citedByCount, corresponding authors, title…) rather than link-to-self and freeze the data.
                applyOpenAlexFields(target, source, doiNormalized, canonicalPublicationId);
                result.markUpdated();
            } else {
                // Foreign (Scopus/user-defined) pub: enrich provenance only, never overwrite its richer content —
                // except the citation count, where OpenAlex's broader index is usually MORE complete. Surface the
                // best-available count with a monotonic max so the reported "Times cited" never regresses.
                bumpCitedByCount(target, source.getCitedByCount());
                sourceLinkService.link(
                        ScholardexEntityType.PUBLICATION,
                        SOURCE_OPENALEX,
                        workId,
                        canonicalPublicationId,
                        LINK_REASON_OPENALEX_PUBLICATION,
                        source.getSourceEventId(),
                        source.getSourceBatchId(),
                        source.getSourceCorrelationId(),
                        false);
                result.markUpdated();
            }
        } else {
            // MINT: a genuinely new (or DOI-less) publication OpenAlex contributes.
            String titleNormalized = ScholardexPublicationCanonicalizationService.normalizeTitle(source.getTitle());
            canonicalPublicationId = publicationCanonicalizationService.buildCanonicalPublicationId(
                    null, null, null, null, doiNormalized, titleNormalized,
                    source.getCoverDate(), source.getCreator(), null);
            applyOpenAlexFields(new ScholardexPublicationFact(), source, doiNormalized, canonicalPublicationId);
            result.markImported();
        }

        writeAuthorshipEdges(source, canonicalPublicationId, doiNormalized, state);
    }

    /**
     * Apply OpenAlex bibliographic content onto a canonical pub and persist it. Used for both a fresh MINT and an
     * in-place REFRESH of an OpenAlex-owned pub. Corresponding authors are name strings (same shape as the Scopus
     * field) and are written only when OpenAlex actually flagged one (so a now-empty payload never wipes a prior
     * capture); {@code citedByCount} and the rest always reflect the latest OpenAlex state.
     */
    /**
     * Surface OpenAlex's citation count onto a foreign (Scopus/user-defined) pub WITHOUT touching its other content:
     * a monotonic max so the reported "Times cited" reflects the best-available source and never regresses. No-op
     * when OpenAlex has no higher count.
     */
    private void bumpCitedByCount(ScholardexPublicationFact target, Integer openAlexCount) {
        if (openAlexCount == null) {
            return;
        }
        int current = target.getCitedByCount() == null ? 0 : target.getCitedByCount();
        if (openAlexCount > current) {
            target.setCitedByCount(openAlexCount);
            scholardexPublicationFactRepository.save(target);
        }
    }

    private void applyOpenAlexFields(
            ScholardexPublicationFact fact, OpenAlexPublicationFact source, String doiNormalized, String canonicalId) {
        if (fact.getCreatedAt() == null) {
            fact.setCreatedAt(Instant.now());
        }
        fact.setId(canonicalId);
        fact.setDoi(source.getDoi());
        fact.setDoiNormalized(doiNormalized);
        fact.setTitle(source.getTitle());
        fact.setTitleNormalized(ScholardexPublicationCanonicalizationService.normalizeTitle(source.getTitle()));
        fact.setCreator(source.getCreator());
        fact.setAuthorCount(source.getAuthorCount());
        // Corresponding authors are no longer denormalized as name strings here — they are modeled id-based as
        // `corresponding=true` authorship edges to canonical authors (writeAuthorshipEdges).
        fact.setCoverDate(source.getCoverDate());
        fact.setForumId(resolveForumId(source.getHostVenueOpenAlexId())); // Stage 3: ISSN-resolved venue, else null
        fact.setCitedByCount(source.getCitedByCount());
        fact.setOpenAccess(source.getOpenAccess());
        fact.setSubtype(source.getType());
        fact.setSubtypeDescription(source.getType());
        fact.setSourceEventId(source.getSourceEventId());
        publicationWriter.upsertAndLinkSource(
                fact,
                new CanonicalWriteProvenance(
                        SOURCE_OPENALEX,
                        source.getSourceRecordId(),
                        source.getSourceBatchId(),
                        source.getSourceCorrelationId(),
                        source.getSourceEventId()),
                LINK_REASON_OPENALEX_PUBLICATION);
    }

    /**
     * Write the {@code OPENALEX} authorship edges for a work (H66B Phase 4a, id-based corresponding model):
     * <ol>
     *   <li>seed each syncing researcher's ORCID onto their canonical author;</li>
     *   <li><b>positional ORCID bridge</b> — for a DOI-linked pub (its canonical fact already carries the ordered
     *       Scopus {@code authorIds}), seed each OpenAlex ORCID onto the Scopus author at the same position (Scopus
     *       and OpenAlex agree on author order — validated 29/29), guarded by equal author count + per-position
     *       surname agreement. This makes Scopus authors ORCID-keyed so the next step dedups against them;</li>
     *   <li>resolve each {@code is_corresponding} authorship to a canonical author (by ORCID → OpenAlex id → mint) —
     *       after bridging, ORCID resolution lands on the existing Scopus author rather than minting a duplicate;</li>
     *   <li>write one edge per author in (syncing researchers ∪ resolved corresponding authors), stamping
     *       {@code corresponding=true} exactly on the resolved corresponding authors.</li>
     * </ol>
     * The {@code (pub, author, source)} key means OPENALEX edges coexist with Scopus ones.
     */
    private void writeAuthorshipEdges(OpenAlexPublicationFact source, String canonicalPublicationId,
                                      String doiNormalized, BulkCanonState state) {
        if (isBlank(canonicalPublicationId)) {
            return;
        }
        OpenAlexAuthorResolver.BulkContext authorCtx = state == null ? null : state.authorCtx;
        List<OpenAlexPublicationFact.AuthorRef> authorships =
                source.getAuthorships() == null ? List.of() : source.getAuthorships();

        // 1) Seed ORCIDs onto the syncing researchers' canonical authors.
        List<String> syncingAuthorIds = new ArrayList<>();
        if (source.getSyncedResearchers() != null) {
            for (OpenAlexPublicationFact.SyncedResearcher researcher : source.getSyncedResearchers()) {
                if (researcher == null || isBlank(researcher.getCanonicalAuthorId())) {
                    continue;
                }
                authorResolver.attachOrcid(researcher.getCanonicalAuthorId(), researcher.getOrcid(), authorCtx);
                syncingAuthorIds.add(researcher.getCanonicalAuthorId());
            }
        }

        // 2) Positional ORCID bridge onto the linked Scopus pub's authors (runs before corresponding resolution).
        ScholardexPublicationFact pub = scholardexPublicationFactRepository.findById(canonicalPublicationId).orElse(null);
        if (pub != null && pub.getAuthorIds() != null && !pub.getAuthorIds().isEmpty() && !authorships.isEmpty()) {
            List<String> openAlexNames = authorships.stream().map(OpenAlexPublicationFact.AuthorRef::getDisplayName).toList();
            List<String> openAlexOrcids = authorships.stream().map(OpenAlexPublicationFact.AuthorRef::getOrcid).toList();
            authorResolver.bridgeOrcidsByPosition(pub.getAuthorIds(), openAlexNames, openAlexOrcids, authorCtx);
        }

        // 3) Resolve authorships to canonical authors. For an OpenAlex-OWNED pub (we mint it; OpenAlex is its
        //    authority) resolve the FULL ordered author list. For a FOREIGN (Scopus-owned) pub resolve only the
        //    corresponding authors — enrichment that must never clobber the richer Scopus author list.
        boolean openAlexOwned = pub != null && SOURCE_OPENALEX.equals(pub.getSource());
        LinkedHashSet<String> resolvedAuthorIds = new LinkedHashSet<>();   // OpenAlex author order
        Set<String> correspondingAuthorIds = new LinkedHashSet<>();
        // H73 S2.2: the canonical OpenAlex author resolved at each authorship position (null where skipped or
        // name-only), parallel to authorships, so the inversion bridge can pair position i with the Scopus AU-ID.
        List<String> resolvedByPosition = new ArrayList<>(authorships.size());
        for (OpenAlexPublicationFact.AuthorRef ref : authorships) {
            boolean corresponding = ref.isCorresponding();
            if (!openAlexOwned && !corresponding) {
                resolvedByPosition.add(null);
                continue;
            }
            String authorId = authorResolver.resolveOrMint(
                    ref.getDisplayName(), ref.getOrcid(), ref.getOpenAlexAuthorId(),
                    ref.getInstitutionNames(), source.getSourceBatchId(), source.getSourceCorrelationId(), authorCtx);
            resolvedByPosition.add(isBlank(authorId) ? null : authorId);
            if (!isBlank(authorId)) {
                resolvedAuthorIds.add(authorId);
                if (corresponding) {
                    correspondingAuthorIds.add(authorId);
                }
                // H73 slice 3: "authored this paper while at this institution" — resolve each authorship's
                // OpenAlex institution ROR to the ROR-keyed backbone affiliation and emit the affiliation edges.
                upsertAffiliationEdges(canonicalPublicationId, authorId, ref.getInstitutionRors(), source, state);
            }
        }
        // H73 S2.2: positionally bridge each Scopus AU-ID (from the same-DOI Scopus pub source-fact) onto the
        // OpenAlex author resolved above, writing a LINKED source-link the later Scopus canon resolves against.
        writeAuthorInversionLinks(source, doiNormalized, authorships, resolvedByPosition, state);

        // 4) One edge per author (syncing researchers ∪ resolved authors), corresponding flag where applicable.
        LinkedHashSet<String> edgeAuthorIds = new LinkedHashSet<>(syncingAuthorIds);
        edgeAuthorIds.addAll(resolvedAuthorIds);
        for (String authorId : edgeAuthorIds) {
            boolean corresponding = correspondingAuthorIds.contains(authorId);
            ScholardexEdgeWriterService.EdgeWriteCommand command = new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalPublicationId,
                    authorId,
                    SOURCE_OPENALEX,
                    source.getSourceRecordId() + "::author::" + authorId,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    ScholardexSourceLinkService.STATE_LINKED,
                    LINK_REASON_OPENALEX_AUTHORSHIP,
                    false);
            if (state != null) {
                // S3.5: accumulate for a batched insert; the corresponding flag is carried by key.
                String key = canonicalPublicationId + "|" + authorId;
                state.authorship.putIfAbsent(key, command);
                if (corresponding) {
                    state.correspondingAuthorshipKeys.add(key);
                }
            } else {
                edgeWriterService.upsertAuthorshipEdge(command, corresponding ? Boolean.TRUE : Boolean.FALSE);
            }
        }

        // 5) Denormalize onto the canonical pub's authorIds[] (the projection/workspace read this, not the edges).
        //    OWNED: set the full OpenAlex author list, in order (resolved first; syncing researchers appended as a
        //    safety net so they're never lost). FOREIGN: merge-only — never drop a foreign pub's authors.
        if (!edgeAuthorIds.isEmpty() && pub != null) {
            LinkedHashSet<String> desired;
            if (openAlexOwned) {
                desired = new LinkedHashSet<>(resolvedAuthorIds);
                desired.addAll(syncingAuthorIds);
            } else {
                desired = new LinkedHashSet<>(pub.getAuthorIds() == null ? List.of() : pub.getAuthorIds());
                desired.addAll(edgeAuthorIds);
            }
            List<String> current = pub.getAuthorIds() == null ? List.of() : pub.getAuthorIds();
            if (!new ArrayList<>(desired).equals(current)) {
                pub.setAuthorIds(new ArrayList<>(desired));
                scholardexPublicationFactRepository.save(pub);
            }
        }
    }

    /**
     * H73 S2.2 (author inversion): for a publication whose normalized DOI matches exactly one Scopus pub
     * source-fact, positionally bridge each Scopus AU-ID onto the OpenAlex author resolved at the same position,
     * writing a LINKED {@code (AUTHOR, SCOPUS, auid) -> openAlexAuthorId} source-link. The later Scopus author /
     * publication canon resolves the AU-ID against this link instead of minting a Scopus-keyed twin — making the
     * OpenAlex author the canonical identity. Guards mirror the ORCID bridge: equal author count + per-position
     * surname agreement; an AU-ID positionally bridged to two different OpenAlex authors across papers is dropped.
     */
    private void writeAuthorInversionLinks(OpenAlexPublicationFact source, String doiNormalized,
                                           List<OpenAlexPublicationFact.AuthorRef> authorships,
                                           List<String> resolvedByPosition, BulkCanonState state) {
        if (state == null || doiNormalized == null) {
            return;
        }
        List<String> scopusAuthors = state.scopusAuthorsByDoi.get(doiNormalized);
        int n = authorships.size();
        if (scopusAuthors == null || n == 0 || scopusAuthors.size() != n) {
            return; // no unambiguous same-DOI Scopus pub, or counts disagree -> positions can't be trusted.
        }
        for (int i = 0; i < n; i++) {
            String openAlexAuthorId = resolvedByPosition.get(i);
            String auid = scopusAuthors.get(i);
            if (isBlank(openAlexAuthorId) || isBlank(auid) || state.conflictedAuids.contains(auid)) {
                continue;
            }
            if (!OpenAlexAuthorResolver.surnameMatches(state.scopusNameByAuid.get(auid), authorships.get(i).getDisplayName())) {
                continue; // per-position verification failed — don't bridge a possibly-misaligned position.
            }
            ScholardexSourceLinkService.SourceLinkUpsertCommand existing = state.authorInversionLinks.get(auid);
            if (existing != null) {
                if (!openAlexAuthorId.equals(existing.canonicalEntityId())) {
                    state.authorInversionLinks.remove(auid); // same AU-ID -> two OpenAlex authors: ambiguous, drop.
                    state.conflictedAuids.add(auid);
                }
                continue;
            }
            state.authorInversionLinks.put(auid, new ScholardexSourceLinkService.SourceLinkUpsertCommand(
                    ScholardexEntityType.AUTHOR,
                    SOURCE_SCOPUS,
                    auid,
                    openAlexAuthorId,
                    ScholardexSourceLinkService.STATE_LINKED,
                    LINK_REASON_AUTHOR_INVERSION,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    false));
        }
    }

    /**
     * H73 slice 3: for one resolved authorship, emit the author→affiliation and pub→author→affiliation edges to the
     * ROR-keyed backbone affiliation ({@code saff_<hash(ror)>}) for each OpenAlex institution ROR on the authorship.
     * Deduplicates RORs within the authorship; skips blanks.
     */
    private void upsertAffiliationEdges(String canonicalPublicationId, String authorId,
                                        java.util.List<String> institutionRors, OpenAlexPublicationFact source,
                                        BulkCanonState state) {
        if (institutionRors == null || institutionRors.isEmpty()) {
            return;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String ror : institutionRors) {
            if (isBlank(ror) || !seen.add(ror)) {
                continue;
            }
            String affiliationId = CanonicalizationSupport.buildRorBackboneAffiliationId(ror);
            ScholardexEdgeWriterService.EdgeWriteCommand authorAff = new ScholardexEdgeWriterService.EdgeWriteCommand(
                    authorId,
                    affiliationId,
                    SOURCE_OPENALEX,
                    source.getSourceRecordId() + "::authoraff::" + authorId + "::" + affiliationId,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    ScholardexSourceLinkService.STATE_LINKED,
                    LINK_REASON_OPENALEX_AFFILIATION,
                    false);
            ScholardexEdgeWriterService.EdgeWriteCommand pubAuthorAff = new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalPublicationId,
                    authorId,
                    affiliationId,
                    SOURCE_OPENALEX,
                    source.getSourceRecordId() + "::pubauthoraff::" + authorId + "::" + affiliationId,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    ScholardexSourceLinkService.STATE_LINKED,
                    LINK_REASON_OPENALEX_AFFILIATION,
                    false);
            if (state != null) {
                // Bulk mode: dedup by natural key (a recurring author↔affiliation across papers writes once) and
                // flush as batched inserts at the end of the run.
                state.authorAffiliation.putIfAbsent(authorId + "|" + affiliationId, authorAff);
                state.publicationAuthorAffiliation.putIfAbsent(
                        canonicalPublicationId + "|" + authorId + "|" + affiliationId, pubAuthorAff);
            } else {
                edgeWriterService.upsertAuthorAffiliationEdge(authorAff);
                edgeWriterService.upsertPublicationAuthorAffiliationEdge(pubAuthorAff);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
