package ro.uvt.pokedex.core.service.openalex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexWorksResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fills in the language of the works that reached the platform before it read the language, and the country
 * of their venues. Two kinds of works are covered:
 * <ol>
 *   <li>the ones a researcher synced personally;</li>
 *   <li>the ones signed by a researcher of the platform, however they reached the corpus. Most publications
 *       come from the bulk import, not from a personal sync: a researcher sees them, confirms them and is
 *       scored on them without ever pressing "sync", so those works are the ones that matter most.</li>
 * </ol>
 * Works of third parties (cited and citing papers) are left alone: nothing scores them by language.
 *
 * <p>A new sync needs none of this — it stores both as it goes. One pass is bounded and safe to repeat: a work
 * that got its language is no longer a candidate, and a work OpenAlex has no language for is marked
 * {@link #UNKNOWN} so it is not asked about on every run.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAlexLanguageBackfillService {

    /** Stored when OpenAlex returns the work without a language, or does not return it at all. */
    public static final String UNKNOWN = "und";

    static final String SOURCE_OPENALEX = "OPENALEX";
    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";
    private static final int CHUNK = 500;

    private final OpenAlexPublicationFactRepository publicationFactRepository;
    private final OpenAlexClient client;
    private final OpenAlexSourceCountryService sourceCountryService;
    private final CacheService cacheService;
    private final ScholardexPublicationFactRepository canonicalPublicationRepository;
    private final ScholardexSourceLinkRepository sourceLinkRepository;

    public record Result(int candidates, int withLanguage, int withoutLanguage, int venuesAsked, int venuesWithCountry) {}

    public Result backfill(int limit) {
        int bounded = Math.max(1, Math.min(5000, limit));
        Map<String, OpenAlexPublicationFact> byWorkId = new LinkedHashMap<>();
        for (OpenAlexPublicationFact fact : publicationFactRepository.findSyncedWithoutLanguage(PageRequest.of(0, bounded))) {
            keep(byWorkId, fact);
        }
        if (byWorkId.size() < bounded) {
            addWorksOfTheUniversity(byWorkId, bounded);
        }
        if (byWorkId.isEmpty()) {
            return new Result(0, 0, 0, 0, 0);
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
                byWorkId.size(), withLanguage, withoutLanguage, venues.asked(), venues.withCountry());
        return new Result(byWorkId.size(), withLanguage, withoutLanguage, venues.asked(), venues.withCountry());
    }

    /**
     * The OpenAlex works behind the publications of the platform's researchers: researcher → author ids →
     * publications → their OPENALEX source links → the works that still have no language. Walked in chunks
     * and left as soon as the pass is full.
     */
    private void addWorksOfTheUniversity(Map<String, OpenAlexPublicationFact> byWorkId, int bounded) {
        Set<String> authorIds = cacheService.getUniversityAuthorIds();
        if (authorIds == null || authorIds.isEmpty()) {
            return;
        }
        List<String> publicationIds = canonicalPublicationRepository.findIdsByAuthorIdsIn(new ArrayList<>(authorIds))
                .stream()
                .map(ScholardexPublicationFact::getId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        for (int from = 0; from < publicationIds.size() && byWorkId.size() < bounded; from += CHUNK) {
            List<String> chunk = publicationIds.subList(from, Math.min(from + CHUNK, publicationIds.size()));
            List<String> workIds = sourceLinkRepository
                    .findByEntityTypeAndSourceAndCanonicalEntityIdIn(ScholardexEntityType.PUBLICATION, SOURCE_OPENALEX, chunk)
                    .stream()
                    .map(ScholardexSourceLink::getSourceRecordId)
                    .filter(id -> id != null && !id.isBlank() && !byWorkId.containsKey(id))
                    .distinct()
                    .toList();
            if (workIds.isEmpty()) {
                continue;
            }
            for (OpenAlexPublicationFact fact : publicationFactRepository.findBySourceRecordIdIn(workIds)) {
                if (fact.getLanguage() == null || fact.getLanguage().isBlank()) {
                    keep(byWorkId, fact);
                    if (byWorkId.size() >= bounded) {
                        return;
                    }
                }
            }
        }
    }

    private static void keep(Map<String, OpenAlexPublicationFact> byWorkId, OpenAlexPublicationFact fact) {
        if (fact != null && fact.getOpenalexWorkId() != null && !fact.getOpenalexWorkId().isBlank()) {
            byWorkId.putIfAbsent(fact.getOpenalexWorkId(), fact);
        }
    }
}
