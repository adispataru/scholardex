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
    private static final String OPENALEX_URL_PREFIX = "https://openalex.org/";
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

        // (A) INCOMING: ONE batched cites: query (cites:W1|W2|… cursor-paged, ids batched internally) discovers all
        // citers; attribute each EXACTLY to the synced papers it names via referenced_works ∩ synced, and store the
        // result durably as citedByWorkIds. This is one call set instead of one-per-paper. Trade: a purely-asymmetric
        // citer (names us in OpenAlex's cites-index but not in its own reference list) is not attributed — empirically
        // ~0; the edge build's neighbor-referencedWorks pass wouldn't reach it either, so the graph stays consistent.
        List<OpenAlexWorksResponse.OpenAlexWork> citingWorks = openAlexClient.fetchCitingWorks(syncedIds);
        List<String> citingIds = openAlexImportService.upsertNeighborWorks(citingWorks, batchId, correlationId);
        Map<String, List<String>> citersByPaper = new HashMap<>();
        for (OpenAlexWorksResponse.OpenAlexWork citer : citingWorks) {
            String citerId = bareWorkId(citer.getId());
            if (citerId == null || citer.getReferenced_works() == null) {
                continue;
            }
            for (String ref : citer.getReferenced_works()) {
                String refId = bareWorkId(ref);
                if (syncedIds.contains(refId)) {
                    citersByPaper.computeIfAbsent(refId, k -> new ArrayList<>()).add(citerId);
                }
            }
        }
        for (OpenAlexPublicationFact syncedFact : syncedFacts) {
            syncedFact.setCitedByWorkIds(citersByPaper.getOrDefault(syncedFact.getSourceRecordId(), List.of())
                    .stream().distinct().collect(Collectors.toList()));
            openAlexPublicationFactRepository.save(syncedFact);
        }

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
            if (fact.getCitedByWorkIds() != null) {
                involved.addAll(fact.getCitedByWorkIds());
            }
        }
        Map<String, String> canonicalByWorkId = resolveCanonicalByWorkId(involved);

        for (OpenAlexPublicationFact fact : facts) {
            String factCanonical = canonicalByWorkId.get(fact.getSourceRecordId());
            if (factCanonical == null) {
                continue;
            }
            result.markProcessed();
            // OUTGOING: F cites R (from F's own reference list).
            if (fact.getReferencedWorks() != null) {
                for (String referencedId : fact.getReferencedWorks()) {
                    String citedCanonical = canonicalByWorkId.get(referencedId);
                    if (citedCanonical == null || citedCanonical.equals(factCanonical)) {
                        continue; // referenced work not in our corpus, or self-loop
                    }
                    writeCitation(citedCanonical, factCanonical, fact.getSourceRecordId(), fact, result);
                }
            }
            // INCOMING: C cites F (from the per-paper cites: attribution stored on synced facts).
            if (fact.getCitedByWorkIds() != null) {
                for (String citerId : fact.getCitedByWorkIds()) {
                    String citingCanonical = canonicalByWorkId.get(citerId);
                    if (citingCanonical == null || citingCanonical.equals(factCanonical)) {
                        continue; // citer not in our corpus, or self-loop
                    }
                    writeCitation(factCanonical, citingCanonical, citerId, fact, result);
                }
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

    private void writeCitation(String citedPublicationId, String citingPublicationId, String citingWorkId,
                               OpenAlexPublicationFact provenance, ImportProcessingResult result) {
        String edgeId = "scit_" + shortHash(citedPublicationId + "|" + citingPublicationId);
        if (citationFactRepository.findById(edgeId).isPresent()) {
            result.markSkipped("citation-edge-already-known");
            return;
        }
        // The unique index guards the (cited, citing) PAIR, but other import paths mint edge ids
        // with different schemes — the same pair can already exist under another id, which the
        // findById fast-path can't see. Checked at save time below (DuplicateKeyException → skip)
        // so one known edge doesn't fail the whole author sync.
        Instant now = Instant.now();
        ScholardexCitationFact fact = new ScholardexCitationFact();
        fact.setId(edgeId);
        fact.setCreatedAt(now);
        fact.setCitedPublicationId(citedPublicationId);
        fact.setCitingPublicationId(citingPublicationId);
        fact.setSource(SOURCE_OPENALEX);
        fact.setSourceRecordId(citingWorkId + "::cites::" + citedPublicationId);
        fact.setSourceEventId(provenance.getSourceEventId());
        fact.setSourceBatchId(provenance.getSourceBatchId());
        fact.setSourceCorrelationId(provenance.getSourceCorrelationId());
        fact.setUpdatedAt(now);
        try {
            citationFactRepository.save(fact);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            result.markSkipped("citation-edge-already-known");
            return;
        }
        result.markImported();
    }

    private static String bareWorkId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.startsWith(OPENALEX_URL_PREFIX) ? trimmed.substring(OPENALEX_URL_PREFIX.length()) : trimmed;
    }
}
