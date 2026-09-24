package ro.uvt.pokedex.core.model.reporting;

import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.service.wos.WosAccessionService;

import java.util.Optional;

/**
 * DOI → WoS accession number for the CNFIS exports (kept as the injection point the facades already use).
 * Used to shell out to {@code curl} against the WoS OpenURL gateway — the container has no curl, so it
 * silently resolved nothing. Now delegates to {@link WosAccessionService}: a proper HTTP client behind a
 * spared cache, so a DOI is asked about once.
 */
@Component
public class WoSExtractor {

    private final WosAccessionService wosAccessionService;

    public WoSExtractor(WosAccessionService wosAccessionService) {
        this.wosAccessionService = wosAccessionService;
    }

    public Optional<String> resolveWosId(String doi) {
        if (doi == null || doi.isBlank()) {
            return Optional.empty();
        }
        return wosAccessionService.resolve(doi);
    }
}
