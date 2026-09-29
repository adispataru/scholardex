package ro.uvt.pokedex.core.service.openalex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexSourceCountry;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexSourceCountryRepository;
import ro.uvt.pokedex.core.service.openalex.dto.OpenAlexSourcesResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where an OpenAlex venue is published. {@link #countryOf} reads what is stored and never calls out — it is
 * used while scoring; {@link #resolve} is the only place that asks OpenAlex, and it is called by the sync and
 * by the backfill.
 *
 * <p>An answer is final when it carries a country. "No country" is kept too, and asked again only after
 * {@code openalex.source-country.retry-days}: OpenAlex fills such gaps over time, but not by the hour.</p>
 */
@Slf4j
@Service
public class OpenAlexSourceCountryService {

    private static final String OPENALEX_ID_PREFIX = "https://openalex.org/";

    private final OpenAlexSourceCountryRepository repository;
    private final OpenAlexClient client;
    private final Duration retryAfter;

    public OpenAlexSourceCountryService(OpenAlexSourceCountryRepository repository, OpenAlexClient client,
                                        @Value("${openalex.source-country.retry-days:90}") long retryDays) {
        this.repository = repository;
        this.client = client;
        this.retryAfter = Duration.ofDays(Math.max(1, retryDays));
    }

    /** The stored country of the venue (ISO 3166 alpha-2, upper case), if one is known. */
    public Optional<String> countryOf(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(sourceId)
                .map(OpenAlexSourceCountry::getCountryCode)
                .filter(code -> !code.isBlank());
    }

    /** Outcome of one {@link #resolve} call. */
    public record Result(int requested, int asked, int withCountry) {}

    /** Asks OpenAlex about the venues that have no answer yet, or whose "no country" answer is old. */
    public Result resolve(Collection<String> sourceIds) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : sourceIds == null ? List.<String>of() : sourceIds) {
            if (id != null && !id.isBlank()) {
                wanted.add(id.trim());
            }
        }
        if (wanted.isEmpty()) {
            return new Result(0, 0, 0);
        }
        Map<String, OpenAlexSourceCountry> stored = new HashMap<>();
        repository.findAllById(wanted).forEach(record -> stored.put(record.getId(), record));
        Instant staleBefore = Instant.now().minus(retryAfter);
        List<String> toAsk = new ArrayList<>();
        for (String id : wanted) {
            OpenAlexSourceCountry record = stored.get(id);
            boolean answered = record != null && ((record.getCountryCode() != null && !record.getCountryCode().isBlank())
                    || (record.getCheckedAt() != null && record.getCheckedAt().isAfter(staleBefore)));
            if (!answered) {
                toAsk.add(id);
            }
        }
        if (toAsk.isEmpty()) {
            return new Result(wanted.size(), 0, 0);
        }
        Map<String, OpenAlexSourcesResponse.Source> answers = new HashMap<>();
        for (OpenAlexSourcesResponse.Source source : client.fetchSources(toAsk)) {
            if (source != null && source.getId() != null) {
                answers.put(source.getId().replace(OPENALEX_ID_PREFIX, ""), source);
            }
        }
        Instant now = Instant.now();
        int withCountry = 0;
        List<OpenAlexSourceCountry> toSave = new ArrayList<>();
        for (String id : toAsk) {
            OpenAlexSourceCountry record = stored.getOrDefault(id, new OpenAlexSourceCountry());
            record.setId(id);
            record.setCheckedAt(now);
            OpenAlexSourcesResponse.Source answer = answers.get(id);
            if (answer != null) {
                record.setDisplayName(answer.getDisplay_name());
                record.setHostOrganizationName(answer.getHost_organization_name());
                if (answer.getCountry_code() != null && !answer.getCountry_code().isBlank()) {
                    record.setCountryCode(answer.getCountry_code().trim().toUpperCase(Locale.ROOT));
                    withCountry++;
                }
            }
            toSave.add(record);
        }
        repository.saveAll(toSave);
        log.info("OpenAlex venue countries: requested={} asked={} withCountry={}", wanted.size(), toAsk.size(), withCountry);
        return new Result(wanted.size(), toAsk.size(), withCountry);
    }
}
