package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.journaldb.JournalDatabaseJournalFact;
import ro.uvt.pokedex.core.repository.journaldb.JournalDatabaseJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;

/**
 * H142 slice 4 — onboard the journals of the journal databases' title lists as a ForumBuilder identity source through
 * {@link ForumMergeEngine}, create-or-match, the same path as DOAJ: a journal that matches existing forums by ISSN tags
 * every match with its {@code journalDatabaseIds} FK; a journal only the lists know (most music journals) mints a
 * canonical forum, so a researcher who names it by ISSN finds it. The membership itself is projected downstream, by
 * ISSN. Idempotent.
 */
@Service
@RequiredArgsConstructor
public class JournalDatabaseOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(JournalDatabaseOnboardingService.class);

    private final JournalDatabaseJournalFactRepository journalDatabaseJournalFactRepository;
    private final ForumMergeEngine forumMergeEngine;

    public ImportProcessingResult onboardJournalDatabases() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        ForumMergeEngine.Context ctx = forumMergeEngine.startCreateOrTagRun();
        Instant now = Instant.now();
        for (JournalDatabaseJournalFact fact : journalDatabaseJournalFactRepository.findAll()) {
            result.markProcessed();
            forumMergeEngine.ingestCreateOrTag(ForumSourceRecord.ofJournalDatabase(fact), ctx, null, null, now, result);
        }
        forumMergeEngine.flush(ctx);
        log.info("Journal-database onboarding complete: processed={} forumsTagged={} forumsCreated={}",
                result.getProcessedCount(), result.getUpdatedCount(), result.getImportedCount());
        return result;
    }
}
