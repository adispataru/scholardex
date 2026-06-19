package ro.uvt.pokedex.core.service.importing.scopus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexCitationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.openalex.OpenAlexClient;
import ro.uvt.pokedex.core.service.openalex.OpenAlexImportService;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport.shortHash;

/**
 * H66B Phase 4a Stage 2 (+ Ext A/B) — the OpenAlex citation graph. A citation is one rule: source-fact F cites
 * referenced work R ⇒ {@code ScholardexCitationFact(citing=F, cited=R)} whenever both resolve to canonical pubs.
 *
 * <p>The on-demand path first <b>expands the citation neighborhood</b> around a researcher's synced works so those
 * endpoints exist: (B) mint each referenced (cited) paper, and (A) fetch the works that CITE the synced papers
 * (OpenAlex {@code cites:} filter) and mint them too — both as bare neighbor source-facts. Then it builds edges. The
 * full-rebuild path skips fetching (neighbors are durable source-facts) and just rebuilds edges. Edges only ever link
 * <i>already-canonical</i> pubs — minting is one level out, so the graph never expands recursively.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexCitationCanonicalizationService {

    private static final String SOURCE_OPENALEX = "OPENALEX";
    private static final int LOOKUP_CHUNK = 1_000;

    private final OpenAlexPublicationFactRepository openAlexPublicationFactRepository;
    private final OpenAlexImportService openAlexImportService;
    private final OpenAlexClient openAlexClient;
    private final OpenAlexCanonicalizationService openAlexCanonicalizationService;
    private final ScholardexCitationFactRepository citationFactRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;

    /** Full-rebuild replay: rebuild edges from all (durable) OpenAlex source-facts — no fetching. */
    public ImportProcessingResult rebuildCitationFacts() {
        return buildEdges(new ArrayList<>(openAlexPublicationFactRepository.findAll()));
    }

    /** On-demand: expand the neighborhood around the synced works (mint cited + citing papers), then build edges. */
    public ImportProcessingResult resolve(Collection<String> syncedWorkIds) {
        if (syncedWorkIds == null || syncedWorkIds.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        List<OpenAlexPublicationFact> syncedFacts =
                new ArrayList<>(openAlexPublicationFactRepository.findBySourceRecordIdIn(syncedWorkIds));
        if (syncedFacts.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        String batchId = syncedFacts.getFirst().getSourceBatchId();
        String correlationId = syncedFacts.getFirst().getSourceCorrelationId();
        Set<String> syncedIds = syncedFacts.stream().map(OpenAlexPublicationFact::getSourceRecordId).collect(Collectors.toSet());

        // (A) INCOMING: works that cite the synced papers — mint them; their referencedWorks carry the back-edge.
        List<OpenAlexWorksResponse.OpenAlexWork> citingWorks = openAlexClient.fetchCitingWorks(syncedIds);
        List<String> citingIds = openAlexImportService.upsertNeighborWorks(citingWorks, batchId, correlationId);

        // (B) OUTGOING: referenced (cited) papers not yet known — fetch + mint them.
        Set<String> referencedIds = new LinkedHashSet<>();
        for (OpenAlexPublicationFact fact : syncedFacts) {
            if (fact.getReferencedWorks() != null) {
                referencedIds.addAll(fact.getReferencedWorks());
            }
        }
        Set<String> alreadyKnown = openAlexPublicationFactRepository.findBySourceRecordIdIn(referencedIds).stream()
                .map(OpenAlexPublicationFact::getSourceRecordId).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(referencedIds);
        missing.removeAll(alreadyKnown);
        if (!missing.isEmpty()) {
            openAlexImportService.upsertNeighborWorks(openAlexClient.fetchWorksByIds(missing), batchId, correlationId);
        }

        // Canonicalize the freshly-minted neighbors so they (and their OPENALEX source-links) exist.
        Set<String> neighborIds = new LinkedHashSet<>(citingIds);
        neighborIds.addAll(referencedIds);
        if (!neighborIds.isEmpty()) {
            openAlexCanonicalizationService.resolve(neighborIds);
        }

        // Build edges over the synced works (outgoing) + the citing neighbors (incoming).
        List<OpenAlexPublicationFact> edgeFacts = new ArrayList<>(syncedFacts);
        edgeFacts.addAll(openAlexPublicationFactRepository.findBySourceRecordIdIn(citingIds));
        return buildEdges(edgeFacts);
    }

    /** For every fact F and every referenced work R it cites, write the edge when both resolve to canonical pubs. */
    private ImportProcessingResult buildEdges(List<OpenAlexPublicationFact> facts) {
        ImportProcessingResult result = new ImportProcessingResult(20);

        Set<String> involved = new LinkedHashSet<>();
        for (OpenAlexPublicationFact fact : facts) {
            involved.add(fact.getSourceRecordId());
            if (fact.getReferencedWorks() != null) {
                involved.addAll(fact.getReferencedWorks());
            }
        }
        Map<String, String> canonicalByWorkId = resolveCanonicalByWorkId(involved);

        for (OpenAlexPublicationFact fact : facts) {
            String citingCanonical = canonicalByWorkId.get(fact.getSourceRecordId());
            if (citingCanonical == null || fact.getReferencedWorks() == null) {
                continue;
            }
            result.markProcessed();
            for (String referencedId : fact.getReferencedWorks()) {
                String citedCanonical = canonicalByWorkId.get(referencedId);
                if (citedCanonical == null || citedCanonical.equals(citingCanonical)) {
                    continue; // referenced work not in our corpus, or self-loop
                }
                writeCitation(citedCanonical, citingCanonical, fact, result);
            }
        }
        log.info("OpenAlex citation edge build: facts={} canonicalWorks={} edgesWritten={} skipped={}",
                facts.size(), canonicalByWorkId.size(), result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    /** Resolve OpenAlex work ids → canonical pub ids via the OPENALEX publication source-links (chunked $in). */
    private Map<String, String> resolveCanonicalByWorkId(Set<String> workIds) {
        Map<String, String> map = new HashMap<>();
        List<String> ids = new ArrayList<>(workIds);
        for (int from = 0; from < ids.size(); from += LOOKUP_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + LOOKUP_CHUNK, ids.size()));
            for (ScholardexSourceLink link : sourceLinkRepository
                    .findByEntityTypeAndSourceRecordIdIn(ScholardexEntityType.PUBLICATION, chunk)) {
                if (SOURCE_OPENALEX.equals(link.getSource()) && link.getCanonicalEntityId() != null) {
                    map.put(link.getSourceRecordId(), link.getCanonicalEntityId());
                }
            }
        }
        return map;
    }

    private void writeCitation(String citedPublicationId, String citingPublicationId, OpenAlexPublicationFact citing, ImportProcessingResult result) {
        String edgeId = "scit_" + shortHash(citedPublicationId + "|" + citingPublicationId);
        if (citationFactRepository.findById(edgeId).isPresent()) {
            result.markSkipped("citation-edge-already-known");
            return;
        }
        Instant now = Instant.now();
        ScholardexCitationFact fact = new ScholardexCitationFact();
        fact.setId(edgeId);
        fact.setCreatedAt(now);
        fact.setCitedPublicationId(citedPublicationId);
        fact.setCitingPublicationId(citingPublicationId);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(citing.getSourceRecordId() + "::cites::" + citedPublicationId);
        fact.setSourceEventId(citing.getSourceEventId());
        fact.setSourceBatchId(citing.getSourceBatchId());
        fact.setSourceCorrelationId(citing.getSourceCorrelationId());
        fact.setUpdatedAt(now);
        citationFactRepository.save(fact);
        result.markImported();
    }
}
