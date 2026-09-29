package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fills in the language of the works that were synced before the platform read it, and the country of their
 * venues. A new sync needs none of this — it stores both as it goes. Bounded per call and safe to repeat:
 * a work that got its language is no longer a candidate, and a work OpenAlex has no language for is marked
 * {@link #UNKNOWN} so it is not asked about on every run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexLanguageBackfillService {

    /** Stored when OpenAlex returns the work without a language, or does not return it at all. */
    public static final String UNKNOWN = "und";

    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";

    private final OpenAlexPublicationFactRepository publicationFactRepository;
    private final OpenAlexClient client;
    private final OpenAlexSourceCountryService sourceCountryService;

    public record Result(int candidates, int withLanguage, int withoutLanguage, int venuesAsked, int venuesWithCountry) {}

    public Result backfill(int limit) {
        int bounded = Math.max(1, Math.min(5000, limit));
        List<OpenAlexPublicationFact> candidates =
                publicationFactRepository.findSyncedWithoutLanguage(PageRequest.of(0, bounded));
        if (candidates.isEmpty()) {
            return new Result(0, 0, 0, 0, 0);
        }
        Map<String, OpenAlexPublicationFact> byWorkId = new HashMap<>();
        for (OpenAlexPublicationFact fact : candidates) {
            if (fact.getOpenalexWorkId() != null && !fact.getOpenalexWorkId().isBlank()) {
                byWorkId.put(fact.getOpenalexWorkId(), fact);
            }
        }
        Map<String, String> languageByWorkId = new HashMap<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        for (OpenAlexWorksResponse.OpenAlexWork work : client.fetchWorkLanguages(byWorkId.keySet())) {
            if (work == null || work.getId() == null) {
                continue;
            }
            String workId = work.getId().replace(OPENALEX_ID_PREFIX, "");
            if (work.getLanguage() != null && !work.getLanguage().isBlank()) {
                languageByWorkId.put(workId, work.getLanguage().trim().toLowerCase(Locale.ROOT));
            }
            if (work.getPrimary_location() != null && work.getPrimary_location().getSource() != null
                    && work.getPrimary_location().getSource().getId() != null) {
                sourceIds.add(work.getPrimary_location().getSource().getId().replace(OPENALEX_ID_PREFIX, ""));
            }
        }
        int withLanguage = 0;
        int withoutLanguage = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, OpenAlexPublicationFact> entry : byWorkId.entrySet()) {
            OpenAlexPublicationFact fact = entry.getValue();
            String language = languageByWorkId.get(entry.getKey());
            if (language != null) {
                withLanguage++;
            } else {
                withoutLanguage++;
            }
            fact.setLanguage(language != null ? language : UNKNOWN);
            fact.setUpdatedAt(now);
            if (fact.getHostVenueOpenAlexId() != null && !fact.getHostVenueOpenAlexId().isBlank()) {
                sourceIds.add(fact.getHostVenueOpenAlexId());
            }
        }
        publicationFactRepository.saveAll(byWorkId.values());
        OpenAlexSourceCountryService.Result venues = sourceCountryService.resolve(sourceIds);
        log.info("OpenAlex language backfill: candidates={} withLanguage={} withoutLanguage={} venuesAsked={} venuesWithCountry={}",
                candidates.size(), withLanguage, withoutLanguage, venues.asked(), venues.withCountry());
        return new Result(candidates.size(), withLanguage, withoutLanguage, venues.asked(), venues.withCountry());
    }
}
