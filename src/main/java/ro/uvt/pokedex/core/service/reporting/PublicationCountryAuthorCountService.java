package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAffiliationFact;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationAuthorAffiliationFact;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexAffiliationFactRepository;
import ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexPublicationAuthorAffiliationFactRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Country-scoped author counting for standards whose N considers only authors affiliated with a given
 * country — FEAA (Anexa 27 / COMISIA 27) point 6: "Numărul de autori luat în calcul (N) se referă doar
 * la cei cu afiliere la instituțiile de învățământ și cercetare din România."
 *
 * <p>Counting is deliberately conservative about missing data: an author is subtracted from the total
 * ONLY when the publication's affiliation links positively place them abroad (at least one linked
 * affiliation, none in the requested country). Authors with no affiliation rows — sparse coverage on
 * older papers — stay counted, so incomplete data can never shrink N below truth and inflate the
 * (1 − (N−1)·0,1) factor. A publication with no affiliation rows at all yields the full author count.</p>
 */
@Service
@RequiredArgsConstructor
public class PublicationCountryAuthorCountService {

    private final ScholardexPublicationAuthorAffiliationFactRepository publicationAuthorAffiliationRepository;
    private final ScholardexAffiliationFactRepository affiliationRepository;

    /** affiliationId → normalized country ("" when the affiliation has none); append-only cache. */
    private final ConcurrentHashMap<String, String> countryByAffiliationId = new ConcurrentHashMap<>();

    /**
     * The publication's author count restricted to {@code country} (case-insensitive, e.g. "Romania"),
     * per the conservative rules above. Never below 1 when the publication has authors — the candidate
     * themselves is by definition affiliated here.
     */
    public int authorCountForCountry(ScoringPublicationReadModel publication, String country) {
        if (publication == null) {
            return 0;
        }
        int total = publication.getAuthorCount();
        if (total <= 0 || publication.getId() == null) {
            return total;
        }
        List<ScholardexPublicationAuthorAffiliationFact> links =
                publicationAuthorAffiliationRepository.findByPublicationIdIn(List.of(publication.getId()));
        if (links.isEmpty()) {
            return total;
        }
        String wanted = normalize(country);
        Map<String, Boolean> anyInCountryByAuthor = new HashMap<>();
        Set<String> missing = new HashSet<>();
        for (ScholardexPublicationAuthorAffiliationFact link : links) {
            if (link.getAffiliationId() != null && !countryByAffiliationId.containsKey(link.getAffiliationId())) {
                missing.add(link.getAffiliationId());
            }
        }
        if (!missing.isEmpty()) {
            for (ScholardexAffiliationFact affiliation : affiliationRepository.findByIdIn(missing)) {
                countryByAffiliationId.put(affiliation.getId(), normalize(affiliation.getCountry()));
            }
        }
        for (ScholardexPublicationAuthorAffiliationFact link : links) {
            if (link.getAuthorId() == null || link.getAffiliationId() == null) {
                continue;
            }
            String linkCountry = countryByAffiliationId.getOrDefault(link.getAffiliationId(), "");
            anyInCountryByAuthor.merge(link.getAuthorId(), wanted.equals(linkCountry), Boolean::logicalOr);
        }
        // Only authors on THIS publication's author list can subtract — stray links can't push N negative.
        Set<String> publicationAuthors = publication.getAuthorIds() == null
                ? Set.of() : new HashSet<>(publication.getAuthorIds());
        int provablyForeign = 0;
        for (Map.Entry<String, Boolean> entry : anyInCountryByAuthor.entrySet()) {
            if (!entry.getValue() && (publicationAuthors.isEmpty() || publicationAuthors.contains(entry.getKey()))) {
                provablyForeign++;
            }
        }
        return Math.max(1, total - provablyForeign);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
