package ro.uvt.pokedex.core.service.dblp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.application.ForumIdentityNormalization;
import ro.uvt.pokedex.core.service.dblp.dto.DblpSearchResponse;
import ro.uvt.pokedex.core.service.importing.BuilderVersion;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.scopus.CanonicalizationSupport;
import ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H66B Phase 4b — Tier-2 DBLP conference resolve. For each hidden-conference candidate
 * ({@link DblpConferenceCandidateDetector}) it asks the DBLP API (by DOI, then title), and on a confident
 * conference match it produces BOTH outputs the platform needs:
 * <ol>
 *   <li>scorer-compatible {@link ScholardexPublicationDblpEvidence} (the {@code conferenceName}/{@code series} that
 *       {@code ComputerScienceConferenceScoringService} reads to reach a CORE rank) — same interface the retired
 *       whole-dump streamer wrote; and</li>
 *   <li>a canonical <b>conference-series forum</b> (find-or-mint by the {@code conf/X} stream key, no ISSN) whose id is
 *       stamped onto the publication's {@code forumId}, filling the venue gap Stage 3 left for ISSN-less conferences.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DblpConferenceResolveService {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private final DblpConferenceCandidateDetector candidateDetector;
    private final DblpClient dblpClient;
    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final ScholardexForumFactRepository forumFactRepository;
    private final ScholardexPublicationDblpEvidenceRepository evidenceRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;

    /** Admin batch sweep — resolve every hidden-conference candidate in the corpus (replaces the dump sweep). */
    public ImportProcessingResult resolveAll() {
        return resolve(publicationFactRepository.findAll());
    }

    /**
     * Full-rebuild durability: {@code forum_facts} is wiped + rebuilt by {@code runFull}, but the DBLP evidence is
     * durable — so re-mint each conf/X forum and re-stamp its publication's {@code forumId} straight from the stored
     * evidence, with NO DBLP API calls. Keeps the conference venues alive across a rebuild for free.
     */
    public ImportProcessingResult rebuildFromEvidence() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        Instant now = Instant.now();
        for (ScholardexPublicationDblpEvidence ev : evidenceRepository.findAll()) {
            String streamKey = ev.getSeries();
            if (streamKey == null || streamKey.isBlank() || ev.getPublicationId() == null) {
                continue;
            }
            result.markProcessed();
            String forumId = findOrMintConferenceForum(streamKey, ev.getConferenceName(), now);
            publicationFactRepository.findById(ev.getPublicationId()).ifPresent(pub -> {
                pub.setForumId(forumId);
                publicationFactRepository.save(pub);
                result.markImported();
            });
        }
        log.info("DBLP rebuild from evidence: evidence={} venuesRelinked={}",
                result.getProcessedCount(), result.getImportedCount());
        return result;
    }

    /** On-demand entry for the OpenAlex sync: resolve the canonical pubs behind the just-synced OpenAlex works. */
    public ImportProcessingResult resolveByOpenAlexWorkIds(Collection<String> openAlexWorkIds) {
        if (openAlexWorkIds == null || openAlexWorkIds.isEmpty()) {
            return new ImportProcessingResult(20);
        }
        List<String> pubIds = sourceLinkRepository
                .findByEntityTypeAndSourceRecordIdIn(ScholardexEntityType.PUBLICATION, openAlexWorkIds).stream()
                .filter(l -> "OPENALEX".equals(l.getSource()) && l.getCanonicalEntityId() != null)
                .map(ScholardexSourceLink::getCanonicalEntityId).distinct().toList();
        return resolve(publicationFactRepository.findAllById(pubIds));
    }

    /** Tier-2 — resolve the hidden-conference candidates among the given (e.g. just-synced) publications. */
    public ImportProcessingResult resolve(Collection<ScholardexPublicationFact> publications) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        List<ScholardexPublicationFact> candidates = candidateDetector.detect(publications);
        for (ScholardexPublicationFact pub : candidates) {
            result.markProcessed();
            resolveOne(pub, result);
        }
        log.info("DBLP conference resolve: candidates={} resolved={} skipped={}",
                candidates.size(), result.getImportedCount(), result.getSkippedCount());
        return result;
    }

    private void resolveOne(ScholardexPublicationFact pub, ImportProcessingResult result) {
        String matchMethod = "doi";
        DblpSearchResponse.DblpInfo hit = lookupByDoi(pub);
        if (hit == null) {
            matchMethod = "title";
            hit = lookupByTitle(pub);
        }
        if (hit == null) {
            result.markSkipped("dblp-no-conference-match pub=" + pub.getId());
            return;
        }
        boolean applied = applyMatch(pub, hit.getKey(), hit.getVenue(), hit.getDoi(), hit.getTitle(),
                parseYear(hit.getYear()), matchMethod, "api");
        if (applied) {
            result.markImported();
        } else {
            result.markSkipped("dblp-not-a-conference pub=" + pub.getId());
        }
    }

    /**
     * Apply a confirmed DBLP match to a publication — the single place the conference outputs are written, shared by
     * the API resolve and the dump sweep. On a real conference ({@code conf/X}) it writes scorer-compatible evidence,
     * find-or-mints the conference-series forum, and stamps {@code forumId}; returns {@code false} (no-op) otherwise.
     */
    public boolean applyMatch(ScholardexPublicationFact pub, String dblpKey, String conferenceName,
                              String doi, String title, Integer year, String matchMethod, String dumpVersion) {
        String streamKey = DblpClient.conferenceStreamKey(dblpKey);
        if (streamKey == null) {
            return false;
        }
        Instant now = Instant.now();
        writeEvidence(pub.getId(), dblpKey, streamKey, conferenceName, doi, title, year, matchMethod, dumpVersion, now);
        String forumId = findOrMintConferenceForum(streamKey, conferenceName, now);
        pub.setForumId(forumId);
        publicationFactRepository.save(pub);
        return true;
    }

    /** Confident DOI match: search by the pub's DOI and accept a conference hit whose DOI equals it. */
    private DblpSearchResponse.DblpInfo lookupByDoi(ScholardexPublicationFact pub) {
        String doi = pub.getDoiNormalized() != null ? pub.getDoiNormalized() : pub.getDoi();
        if (doi == null || doi.isBlank()) {
            return null;
        }
        for (DblpSearchResponse.DblpInfo info : dblpClient.search(doi)) {
            if (DblpClient.conferenceStreamKey(info.getKey()) != null
                    && doiEquals(info.getDoi(), pub.getDoi(), pub.getDoiNormalized())) {
                return info;
            }
        }
        return null;
    }

    /** Title fallback: search by the pub's title and accept a conference hit with the same title and year. */
    private DblpSearchResponse.DblpInfo lookupByTitle(ScholardexPublicationFact pub) {
        String title = pub.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }
        Integer pubYear = parseYear(pub.getCoverDate());
        String wantTitle = pub.getTitleNormalized() != null ? pub.getTitleNormalized()
                : ScholardexPublicationCanonicalizationService.normalizeTitle(title);
        for (DblpSearchResponse.DblpInfo info : dblpClient.search(title)) {
            if (DblpClient.conferenceStreamKey(info.getKey()) == null) {
                continue;
            }
            String hitTitle = ScholardexPublicationCanonicalizationService.normalizeTitle(stripTrailingDot(info.getTitle()));
            if (wantTitle != null && wantTitle.equals(hitTitle)
                    && (pubYear == null || pubYear.equals(parseYear(info.getYear())))) {
                return info;
            }
        }
        return null;
    }

    private String findOrMintConferenceForum(String streamKey, String venueName, Instant now) {
        // The stream forum is named after DBLP's stream acronym (conf/aina -> "AINA"), NEVER after an
        // evidence conferenceName: that is whichever VOLUME title happened to arrive first ("AINA Workshops",
        // "AINA (5)"), and a volume-level name on the stream-level forum mislabels every other edition's
        // papers (a "… Workshops" name even triggers the scorer's workshop reduction for main-track papers).
        String name = streamForumName(streamKey, venueName);
        var existing = forumFactRepository.findByDblpIdsContaining(streamKey);
        if (existing.isPresent()) {
            ScholardexForumFact forum = existing.get();
            if ((forum.getName() == null || forum.getName().isBlank()) && name != null && !name.isBlank()) {
                forum.setName(name);
                forum.setNameNormalized(ForumIdentityNormalization.normalizeName(name));
                forum.setUpdatedAt(now);
                forumFactRepository.save(forum);
            }
            return forum.getId();
        }
        ScholardexForumFact forum = new ScholardexForumFact();
        forum.setId("sforum_" + CanonicalizationSupport.shortHash("dblp|" + streamKey));
        forum.setName(name);
        forum.setNameNormalized(ForumIdentityNormalization.normalizeName(name));
        forum.setAggregationType("Conference Proceeding");
        forum.setAggregationTypeNormalized("conference proceeding");
        forum.setDblpIds(new ArrayList<>(List.of(streamKey)));
        forum.setBuilderVersion(BuilderVersion.SCHOLARDEX_FORUM);
        forum.setCreatedAt(now);
        forum.setUpdatedAt(now);
        forumFactRepository.save(forum);
        return forum.getId();
    }

    /** {@code conf/aina} -> {@code AINA}; falls back to the evidence venue name, then the raw stream key. */
    private static String streamForumName(String streamKey, String venueName) {
        if (streamKey != null && streamKey.startsWith("conf/")) {
            String acronym = streamKey.substring("conf/".length()).trim();
            if (!acronym.isEmpty()) {
                return acronym.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return (venueName != null && !venueName.isBlank()) ? venueName : streamKey;
    }

    private void writeEvidence(String publicationId, String dblpKey, String streamKey, String conferenceName,
                               String doi, String title, Integer year, String matchMethod, String dumpVersion, Instant now) {
        ScholardexPublicationDblpEvidence ev = evidenceRepository.findByPublicationId(publicationId)
                .orElseGet(ScholardexPublicationDblpEvidence::new);
        if (ev.getCreatedAt() == null) {
            ev.setCreatedAt(now);
        }
        ev.setPublicationId(publicationId);
        ev.setDblpKey(dblpKey);
        ev.setDumpVersion(dumpVersion); // "api" (live API) or the dump version string (corpus-matched sweep)
        ev.setMatchMethod(matchMethod);
        ev.setDoi(doi);
        ev.setTitle(title);
        ev.setYear(year);
        ev.setConferenceName(conferenceName); // the title ComputerScienceConferenceScoringService matches to CORE
        ev.setSeries(streamKey);
        ev.setSourceUrl(dblpKey == null ? null : "https://dblp.org/rec/" + dblpKey + ".html");
        ev.setUpdatedAt(now);
        evidenceRepository.save(ev);
    }

    private static boolean doiEquals(String hitDoi, String pubDoi, String pubDoiNormalized) {
        String h = normalizeDoi(hitDoi);
        return h != null && (h.equals(normalizeDoi(pubDoi)) || h.equals(pubDoiNormalized));
    }

    private static String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String d = doi.trim().toLowerCase(Locale.ROOT);
        int idx = d.indexOf("doi.org/");
        if (idx >= 0) {
            d = d.substring(idx + "doi.org/".length());
        }
        return d.isBlank() ? null : d;
    }

    private static String stripTrailingDot(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim();
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = YEAR.matcher(value);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }
}
