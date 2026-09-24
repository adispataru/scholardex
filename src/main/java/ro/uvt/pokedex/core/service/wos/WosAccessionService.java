package ro.uvt.pokedex.core.service.wos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.wos.WosAccessionLookup;
import ro.uvt.pokedex.core.model.wos.WosAccessionLookup.Status;
import ro.uvt.pokedex.core.repository.wos.WosAccessionLookupRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * DOI → WoS accession number, cache-first: a FOUND answer is final; a NOT_FOUND answer is trusted for
 * {@code wos.openurl.not-found-retry-days} (WoS indexes new papers with a delay); an UNAVAILABLE gateway is
 * never recorded, so the next caller asks again.
 */
@Slf4j
@Service
public class WosAccessionService {

    private final WosAccessionLookupRepository repository;
    private final WosOpenUrlClient client;
    private final Duration notFoundRetryAfter;

    public WosAccessionService(WosAccessionLookupRepository repository, WosOpenUrlClient client,
                               @Value("${wos.openurl.not-found-retry-days:90}") long notFoundRetryDays) {
        this.repository = repository;
        this.client = client;
        this.notFoundRetryAfter = Duration.ofDays(Math.max(1, notFoundRetryDays));
    }

    public static String key(String doi) {
        if (doi == null) {
            return null;
        }
        String k = doi.trim().toLowerCase(Locale.ROOT);
        k = k.replaceFirst("^https?://(dx\\.)?doi\\.org/", "").replaceFirst("^doi:", "");
        return k.isEmpty() ? null : k;
    }

    public Optional<String> resolve(String doi) {
        String key = key(doi);
        if (key == null) {
            return Optional.empty();
        }
        Optional<WosAccessionLookup> cached = repository.findById(key);
        if (cached.isPresent()) {
            WosAccessionLookup c = cached.get();
            if (c.getStatus() == Status.FOUND && c.getWosId() != null) {
                return Optional.of(c.getWosId());
            }
            if (c.getStatus() == Status.NOT_FOUND && c.getCheckedAt() != null
                    && c.getCheckedAt().isAfter(Instant.now().minus(notFoundRetryAfter))) {
                return Optional.empty();
            }
        }
        WosOpenUrlClient.Lookup lookup = client.lookup(key);
        if (lookup.outcome() == WosOpenUrlClient.Outcome.UNAVAILABLE) {
            return Optional.empty();
        }
        WosAccessionLookup record = cached.orElseGet(() -> {
            WosAccessionLookup fresh = new WosAccessionLookup();
            fresh.setDoi(key);
            return fresh;
        });
        record.setAttempts(record.getAttempts() + 1);
        record.setCheckedAt(Instant.now());
        record.setStatus(lookup.outcome() == WosOpenUrlClient.Outcome.FOUND ? Status.FOUND : Status.NOT_FOUND);
        record.setWosId(lookup.wosId().orElse(null));
        repository.save(record);
        return lookup.wosId();
    }
}
