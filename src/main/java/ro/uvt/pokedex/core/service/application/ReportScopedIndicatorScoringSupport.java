package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Citation;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Set;

final class ReportScopedIndicatorScoringSupport {

    private ReportScopedIndicatorScoringSupport() {
    }

    static CitationContext prepareCitationContext(List<Publication> publications,
                                                  ScholardexProjectionReadService scholardexProjectionReadService) {
        List<String> pubIds = publications.stream().map(Publication::getId).toList();
        List<Citation> allCitations = scholardexProjectionReadService.findAllCitationsByCitedIdIn(pubIds);
        List<String> citationIds = allCitations.stream().map(Citation::getCitingId).toList();
        List<Publication> citingPublications = scholardexProjectionReadService.findAllPublicationsByIdIn(citationIds);
        Map<String, Publication> citingPublicationsById = new HashMap<>();
        for (Publication publication : citingPublications) {
            if (publication != null && publication.getId() != null) {
                citingPublicationsById.putIfAbsent(publication.getId(), publication);
            }
        }
        Map<String, List<Publication>> citingPublicationsByCitedPublicationId = new HashMap<>();
        for (Citation citation : allCitations) {
            if (citation == null) {
                continue;
            }
            Publication citing = citingPublicationsById.get(citation.getCitingId());
            if (citing == null || citation.getCitedId() == null) {
                continue;
            }
            citingPublicationsByCitedPublicationId
                    .computeIfAbsent(citation.getCitedId(), ignored -> new ArrayList<>())
                    .add(citing);
        }
        return new CitationContext(citingPublicationsById, citingPublicationsByCitedPublicationId, allCitations.size());
    }

    static Map<Indicator, Map<String, Score>> precomputeCitationBaseScoresByIndicator(List<Indicator> indicators,
                                                                                      CitationContext citationContext,
                                                                                      ScientificProductionService scientificProductionService) {
        if (indicators == null || indicators.isEmpty()) {
            return Map.of();
        }
        List<Publication> uniqueCitingPublications = new ArrayList<>(citationContext.citingPublicationsById().values());
        if (uniqueCitingPublications.isEmpty()) {
            return Map.of();
        }
        Map<Indicator, Map<String, Score>> cached = new HashMap<>();
        for (Indicator indicator : indicators) {
            if (indicator == null) {
                continue;
            }
            if (!Indicator.Type.CITATIONS.equals(indicator.getOutputType())
                    && !Indicator.Type.CITATIONS_EXCLUDE_SELF.equals(indicator.getOutputType())) {
                continue;
            }
            cached.put(indicator, scientificProductionService.precomputeCitationBaseScores(uniqueCitingPublications, indicator));
        }
        return cached;
    }

    static CitationScoreResult calculateCitationScore(Indicator indicator,
                                                      List<Publication> publications,
                                                      Set<String> researcherAuthorIds,
                                                      CitationContext citationContext,
                                                      Map<String, Score> cachedCitationBaseScoresByCitingPublicationId,
                                                      ScientificProductionService scientificProductionService) {
        CitationViewComputation computation = computeCitationView(
                indicator,
                publications,
                researcherAuthorIds,
                citationContext,
                cachedCitationBaseScoresByCitingPublicationId,
                scientificProductionService
        );
        return new CitationScoreResult(computation.totalScore(), computation.selectorNanos());
    }

    static CitationViewComputation computeCitationView(Indicator indicator,
                                                       List<Publication> publications,
                                                       Set<String> researcherAuthorIds,
                                                       CitationContext citationContext,
                                                       Map<String, Score> cachedCitationBaseScoresByCitingPublicationId,
                                                       ScientificProductionService scientificProductionService) {
        boolean excludeSelf = indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF);
        Map<String, Map<String, Score>> rawScores = new LinkedHashMap<>();
        Map<String, Publication> citationMap = new LinkedHashMap<>();
        Set<String> forumIds = new java.util.LinkedHashSet<>();
        int totalCitationCount = 0;

