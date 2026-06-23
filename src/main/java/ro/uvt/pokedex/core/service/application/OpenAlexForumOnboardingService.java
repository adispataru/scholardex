package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H66B Phase 4a Stage 3 — onboard OpenAlex host venues as a ForumBuilder create-or-match identity source
 * through {@link ForumMergeEngine} (same path as ERIH/DOAJ). For each publication's host venue it matches
 * existing canonical forums by ISSN — tagging the forum's {@code openAlexIds} FK — or mints a new forum for an
 * OpenAlex-only journal.
 *
 * <p><b>ISSN-gated by design:</b> a venue with no ISSN (most conferences) is skipped, never minted name-only —
 * matching a conference by display name would explode duplicates. CS conference identity is DBLP's job
 * (Phase 4b); this stage resolves the journal/ISSN-bearing tail. Each venue is onboarded once per run.
 */
@Service
@RequiredArgsConstructor
public class OpenAlexForumOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexForumOnboardingService.class);

    private final ForumMergeEngine forumMergeEngine;

    /** Onboard the venues carried by the given OpenAlex publication facts (the synced + neighbor source-facts). */
    public ImportProcessingResult onboard(Collection<OpenAlexPublicationFact> pubs) {
        ImportProcessingResult result = new ImportProcessingResult(20);
        ForumMergeEngine.Context ctx = forumMergeEngine.startCreateOrTagRun();
        Instant now = Instant.now();
        Set<String> seenVenues = new HashSet<>();
        for (OpenAlexPublicationFact pub : pubs) {
            String venueId = pub.getHostVenueOpenAlexId();
            List<String> issns = pub.getHostVenueIssns();
            if (venueId == null || venueId.isBlank() || issns == null || issns.isEmpty()) {
                continue; // ISSN-gate: no stable venue id or no ISSN — skip (conferences are DBLP's job)
            }
            if (!seenVenues.add(venueId)) {
                continue; // each distinct venue onboarded once per run
            }
            result.markProcessed();
            forumMergeEngine.ingestCreateOrTag(
                    ForumSourceRecord.ofOpenAlex(venueId, pub.getHostVenueName(), issns, pub.getHostVenueSourceType()),
                    ctx, null, null, now, result);
        }
        forumMergeEngine.flush(ctx);
        log.info("OpenAlex venue onboarding: venues={} forumsTagged={} forumsCreated={} skipped={}",
                result.getProcessedCount(), result.getUpdatedCount(), result.getImportedCount(), result.getSkippedCount());
        return result;
    }
}
