package ro.uvt.pokedex.core.service.application.reporting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.PersistenceYearSupport;
import ro.uvt.pokedex.core.service.reporting.CNFISScoringService2025;
import ro.uvt.pokedex.core.service.reporting.ComputerScienceConferenceScoringService;
import ro.uvt.pokedex.core.service.reporting.PublicationSubtypeSupport;
import ro.uvt.pokedex.core.service.reporting.Score;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classifies a publication into a venue bucket (Q1..Q4, CORE rankings, LNCS, SCOPUS, Unranked)
 * for the group publications overview. Pulled out of {@code GroupReportFacade} so the
 * publication aggregator stays focused on aggregation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VenueClassifier {

    public static final List<String> BUCKET_ORDER = List.of(
            "Q1", "Q2", "Q3", "Q4", "A_STAR", "A", "B", "C", "D",
            "LNCS", "BOOK_LNCS", "SCOPUS", "NON_RANK", "Unranked"
    );

    private final CNFISScoringService2025 cnfisScoringService2025;
    private final ComputerScienceConferenceScoringService computerScienceConferenceScoringService;

    public Map<String, Long> emptyBucketCounts() {
        Map<String, Long> buckets = new LinkedHashMap<>();
        BUCKET_ORDER.forEach(bucket -> buckets.put(bucket, 0L));
        return buckets;
    }

    public String classify(ScholardexPublicationView publication, ScholardexForumView forum) {
        if (PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "ar", "re")) {
            return classifyJournalBucket(publication);
        }
        if (isLncsBookChapter(publication, forum)) {
            return classifyLncsBookChapterBucket(publication, forum);
        }
        if (PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "cp")) {
            return classifyConferenceBucket(publication);
        }
        return "Unranked";
    }

    private boolean isLncsBookChapter(ScholardexPublicationView publication, ScholardexForumView forum) {
        if (!PublicationSubtypeSupport.isSubtype(publication.toScoringPublication(), "ch")) {
            return false;
        }
        String publicationName = forum == null || forum.getPublicationName() == null
                ? ""
                : forum.getPublicationName().trim();
        return publicationName.contains("Lecture Notes in ")
                || publicationName.contains("Lecture Notes on ");
    }

    private String classifyLncsBookChapterBucket(ScholardexPublicationView publication, ScholardexForumView forum) {
        int year = PersistenceYearSupport.extractYear(publication.getCoverDate(), publication.getId(), log)
                .orElse(2023);
        Optional<Score> conferenceScore = computerScienceConferenceScoringService.tryResolveCoreScore(
                publication.toScoringPublication(), forum, year);
        if (conferenceScore.isPresent()) {
            Score score = conferenceScore.get();
            if (score.getCoreRankingEquivalent() != null && !score.getCoreRankingEquivalent().isBlank()) {
                return score.getCoreRankingEquivalent().trim();
            }
        }
        return "BOOK_LNCS";
    }

    private String classifyJournalBucket(ScholardexPublicationView publication) {
        Domain domain = new Domain();
        domain.setName("ALL");
        CNFISReport2025 report = cnfisScoringService2025.getReport(publication.toScoringPublication(), domain);
        if (report.isIsiQ1()) return "Q1";
        if (report.isIsiQ2()) return "Q2";
        if (report.isIsiQ3()) return "Q3";
        if (report.isIsiQ4()) return "Q4";
        return "Unranked";
    }

    private String classifyConferenceBucket(ScholardexPublicationView publication) {
        Score score = computerScienceConferenceScoringService.getScore(
                publication.toScoringPublication(), allDomainIndicator());
        if ("LNCS".equalsIgnoreCase(score.getQuarter())) return "LNCS";
        if ("SCOPUS".equalsIgnoreCase(score.getQuarter())) return "SCOPUS";
        if (score.getCoreRankingEquivalent() == null || score.getCoreRankingEquivalent().isBlank()) {
            return "Unranked";
        }
        return score.getCoreRankingEquivalent().trim();
    }

    private Indicator allDomainIndicator() {
        Indicator indicator = new Indicator();
        Domain domain = new Domain();
        domain.setName("ALL");
        indicator.setDomain(domain);
        return indicator;
    }
}
