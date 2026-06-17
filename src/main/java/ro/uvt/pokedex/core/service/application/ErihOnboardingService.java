package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.erih.ErihJournalFact;
import ro.uvt.pokedex.core.repository.erih.ErihJournalFactRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.time.Instant;

/**
 * H66 A5 / H66B M3 — onboard ERIH+ journals as a ForumBuilder source through {@link ForumMergeEngine}.
 * Create-or-match: an ERIH journal that matches existing forums by ISSN tags <b>every</b> match with its
 * erihId (the split-journal signal {@link ScholardexForumDeduplicationService} later clusters on — fan-out,
 * not conflict); an ERIH-only journal with no matching forum mints a new canonical forum. Idempotent.
 *
 * <p>Was a bespoke match-only {@code erihIds} writer (H66 A5); H66B M3 routes it through the unified engine
 * so ERIH-only humanities venues finally get forums (non-STEM coverage) and ERIH obeys the same ISSN
 * identity rules (check-digit-validated, H57 token hygiene) as every other forum source. Run order on a
 * rebuild: rebuild forums → onboard ERIH → dedup. See [[h66b-entity-oriented-builders]].
 */
@Service
@RequiredArgsConstructor
public class ErihOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(ErihOnboardingService.class);

    private final ErihJournalFactRepository erihJournalFactRepository;
    private final ForumMergeEngine forumMergeEngine;

    public ImportProcessingResult onboardErih() {
        ImportProcessingResult result = new ImportProcessingResult(20);
        ForumMergeEngine.Context ctx = forumMergeEngine.startErihRun();
        Instant now = Instant.now();
        for (ErihJournalFact erih : erihJournalFactRepository.findAll()) {
            result.markProcessed();
            forumMergeEngine.ingestErih(ForumSourceRecord.ofErih(erih), ctx, null, null, now, result);
        }
        forumMergeEngine.flush(ctx);
        log.info("ERIH onboarding complete: erihProcessed={} forumsTagged={} forumsCreated={}",
                result.getProcessedCount(), result.getUpdatedCount(), result.getImportedCount());
        return result;
    }
}
