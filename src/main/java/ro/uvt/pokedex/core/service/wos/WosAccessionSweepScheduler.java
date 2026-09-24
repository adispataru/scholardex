package ro.uvt.pokedex.core.service.wos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Nightly backfill of WoS accession numbers for the platform's own researchers' publications, so a CNFIS
 * export (individual or institutional) finds them stored instead of resolving them one by one on click, and
 * so the "ISI Proceedings" flag — which needs a WoS id on a conference paper — is available at all.
 * Bounded per night; a DOI already answered (found, or "no record" within the retry window) costs no call.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "wos.openurl.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class WosAccessionSweepScheduler {

    static final String SOURCE = "WOS_OPENURL";
    static final String LINKER_VERSION = "wos-openurl-v1";

    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final CacheService cacheService;
    private final WosAccessionService wosAccessionService;
    private final PublicationEnrichmentLinkerService linkerService;

    @Value("${wos.openurl.sweep.nightly-limit:400}")
    private int nightlyLimit = 400;

    public WosAccessionSweepScheduler(ScholardexPublicationFactRepository publicationFactRepository,
                                      CacheService cacheService,
                                      WosAccessionService wosAccessionService,
                                      PublicationEnrichmentLinkerService linkerService) {
        this.publicationFactRepository = publicationFactRepository;
        this.cacheService = cacheService;
        this.wosAccessionService = wosAccessionService;
        this.linkerService = linkerService;
    }

    @Scheduled(cron = "${wos.openurl.sweep.cron:0 50 3 * * *}")
    public void sweepNightly() {
        try {
            Result r = sweep(nightlyLimit);
            log.info("Nightly WoS accession sweep: candidates={} asked={} found={} linked={}",
                    r.candidates, r.asked, r.found, r.linked);
        } catch (RuntimeException ex) {
            log.warn("Nightly WoS accession sweep failed: {}", ex.toString());
        }
    }

    public record Result(int candidates, int asked, int found, int linked) {}

    /** @return counts; {@code limit} caps the DOIs asked in this run */
    public Result sweep(int limit) {
        Map<String, ScholardexPublicationFact> candidates = new LinkedHashMap<>();
        for (String authorId : cacheService.getUniversityAuthorIds()) {
            for (ScholardexPublicationFact pub : publicationFactRepository.findByAuthorIdsContains(authorId)) {
                if (pub.getDoi() != null && !pub.getDoi().isBlank()
                        && (pub.getWosId() == null || pub.getWosId().isBlank())) {
                    candidates.putIfAbsent(pub.getId(), pub);
                }
            }
        }
        int asked = 0;
        int found = 0;
        int linked = 0;
        String runId = "wos-openurl-sweep-" + System.currentTimeMillis();
        for (ScholardexPublicationFact pub : candidates.values()) {
            if (asked >= Math.max(0, limit)) {
                break;
            }
            asked++;
            Optional<String> wosId = wosAccessionService.resolve(pub.getDoi());
            if (wosId.isEmpty()) {
                continue;
            }
            found++;
            PublicationEnrichmentLinkerService.LinkResult result = linkerService.linkWosEnrichment(
                    pub.getId(), pub.getEid(), pub.getDoi(), wosId.get(), SOURCE, LINKER_VERSION, runId);
            if (result != null && result.state() == PublicationEnrichmentLinkerService.LinkState.LINKED) {
                linked++;
            }
        }
        return new Result(candidates.size(), asked, found, linked);
    }
}
