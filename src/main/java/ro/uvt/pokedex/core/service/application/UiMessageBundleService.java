package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H87 S3a — hands the client-side modules the subset of the message bundle they need, resolved for the active
 * locale, so {@code messages.properties} stays the one place translations live.
 *
 * <p>The KEY LIST comes from the default (Romanian) bundle read as a resource, because {@link MessageSource}
 * can resolve a key but cannot enumerate keys. Each VALUE is then resolved through the MessageSource, so the
 * usual fallback chain applies and an English page never silently shows a Romanian string.</p>
 */

@Service
@RequiredArgsConstructor
public class UiMessageBundleService {

    private static final Logger log = LoggerFactory.getLogger(UiMessageBundleService.class);
    private static final String BASE_BUNDLE = "messages.properties";

    private final MessageSource messageSource;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * All keys starting with any of {@code prefixes}, resolved for {@code locale}. Cached per
     * (locale, prefixes) — the bundle is static for the life of the process.
     */
    public Map<String, String> bundleFor(Locale locale, String... prefixes) {
        String cacheKey = (locale == null ? "ro" : locale.getLanguage()) + "|" + String.join(",", prefixes);
        return cache.computeIfAbsent(cacheKey, ignored -> build(locale, prefixes));
    }

    private Map<String, String> build(Locale locale, String[] prefixes) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : new TreeSet<>(baseKeys().stringPropertyNames())) {
            boolean wanted = false;
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) {
                continue;
            }
            try {
                resolved.put(key, messageSource.getMessage(key, null, locale));
            } catch (RuntimeException ex) {
                log.warn("UI bundle: key {} could not be resolved for {}: {}", key, locale, ex.getMessage());
            }
        }
        return Map.copyOf(resolved);
    }

    private Properties baseKeys() {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(BASE_BUNDLE).getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            // Fail soft: an empty bundle makes the client render keys, which is ugly but not broken.
            log.warn("UI bundle: could not read {} — client modules will fall back to keys: {}",
                    BASE_BUNDLE, ex.getMessage());
        }
        return properties;
    }
}
