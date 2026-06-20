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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String LINK_REASON_OPENALEX_PUBLICATION = "openalex-fact-bridge";
    private static final String LINK_REASON_OPENALEX_AUTHORSHIP = "openalex-authorship-self";
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

    /** Full-rebuild replay: canonicalize every OpenAlex source-fact. */
    public ImportProcessingResult rebuildCanonicalFacts() {
        return canonicalize(new ArrayList<>(openAlexPublicationFactRepository.findAll()));
    }

    /** On-demand (Tier-2): canonicalize just the source-facts touched by a sync batch. */
    public ImportProcessingResult resolve(Collection<String> sourceRecordIds) {
        if (sourceRecordIds == null || sourceRecordIds.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        return canonicalize(new ArrayList<>(openAlexPublicationFactRepository.findBySourceRecordIdIn(sourceRecordIds)));
    }

    private ImportProcessingResult canonicalize(List<OpenAlexPublicationFact> sources) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        // Stage 3: resolve/mint the host-venue forums first (ISSN-gated, flushed) so canonicalizeOne can stamp forumId.
        forumOnboardingService.onboard(sources);
        sources.sort(Comparator.comparing(OpenAlexPublicationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
        for (OpenAlexPublicationFact source : sources) {
            canonicalizeOne(source, result);
        }
        return result;
    }

    /** Stage 3: resolve a publication's host venue to its canonical forum via the venue's openAlexIds tag. */
    private String resolveForumId(String hostVenueOpenAlexId) {
        if (hostVenueOpenAlexId == null || hostVenueOpenAlexId.isBlank()) {
            return null;
        }
        return forumFactRepository.findByOpenAlexIdsContaining(hostVenueOpenAlexId)
                .map(ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact::getId).orElse(null);
    }

    private void canonicalizeOne(OpenAlexPublicationFact source, ImportProcessingResult result) {
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

        writeAuthorshipEdges(source, canonicalPublicationId);
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
    private void writeAuthorshipEdges(OpenAlexPublicationFact source, String canonicalPublicationId) {
        if (isBlank(canonicalPublicationId)) {
            return;
        }
        List<OpenAlexPublicationFact.AuthorRef> authorships =
                source.getAuthorships() == null ? List.of() : source.getAuthorships();

        // 1) Seed ORCIDs onto the syncing researchers' canonical authors.
        List<String> syncingAuthorIds = new ArrayList<>();
        if (source.getSyncedResearchers() != null) {
            for (OpenAlexPublicationFact.SyncedResearcher researcher : source.getSyncedResearchers()) {
                if (researcher == null || isBlank(researcher.getCanonicalAuthorId())) {
                    continue;
                }
                authorResolver.attachOrcid(researcher.getCanonicalAuthorId(), researcher.getOrcid());
                syncingAuthorIds.add(researcher.getCanonicalAuthorId());
            }
        }

        // 2) Positional ORCID bridge onto the linked Scopus pub's authors (runs before corresponding resolution).
        ScholardexPublicationFact pub = scholardexPublicationFactRepository.findById(canonicalPublicationId).orElse(null);
        if (pub != null && pub.getAuthorIds() != null && !pub.getAuthorIds().isEmpty() && !authorships.isEmpty()) {
            List<String> openAlexNames = authorships.stream().map(OpenAlexPublicationFact.AuthorRef::getDisplayName).toList();
            List<String> openAlexOrcids = authorships.stream().map(OpenAlexPublicationFact.AuthorRef::getOrcid).toList();
            authorResolver.bridgeOrcidsByPosition(pub.getAuthorIds(), openAlexNames, openAlexOrcids);
        }

        // 3) Resolve authorships to canonical authors. For an OpenAlex-OWNED pub (we mint it; OpenAlex is its
        //    authority) resolve the FULL ordered author list. For a FOREIGN (Scopus-owned) pub resolve only the
        //    corresponding authors — enrichment that must never clobber the richer Scopus author list.
        boolean openAlexOwned = pub != null && SOURCE_OPENALEX.equals(pub.getSource());
        LinkedHashSet<String> resolvedAuthorIds = new LinkedHashSet<>();   // OpenAlex author order
        Set<String> correspondingAuthorIds = new LinkedHashSet<>();
        for (OpenAlexPublicationFact.AuthorRef ref : authorships) {
            boolean corresponding = ref.isCorresponding();
            if (!openAlexOwned && !corresponding) {
                continue;
            }
            String authorId = authorResolver.resolveOrMint(
                    ref.getDisplayName(), ref.getOrcid(), ref.getOpenAlexAuthorId(),
                    source.getSourceBatchId(), source.getSourceCorrelationId());
            if (!isBlank(authorId)) {
                resolvedAuthorIds.add(authorId);
                if (corresponding) {
                    correspondingAuthorIds.add(authorId);
                }
            }
        }

        // 4) One edge per author (syncing researchers ∪ resolved authors), corresponding flag where applicable.
        LinkedHashSet<String> edgeAuthorIds = new LinkedHashSet<>(syncingAuthorIds);
        edgeAuthorIds.addAll(resolvedAuthorIds);
        for (String authorId : edgeAuthorIds) {
            boolean corresponding = correspondingAuthorIds.contains(authorId);
            edgeWriterService.upsertAuthorshipEdge(
                    new ScholardexEdgeWriterService.EdgeWriteCommand(
                            canonicalPublicationId,
                            authorId,
                            SOURCE_OPENALEX,
                            source.getSourceRecordId() + "::author::" + authorId,
                            source.getSourceEventId(),
                            source.getSourceBatchId(),
                            source.getSourceCorrelationId(),
                            ScholardexSourceLinkService.STATE_LINKED,
                            LINK_REASON_OPENALEX_AUTHORSHIP,
                            false),
                    corresponding ? Boolean.TRUE : Boolean.FALSE);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
