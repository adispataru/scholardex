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
        sources.sort(Comparator.comparing(OpenAlexPublicationFact::getSourceRecordId, Comparator.nullsLast(String::compareTo)));
        for (OpenAlexPublicationFact source : sources) {
            canonicalizeOne(source, result);
        }
        return result;
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
            // LINK: enrich provenance only, never overwrite the canonical fact's (richer) content.
            ScholardexPublicationFact target = byDoi.getFirst();
            canonicalPublicationId = target.getId();
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
        } else {
            // MINT: a genuinely new (or DOI-less) publication OpenAlex contributes.
            ScholardexPublicationFact fact = new ScholardexPublicationFact();
            String titleNormalized = ScholardexPublicationCanonicalizationService.normalizeTitle(source.getTitle());
            canonicalPublicationId = publicationCanonicalizationService.buildCanonicalPublicationId(
                    null, null, null, null, doiNormalized, titleNormalized,
                    source.getCoverDate(), source.getCreator(), null);
            Instant now = Instant.now();
            fact.setId(canonicalPublicationId);
            fact.setCreatedAt(now);
            fact.setDoi(source.getDoi());
            fact.setDoiNormalized(doiNormalized);
            fact.setTitle(source.getTitle());
            fact.setTitleNormalized(titleNormalized);
            fact.setCreator(source.getCreator());
            fact.setAuthorCount(source.getAuthorCount());
            // Corresponding authors are name strings (same shape as the Scopus field); only populate on MINT —
            // linked Scopus pubs keep their own (richer, denser) corresponding-author data, never clobbered.
            if (source.getCorrespondingAuthorNames() != null && !source.getCorrespondingAuthorNames().isEmpty()) {
                fact.setCorrespondingAuthors(new ArrayList<>(source.getCorrespondingAuthorNames()));
            }
            fact.setCoverDate(source.getCoverDate());
            fact.setCitedByCount(source.getCitedByCount());
            fact.setOpenAccess(source.getOpenAccess());
            fact.setSubtype(source.getType());
            fact.setSubtypeDescription(source.getType());
            fact.setSourceEventId(source.getSourceEventId());
            publicationWriter.upsertAndLinkSource(
                    fact,
                    new CanonicalWriteProvenance(
                            SOURCE_OPENALEX,
                            workId,
                            source.getSourceBatchId(),
                            source.getSourceCorrelationId(),
                            source.getSourceEventId()),
                    LINK_REASON_OPENALEX_PUBLICATION);
            result.markImported();
        }

        writeSelfAuthorshipEdges(source, canonicalPublicationId);
    }

    /**
     * One {@code OPENALEX} authorship edge per syncing researcher's canonical author id, so a work synced by a
     * researcher's own ORCID is visible in their workspace regardless of Scopus attribution. Source-scoped key
     * ⇒ no collision with Scopus authorship edges.
     */
    private void writeSelfAuthorshipEdges(OpenAlexPublicationFact source, String canonicalPublicationId) {
        if (isBlank(canonicalPublicationId)) {
            return;
        }
        Set<String> authorIds = new LinkedHashSet<>();
        if (source.getSyncedResearcherAuthorIds() != null) {
            for (String authorId : source.getSyncedResearcherAuthorIds()) {
                if (!isBlank(authorId)) {
                    authorIds.add(authorId);
                }
            }
        }
        for (String authorId : authorIds) {
            edgeWriterService.upsertAuthorshipEdge(new ScholardexEdgeWriterService.EdgeWriteCommand(
                    canonicalPublicationId,
                    authorId,
                    SOURCE_OPENALEX,
                    source.getSourceRecordId() + "::author::" + authorId,
                    source.getSourceEventId(),
                    source.getSourceBatchId(),
                    source.getSourceCorrelationId(),
                    ScholardexSourceLinkService.STATE_LINKED,
                    LINK_REASON_OPENALEX_AUTHORSHIP,
                    false));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