        for (Publication pub : publications) {
            if (pub.getForum() != null) {
                forumIds.add(pub.getForum());
            }
            List<Publication> citations = citationContext.citingPublicationsByCitedPublicationId()
                    .getOrDefault(pub.getId(), List.of());
            if (excludeSelf) {
                citations = citations.stream()
                        .filter(citing -> !sharesAnyAuthor(citing, researcherAuthorIds))
                        .toList();
            }
            totalCitationCount += citations.size();
            for (Publication citing : citations) {
                if (citing.getTitle() != null) {
                    citationMap.putIfAbsent(citing.getTitle(), citing);
                }
                if (citing.getForum() != null) {
                    forumIds.add(citing.getForum());
                }
            }

            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(
                    pub,
                    citations,
                    indicator,
                    cachedCitationBaseScoresByCitingPublicationId
            );
            rawScores.put(pub.getTitle(), citScores);
        }

        long selectorStartNanos = System.nanoTime();
        ReportingComputationSupport.applyFinalSelector(indicator, rawScores);
        long selectorNanos = System.nanoTime() - selectorStartNanos;
        double totalScore = sumDisplayedCitationScores(rawScores.values());
        Map<String, Map<String, Score>> displayScores = copyScores(rawScores);
        Map<String, Integer> quarterHistogram = buildQuarterHistogram(displayScores);

        return new CitationViewComputation(
                totalScore,
                totalCitationCount,
                displayScores,
                citationMap,
                forumIds,
                new ArrayList<>(quarterHistogram.keySet()),
                new ArrayList<>(quarterHistogram.values()),
                selectorNanos
        );
    }

    static String viewNameFor(Indicator indicator) {
        if (indicator.getOutputType().toString().contains("ACTIVIT")) {
            return "user/indicators-apply-activities";
        }
        if (indicator.getOutputType().equals(Indicator.Type.CITATIONS)
                || indicator.getOutputType().equals(Indicator.Type.CITATIONS_EXCLUDE_SELF)) {
            return "user/indicators-apply-citations";
        }
        return "user/indicators-apply-publications";
    }

    private static boolean sharesAnyAuthor(Publication publication, Set<String> authorIds) {
        if (publication == null || publication.getAuthors() == null || publication.getAuthors().isEmpty() || authorIds.isEmpty()) {
            return false;
        }
        for (String authorId : publication.getAuthors()) {
            if (authorIds.contains(authorId)) {
                return true;
            }
        }
        return false;
    }

    private static double sumDisplayedCitationScores(Collection<Map<String, Score>> scoresByPublication) {
        double total = 0.0;
        for (Map<String, Score> publicationScores : scoresByPublication) {
            if (publicationScores == null) {
                continue;
            }
            for (Map.Entry<String, Score> entry : publicationScores.entrySet()) {
                if ("total".equals(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                total += entry.getValue().getAuthorScore();
            }
        }
        return total;
    }

    private static Map<String, Map<String, Score>> copyScores(Map<String, Map<String, Score>> scores) {
        Map<String, Map<String, Score>> displayScores = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Score>> publicationEntry : scores.entrySet()) {
            Map<String, Score> filtered = new LinkedHashMap<>();
            if (publicationEntry.getValue() != null) {
                for (Map.Entry<String, Score> scoreEntry : publicationEntry.getValue().entrySet()) {
                    filtered.put(scoreEntry.getKey(), scoreEntry.getValue());
                }
            }
            displayScores.put(publicationEntry.getKey(), filtered);
        }
        return displayScores;
    }

    private static Map<String, Integer> buildQuarterHistogram(Map<String, Map<String, Score>> displayScores) {
        Map<String, Integer> histogram = new TreeMap<>();
        for (Map<String, Score> publicationScores : displayScores.values()) {
            if (publicationScores == null) {
                continue;
            }
            for (Score score : publicationScores.values()) {
                if (score == null || score.getQuarter() == null) {
                    continue;
                }
                histogram.merge(score.getQuarter(), 1, Integer::sum);
            }
        }
        return histogram;
    }

    record CitationContext(
            Map<String, Publication> citingPublicationsById,
            Map<String, List<Publication>> citingPublicationsByCitedPublicationId,
            int citationFactsCount
    ) {
        static CitationContext empty() {
            return new CitationContext(Map.of(), Map.of(), 0);
        }
    }

    record CitationScoreResult(
            double score,
            long selectorNanos
    ) {
    }

    record CitationViewComputation(
            double totalScore,
            int totalCitationCount,
            Map<String, Map<String, Score>> displayScores,
            Map<String, Publication> citationMap,
            Set<String> forumIds,
            List<String> quarterLabels,
            List<Integer> quarterValues,
            long selectorNanos
    ) {
    }
}
