package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.doaj.DoajJournalFact;
import ro.uvt.pokedex.core.repository.doaj.DoajJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;

/**
 * H66B M4-B — onboard DOAJ (open-access) journals as a ForumBuilder identity source through
 * {@link ForumMergeEngine}, create-or-match (the same path as ERIH). A DOAJ journal that matches existing
 * forums by ISSN tags every match with its `doajIds` FK; a DOAJ-only OA journal no curated source had mints a
 * new canonical forum. Idempotent.
 *
 * <p>Was a stage-4 membership projection only (matched DOAJ journals to already-built forums by ISSN, never
 * creating). M4-B promotes it so DOAJ-only venues finally get forums, with DOAJ obeying the same strict ISSN
 * identity as every other source. The open-access *membership* view is still produced downstream. See
 * [[h66b-entity-oriented-builders]].
 */
@Service
@RequiredArgsConstructor
public class DoajOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(DoajOnboardingService.class);

    private final DoajJournalFactRepository doajJournalFactRepository;
    private final ForumMergeEngine forumMergeEngine;

    public ImportProcessingResult onboardDoaj() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        ForumMergeEngine.Context ctx = forumMergeEngine.startCreateOrTagRun();
        Instant now = Instant.now();
        for (DoajJournalFact doaj : doajJournalFactRepository.findAll()) {
            result.markProcessed();
            forumMergeEngine.ingestCreateOrTag(ForumSourceRecord.ofDoaj(doaj), ctx, null, null, now, result);
        }
        forumMergeEngine.flush(ctx);
        log.info("DOAJ onboarding complete: doajProcessed={} forumsTagged={} forumsCreated={}",
                result.getProcessedCount(), result.getUpdatedCount(), result.getImportedCount());
        return result;
    }
}
