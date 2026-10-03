package ro.uvt.pokedex.core.service.reporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.service.model.UniversityRankingLookupService;

import ro.uvt.pokedex.core.model.WoSRanking;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * H144 — hands {@link RegistryScoringSupport} the app's lists at startup: URAP positions and countries of universities,
 * and what the corpus knows of a journal named by ISSN (DOAJ fee, Web of Science core editions, indexing databases).
 */
@Service
@RequiredArgsConstructor
public class RegistryScoringLookups implements RegistryScoringSupport.Lookups {

    private final UniversityRankingLookupService universityRankingLookupService;
    private final ReportingLookupPort reportingLookupPort;
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexForumFactRepository forumFactRepository;
    private final ro.uvt.pokedex.core.service.openalex.OpenAlexSourceCountryService sourceCountryService;

    @PostConstruct
    void register() {
        RegistryScoringSupport.register(this);
    }

    @Override
    public Optional<Integer> urapRank(String university, int year) {
        return universityRankingLookupService.urapRank(university, year).map(UniversityRankingLookupService.BestRank::rank);
    }

    @Override
    public Optional<Integer> worldRank(String university, int year) {
        return universityRankingLookupService.worldRank(university, year).map(UniversityRankingLookupService.BestRank::rank);
    }

    @Override
    public Optional<String> universityCountry(String university) {
        return universityRankingLookupService.countryOf(university);
    }

    @Override
    public Optional<RegistryScoringSupport.JournalFacts> journal(String issn, int year) {
        List<String> forums = reportingLookupPort.findForumIdsByIssn(issn, issn);
        if (forums == null || forums.isEmpty()) {
            return Optional.empty();
        }
        // a year past the published lists reads the latest ones
        int last = reportingLookupPort.maxAvailableYear();
        int y = year <= 0 || (last > 0 && year > last) ? last : year;
        boolean fee = false, wos = false, core = false, scopus = false;
        Set<String> databases = new HashSet<>();
        for (String forumId : forums) {
            fee |= reportingLookupPort.isFeeJournal(forumId);
            boolean citationIndex = reportingLookupPort.isForumInScie(forumId, y) || reportingLookupPort.isForumInSsci(forumId, y)
                    || reportingLookupPort.isForumInAhci(forumId, y);
            wos |= citationIndex;
            core |= citationIndex || reportingLookupPort.isForumInEsci(forumId, y);
            scopus |= reportingLookupPort.isForumInScopus(forumId);
            Set<String> indexed = reportingLookupPort.getForumIndexingDatabases(forumId);
            if (indexed != null) {
                databases.addAll(indexed);
            }
        }
        return Optional.of(new RegistryScoringSupport.JournalFacts(fee, wos, core, scopus, databases,
                impactFactor(issn, y), country(forums)));
    }

    /** H145 — the country where the journal is published (OpenAlex's, as stored), for the coefficient m. */
    private String country(List<String> forumIds) {
        try {
            for (var forum : forumFactRepository.findAllById(forumIds)) {
                for (String sourceId : forum.getOpenAlexIds() == null ? List.<String>of() : forum.getOpenAlexIds()) {
                    String id = sourceId == null ? null : sourceId.replaceFirst("^https://openalex\\.org/", "");
                    Optional<String> country = sourceCountryService.countryOf(id);
                    if (country.isPresent()) {
                        return country.get();
                    }
                }
            }
        } catch (RuntimeException e) {
            return null; // unknown place: m resolves downward
        }
        return null;
    }

    /** The journal's impact factor of the year, else of the latest year before it that has one; null without any. */
    private Double impactFactor(String issn, int year) {
        Double best = null;
        int bestYear = Integer.MIN_VALUE;
        for (WoSRanking ranking : reportingLookupPort.getRankingsByIssn(issn)) {
            if (ranking.getScore() == null || ranking.getScore().getIF() == null) {
                continue;
            }
            for (Map.Entry<Integer, Double> e : ranking.getScore().getIF().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getKey() > year) {
                    continue;
                }
                if (e.getKey() > bestYear || (e.getKey() == bestYear && e.getValue() > best)) {
                    best = e.getValue();
                    bestYear = e.getKey();
                }
            }
        }
        return best;
    }
}
