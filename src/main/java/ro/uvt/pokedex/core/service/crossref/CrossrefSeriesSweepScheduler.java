package ro.uvt.pokedex.core.service.crossref;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

/**
 * H106 S6 — keeps the Crossref series/volume evidence accumulating between rebuilds. New Springer-ISBN
 * papers arrive daily through the Scopus and OpenAlex syncs (own papers and citing papers alike); without
 * this, their LNCS floor would rest on the DOI prefix until the next full rebuild or a manual admin sweep.
 * Only rows never asked for their series are candidates, so a quiet day costs a handful of Crossref calls.
 */
@Component
@ConditionalOnProperty(value = "core.crossref.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class CrossrefSeriesSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrossrefSeriesSweepScheduler.class);

    private final CrossrefVolumeEnrichmentService enrichmentService;

    @Value("${core.crossref.sweep.daily-limit:500}")
    private int dailyLimit;

    public CrossrefSeriesSweepScheduler(CrossrefVolumeEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    @Scheduled(cron = "${core.crossref.sweep.cron:0 20 3 * * *}")
    public void sweepDaily() {
        try {
            ImportProcessingResult result = enrichmentService.sweep(false, dailyLimit);
            log.info("Daily Crossref series sweep: candidates={} resolved={}", result.getProcessedCount(), result.getImportedCount());
        } catch (RuntimeException ex) {
            log.warn("Daily Crossref series sweep failed: {}", ex.toString());
        }
    }
}
