package ro.uvt.pokedex.core.service.crossref;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H92 — recovers the conference behind a Springer proceedings chapter by asking Crossref for the VOLUME
 * title, and stores it as venue evidence for the scorer to match against CORE.
 *
 * <p>The gap it closes: such a paper sits on a forum named for the SERIES ("Lecture Notes on Data
 * Engineering and Communications Technologies"), which names no conference, so it falls to the LNCS C
 * floor at 2 points regardless of which conference it actually was. DBLP would settle it, but DBLP has not
 * indexed most 2025/2026 Springer volumes — measured against production, only 6–20% of conference
 * publications carry DBLP evidence at all.
 *
 * <p>Deliberately narrow in three ways:
 * <ul>
 *   <li><b>Springer ISBN DOIs only</b> ({@code 10.1007/978}). {@code container-title[1]} holding the volume
 *       title is a Springer convention, verified on real records, not a Crossref guarantee — widening to
 *       other publishers should follow evidence, not assumption.</li>
 *   <li><b>Series forums only.</b> A paper already on a Conference-Proceeding forum has a venue name worth
 *       matching; this is for the ones that do not.</li>
 *   <li><b>It writes a NAME, never a venue.</b> {@code series} stays null on a Crossref-only row, so
 *       {@code DblpConferenceResolveService.rebuildFromEvidence()} skips it and no forum is minted or
 *       re-stamped from a volume title — the shape that produced the "AINA Workshops" mint accident.</li>
 * </ul>
 */
@Service
public class CrossrefVolumeEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(CrossrefVolumeEnrichmentService.class);

    /** Springer's ISBN-based DOI prefix — the record shape where container-title carries series + volume. */
    private static final String SPRINGER_ISBN_DOI_PREFIX = "10.1007/978";

    private final CrossrefClient crossrefClient;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexPublicationDblpEvidenceRepository evidenceRepository;

    public CrossrefVolumeEnrichmentService(CrossrefClient crossrefClient,
                                           ScholardexPublicationFactRepository publicationFactRepository,
                                           ScholardexPublicationDblpEvidenceRepository evidenceRepository) {
        this.crossrefClient = crossrefClient;
        this.publicationFactRepository = publicationFactRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * Sweep every candidate. {@code dryRun} performs the Crossref lookups and reports what WOULD be stored
     * without writing — the same shape as the WoS CPCI onboarding, so a run can be inspected before it
     * changes any score.
     *
     * @param limit 0 or negative for "no cap"; otherwise stop after that many candidates (for a first pass).
     */
    public ImportProcessingResult sweep(boolean dryRun, int limit) {
        return sweep(dryRun, limit, false);
    }

    /**
     * @param recheckEmpty re-try rows that were checked but came back EMPTY (no volume title). A fresh
     *     Springer DOI queried days after publication often has incomplete Crossref metadata; without a
     *     re-check such papers stay unidentified forever (the AINA-2026 chapters Florin reported —
     *     crossref-volume rows with a null volumeTitle blocked the sweep's already-checked guard).
     */
    public ImportProcessingResult sweep(boolean dryRun, int limit, boolean recheckEmpty) {
        // Logged BEFORE any work: the first prod run left zero trace when it never got this far, and
        // "did the method even run" should never again require a thread dump to answer.
        log.info("Crossref volume sweep starting: dryRun={} limit={}", dryRun, limit);
        ImportProcessingResult result = new ImportProcessingResult(30);
        Instant now = Instant.now();
        int examined = 0;

        for (ScholardexPublicationFact pub : publicationFactRepository.findAll()) {
            if (limit > 0 && examined >= limit) {
                break;
            }
            if (!isCandidate(pub, recheckEmpty)) {
                continue;
            }
            examined++;
            result.markProcessed();
            Optional<CrossrefClient.ContainerTitles> titles = crossrefClient.containerTitles(pub.getDoi());
            if (titles.isEmpty()) {
                result.markSkipped("no-crossref-record pub=" + pub.getId());
                if (!dryRun) {
                    // Stamp the attempt even on a miss, so a re-run does not re-ask Crossref forever.
                    store(pub, null, null, now);
                }
                continue;
            }
            if (dryRun) {
                log.info("Crossref dry-run: pub={} doi={} series={} volumeTitle={}",
                        pub.getId(), pub.getDoi(), titles.get().series(), titles.get().volume());
            } else {
                store(pub, titles.get().series(), titles.get().volume(), now);
            }
            result.markImported();
        }
        log.info("Crossref volume sweep ({}): candidates={} resolved={} unresolved={}",
                dryRun ? "dry-run" : "apply", result.getProcessedCount(), result.getImportedCount(),
                result.getProcessedCount() - result.getImportedCount());
        return result;
    }

    /**
     * H106 S6 — a candidate is EVERY Springer-ISBN paper whose Crossref series we do not hold yet, whatever
     * its forum (a bare re-stamped conference acronym, a series, or none at all) and whether or not DBLP
     * named its conference: the series decides the LNCS floor, which is orthogonal to the conference
     * identity. H92's narrower rule (series forums only, no DBLP evidence) is why ~800 floored citations
     * never had a Crossref record. A row checked in the H92 era (volume only) is re-asked once for its
     * series; a row that has been asked for its series is done unless {@code recheckEmpty} asks for
     * incomplete rows again.
     */
    private boolean isCandidate(ScholardexPublicationFact pub, boolean recheckEmpty) {
        String doi = pub.getDoi() == null ? "" : pub.getDoi().toLowerCase(java.util.Locale.ROOT);
        if (!doi.contains(SPRINGER_ISBN_DOI_PREFIX)) {
            return false;
        }
        Optional<ScholardexPublicationDblpEvidence> existing = evidenceRepository.findByPublicationId(pub.getId());
        if (existing.isEmpty()) {
            return true;
        }
        ScholardexPublicationDblpEvidence ev = existing.get();
        boolean seriesKnown = ev.getCrossrefSeries() != null && !ev.getCrossrefSeries().isBlank();
        boolean volumeKnown = ev.getVolumeTitle() != null && !ev.getVolumeTitle().isBlank();
        boolean askedForSeries = ev.getCrossrefSeriesCheckedAt() != null;
        if (!seriesKnown && !askedForSeries) {
            return true;
        }
        return recheckEmpty && (!seriesKnown || !volumeKnown);
    }

    /** Upsert ONLY the Crossref fields; DBLP owns series/conferenceName and must not be disturbed. */
    private void store(ScholardexPublicationFact pub, String series, String volumeTitle, Instant now) {
        ScholardexPublicationDblpEvidence ev = evidenceRepository.findByPublicationId(pub.getId())
                .orElseGet(ScholardexPublicationDblpEvidence::new);
        if (ev.getCreatedAt() == null) {
            ev.setCreatedAt(now);
        }
        ev.setPublicationId(pub.getId());
        if (ev.getDoi() == null) {
            ev.setDoi(pub.getDoi());
        }
        if (ev.getTitle() == null) {
            ev.setTitle(pub.getTitle());
        }
        if (ev.getMatchMethod() == null) {
            ev.setMatchMethod("crossref-volume");
        }
        // Never erase a value a previous run stored with a later miss (Crossref records do go incomplete).
        if (volumeTitle != null) {
            ev.setVolumeTitle(volumeTitle);
        }
        if (series != null) {
            ev.setCrossrefSeries(series);
        }
        ev.setCrossrefCheckedAt(now);
        ev.setCrossrefSeriesCheckedAt(now);
        ev.setUpdatedAt(now);
        evidenceRepository.save(ev);
    }

    /** Candidate count without calling Crossref — for sizing a run before starting it. */
    public long countCandidates() {
        List<ScholardexPublicationFact> all = publicationFactRepository.findAll();
        return all.stream().filter(p -> isCandidate(p, false)).count();
    }
}
