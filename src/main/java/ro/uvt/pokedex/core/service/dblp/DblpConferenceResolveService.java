package ro.uvt.pokedex.core.service.dblp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationDblpEvidence;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationDblpEvidenceRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
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

    /** Admin batch sweep — resolve every hidden-conference candidate in the corpus (replaces the dump sweep). */
    public ImportProcessingResult resolveAll() {
        return resolve(publicationFactRepository.findAll());
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
        String streamKey = DblpClient.conferenceStreamKey(hit.getKey());
        if (streamKey == null) {
            result.markSkipped("dblp-not-a-conference pub=" + pub.getId());
            return;
        }
        Instant now = Instant.now();
        writeEvidence(pub, hit, streamKey, matchMethod, now);
        String forumId = findOrMintConferenceForum(streamKey, hit.getVenue(), now);
        pub.setForumId(forumId);
        publicationFactRepository.save(pub);
        result.markImported();
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
        var existing = forumFactRepository.findByDblpIdsContaining(streamKey);
        if (existing.isPresent()) {
            ScholardexForumFact forum = existing.get();
            if ((forum.getName() == null || forum.getName().isBlank()) && venueName != null && !venueName.isBlank()) {
                forum.setName(venueName);
                forum.setNameNormalized(ForumIdentityNormalization.normalizeName(venueName));
                forum.setUpdatedAt(now);
                forumFactRepository.save(forum);
            }
            return forum.getId();
        }
        String name = (venueName != null && !venueName.isBlank()) ? venueName : streamKey;
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

    private void writeEvidence(ScholardexPublicationFact pub, DblpSearchResponse.DblpInfo hit, String streamKey,
                               String matchMethod, Instant now) {
        ScholardexPublicationDblpEvidence ev = evidenceRepository.findByPublicationId(pub.getId())
                .orElseGet(ScholardexPublicationDblpEvidence::new);
        if (ev.getCreatedAt() == null) {
            ev.setCreatedAt(now);
        }
        ev.setPublicationId(pub.getId());
        ev.setDblpKey(hit.getKey());
        ev.setDumpVersion("api"); // sourced from the live API, not the dump
        ev.setMatchMethod(matchMethod);
        ev.setDoi(hit.getDoi());
        ev.setTitle(hit.getTitle());
        ev.setYear(parseYear(hit.getYear()));
        ev.setConferenceName(hit.getVenue()); // the title ComputerScienceConferenceScoringService matches to CORE
        ev.setSeries(streamKey);
        ev.setSourceUrl(hit.getUrl());
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
