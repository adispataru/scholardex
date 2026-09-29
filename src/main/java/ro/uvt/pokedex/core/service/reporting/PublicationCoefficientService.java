package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.OpenAlexPublicationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexEntityType;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexSourceLink;
import ro.uvt.pokedex.core.repository.scopus.canonical.OpenAlexPublicationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexSourceLinkRepository;
import ro.uvt.pokedex.core.service.openalex.OpenAlexLanguageBackfillService;
import ro.uvt.pokedex.core.service.openalex.OpenAlexSourceCountryService;
import ro.uvt.pokedex.core.service.reporting.PublicationCoefficientSupport.Coefficient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The coefficient m of OM 3.019/2025, Comisia 25, definition [6], from what OpenAlex says about a publication:
 * its language and the country its venue is published in.
 *
 * <table>
 *   <caption>How the coefficient is decided</caption>
 *   <tr><th>language</th><th>venue published</th><th>m</th><th>basis</th></tr>
 *   <tr><td>en, fr, de, it, es</td><td>outside Romania</td><td>2</td><td>{@link #ABROAD}</td></tr>
 *   <tr><td>en, fr, de, it, es</td><td>in Romania</td><td>1,5</td><td>{@link #INTERNATIONAL_LANGUAGE}</td></tr>
 *   <tr><td>en, fr, de, it, es</td><td>not known</td><td>1,5</td><td>{@link #PLACE_UNKNOWN}</td></tr>
 *   <tr><td>any other</td><td>—</td><td>1</td><td>{@link #OTHER_LANGUAGE}</td></tr>
 *   <tr><td>not known</td><td>—</td><td>1</td><td>{@code NOT_DETERMINED}</td></tr>
 * </table>
 *
 * <p>Every unknown is resolved downward, so a missing piece of data can lower a score, never raise it. What
 * the annex also asks of the top value — international peer review — cannot be looked up; the candidate and
 * the commission still have to check it.</p>
 *
 * <p>Nothing here calls OpenAlex: scoring reads what the sync and the backfill stored.</p>
 */
@Slf4j
@Service
public class PublicationCoefficientService implements PublicationCoefficientSupport.Resolver {

    public static final String ABROAD = "ABROAD_INTERNATIONAL_LANGUAGE";
    public static final String INTERNATIONAL_LANGUAGE = "INTERNATIONAL_LANGUAGE";
    public static final String PLACE_UNKNOWN = "INTERNATIONAL_LANGUAGE_PLACE_UNKNOWN";
    public static final String OTHER_LANGUAGE = "OTHER_LANGUAGE";

    /** Definition [5]: "limbi de circulație internațională: engleză, franceză, germană, italiană și spaniolă". */
    static final Set<String> INTERNATIONAL_LANGUAGES = Set.of("en", "fr", "de", "it", "es");
    static final String HOME_COUNTRY = "RO";
    static final String SOURCE_OPENALEX = "OPENALEX";

    private static final Duration REMEMBER_FOR = Duration.ofMinutes(10);
    private static final int REMEMBER_AT_MOST = 50_000;

    private final ScholardexSourceLinkRepository sourceLinkRepository;
    private final OpenAlexPublicationFactRepository publicationFactRepository;
    private final OpenAlexSourceCountryService sourceCountryService;

    private record Remembered(Coefficient coefficient, Instant until) {}

    /** One report scores the same publication under several indicators; the answer is kept for a few minutes. */
    private final ConcurrentHashMap<String, Remembered> remembered = new ConcurrentHashMap<>();

    public PublicationCoefficientService(ScholardexSourceLinkRepository sourceLinkRepository,
                                         OpenAlexPublicationFactRepository publicationFactRepository,
                                         OpenAlexSourceCountryService sourceCountryService) {
        this.sourceLinkRepository = sourceLinkRepository;
        this.publicationFactRepository = publicationFactRepository;
        this.sourceCountryService = sourceCountryService;
    }

    @PostConstruct
    void register() {
        PublicationCoefficientSupport.register(this);
    }

    @PreDestroy
    void unregister() {
        PublicationCoefficientSupport.unregister(this);
    }

    @Override
    public Coefficient resolve(ScoringPublicationReadModel publication) {
        if (publication == null || publication.getId() == null || publication.getId().isBlank()) {
            return Coefficient.notDetermined();
        }
        Instant now = Instant.now();
        Remembered known = remembered.get(publication.getId());
        if (known != null && known.until().isAfter(now)) {
            return known.coefficient();
        }
        Coefficient coefficient;
        try {
            coefficient = lookUp(publication.getId());
        } catch (RuntimeException e) {
            // A database hiccup must not take a report down: the floor is always a valid answer.
            log.warn("Coefficient m not resolved for publication {}: {}", publication.getId(), e.toString());
            return Coefficient.notDetermined();
        }
        if (remembered.size() >= REMEMBER_AT_MOST) {
            remembered.clear();
        }
        remembered.put(publication.getId(), new Remembered(coefficient, now.plus(REMEMBER_FOR)));
        return coefficient;
    }

    private Coefficient lookUp(String publicationId) {
        List<String> workIds = sourceLinkRepository
                .findByEntityTypeAndCanonicalEntityId(ScholardexEntityType.PUBLICATION, publicationId).stream()
                .filter(link -> SOURCE_OPENALEX.equals(link.getSource()))
                .map(ScholardexSourceLink::getSourceRecordId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (workIds.isEmpty()) {
            return Coefficient.notDetermined();
        }
        Coefficient best = Coefficient.notDetermined();
        for (OpenAlexPublicationFact fact : publicationFactRepository.findBySourceRecordIdIn(workIds)) {
            Coefficient candidate = coefficientOf(fact);
            // Two OpenAlex records of one publication may differ; the one that says more wins.
            if (rank(candidate) > rank(best)) {
                best = candidate;
            }
        }
        return best;
    }

    Coefficient coefficientOf(OpenAlexPublicationFact fact) {
        String language = fact == null || fact.getLanguage() == null
                ? "" : fact.getLanguage().trim().toLowerCase(java.util.Locale.ROOT);
        if (language.isEmpty() || OpenAlexLanguageBackfillService.UNKNOWN.equals(language)) {
            return Coefficient.notDetermined();
        }
        if (!INTERNATIONAL_LANGUAGES.contains(language)) {
            return new Coefficient(1.0, OTHER_LANGUAGE);
        }
        Optional<String> country = sourceCountryService.countryOf(fact.getHostVenueOpenAlexId());
        if (country.isEmpty()) {
            return new Coefficient(1.5, PLACE_UNKNOWN);
        }
        return HOME_COUNTRY.equalsIgnoreCase(country.get())
                ? new Coefficient(1.5, INTERNATIONAL_LANGUAGE)
                : new Coefficient(2.0, ABROAD);
    }

    /** How much a result says: a determined one beats "not determined", a higher value beats a lower one. */
    private static double rank(Coefficient coefficient) {
        return PublicationCoefficientSupport.NOT_DETERMINED.equals(coefficient.basis()) ? 0.0 : coefficient.value();
    }

    /** Drops what is remembered; the tests use it between cases. */
    void forget() {
        remembered.clear();
    }
}
