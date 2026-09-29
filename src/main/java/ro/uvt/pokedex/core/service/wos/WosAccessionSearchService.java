package ro.uvt.pokedex.core.service.wos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.application.PublicationEnrichmentLinkerService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * H129 — the search for WoS accession numbers of the platform's own researchers' publications, started by a
 * platform admin ({@code POST /admin/initialization/wos/accession/search}). It asks Clarivate's link resolver,
 * so it runs when somebody decides it should — before a CNFIS reporting, after new publications came in —
 * and no longer on a schedule (it was a nightly, then a weekly sweep: H114, H122); a download never asks.
 * <p>
 * Only publications of the university's authors are candidates, never the papers that cite them. Bounded per
 * run; a DOI already answered (found, or "no record" within the retry window) costs no call.
 */
@Slf4j
@Component
public class WosAccessionSearchService {

    static final String SOURCE = "WOS_OPENURL";
    public static final int DEFAULT_LIMIT = 400;
    public static final int MAX_LIMIT = 2000;
    static final String LINKER_VERSION = "wos-openurl-v1";

    private final ScholardexPublicationFactRepository publicationFactRepository;
    private final CacheService cacheService;
    private final WosAccessionService wosAccessionService;
    private final PublicationEnrichmentLinkerService linkerService;

    public WosAccessionSearchService(ScholardexPublicationFactRepository publicationFactRepository,
                                      CacheService cacheService,
                                      WosAccessionService wosAccessionService,
                                      PublicationEnrichmentLinkerService linkerService) {
        this.publicationFactRepository = publicationFactRepository;
        this.cacheService = cacheService;
        this.wosAccessionService = wosAccessionService;
        this.linkerService = linkerService;
    }

    public record Result(int candidates, int asked, int found, int linked) {}

    /** @return counts; {@code limit} caps the DOIs asked in this run */
    public Result search(int limit) {
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
        String runId = "wos-openurl-search-" + System.currentTimeMillis();
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
