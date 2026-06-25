package ro.uvt.pokedex.core.service.application;

import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.scoring.SelfCitationPolicy;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

public final class ReportScopedIndicatorScoringSupport {

    private ReportScopedIndicatorScoringSupport() {
    }

    public static CitationContext prepareCitationContext(List<ScholardexPublicationView> publications,
                                                  ScholardexProjectionReadService scholardexProjectionReadService) {
        List<String> pubIds = publications.stream().map(ScholardexPublicationView::getId).toList();
        List<ScholardexCitationView> allCitations = scholardexProjectionReadService.findAllCitationsByCitedIdIn(pubIds);
        List<String> citationIds = allCitations.stream().map(ScholardexCitationView::getCitingId).toList();
        List<ScholardexPublicationView> citingPublications = scholardexProjectionReadService.findAllPublicationsByIdIn(citationIds);
        Map<String, ScholardexPublicationView> citingPublicationsById = new HashMap<>();
        for (ScholardexPublicationView publication : citingPublications) {
            if (publication != null && publication.getId() != null) {
                citingPublicationsById.putIfAbsent(publication.getId(), publication);
            }
        }
        Map<String, List<ScholardexPublicationView>> citingPublicationsByCitedPublicationId = new HashMap<>();
        for (ScholardexCitationView citation : allCitations) {
            if (citation == null) {
                continue;
            }
            ScholardexPublicationView citing = citingPublicationsById.get(citation.getCitingId());
            if (citing == null || citation.getCitedId() == null) {
                continue;
            }
            citingPublicationsByCitedPublicationId
                    .computeIfAbsent(citation.getCitedId(), ignored -> new ArrayList<>())
                    .add(citing);
        }
        return new CitationContext(citingPublicationsById, citingPublicationsByCitedPublicationId, allCitations.size());
    }

    public static Map<Indicator, Map<String, Score>> precomputeCitationBaseScoresByIndicator(List<Indicator> indicators,
                                                                                      CitationContext citationContext,
                                                                                      ScientificProductionService scientificProductionService) {
        if (indicators == null || indicators.isEmpty()) {
            return Map.of();
        }
        List<ScoringPublicationReadModel> uniqueCitingPublications = citationContext.citingPublicationsById().values().stream()
                .map(ScholardexPublicationView::toScoringPublication)
                .toList();
        if (uniqueCitingPublications.isEmpty()) {
            return Map.of();
        }
        Map<Indicator, Map<String, Score>> cached = new HashMap<>();
        for (Indicator indicator : indicators) {
            if (indicator == null) {
                continue;
            }
            // H52 slice 11d.2: typed-kind check for the citation family.
            if (!indicator.isCitationsOutput()) {
                continue;
            }
            cached.put(indicator, scientificProductionService.precomputeCitationBaseScores(uniqueCitingPublications, indicator));
        }
        return cached;
    }

    public static CitationScoreResult calculateCitationScore(Indicator indicator,
                                                      List<ScholardexPublicationView> publications,
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
                                                       List<ScholardexPublicationView> publications,
                                                       Set<String> researcherAuthorIds,
                                                       CitationContext citationContext,
                                                       Map<String, Score> cachedCitationBaseScoresByCitingPublicationId,
                                                       ScientificProductionService scientificProductionService) {
        // H61: self-citation exclusion mode — NONE / candidate-only / any-coauthor of the cited publication.
        SelfCitationPolicy citationPolicy = indicator.getCitationExclusionPolicy();
        Map<String, Map<String, Score>> rawScores = new LinkedHashMap<>();
        Map<String, ScholardexPublicationView> citationMap = new LinkedHashMap<>();
        Set<String> forumIds = new java.util.LinkedHashSet<>();
        int totalCitationCount = 0;

        for (ScholardexPublicationView pub : publications) {
            if (pub.getForum() != null) {
                forumIds.add(pub.getForum());
            }
            List<ScholardexPublicationView> citations = citationContext.citingPublicationsByCitedPublicationId()
                    .getOrDefault(pub.getId(), List.of());
            Set<String> exclusionAuthorIds = citationExclusionAuthorIds(citationPolicy, pub, researcherAuthorIds);
            if (!exclusionAuthorIds.isEmpty()) {
                citations = citations.stream()
                        .filter(citing -> !sharesAnyAuthor(citing, exclusionAuthorIds))
                        .toList();
            }
            totalCitationCount += citations.size();
            for (ScholardexPublicationView citing : citations) {
                if (citing.getTitle() != null) {
                    citationMap.putIfAbsent(citing.getTitle(), citing);
                }
                if (citing.getForum() != null) {
                    forumIds.add(citing.getForum());
                }
            }

            Map<String, Score> citScores = scientificProductionService.calculateScientificImpactScore(
                    pub.toScoringPublication(),
                    citations.stream().map(ScholardexPublicationView::toScoringPublication).toList(),
                    indicator,
                    cachedCitationBaseScoresByCitingPublicationId
            );
            String publicationTitle = pub.getTitle();
            if (!rawScores.containsKey(publicationTitle)) {
                rawScores.put(publicationTitle, new LinkedHashMap<>(citScores));
            } else {
                mergeCitationScores(rawScores.get(publicationTitle), citScores);
            }
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
        return "user/indicators-apply";
    }

    /**
     * H61: the author-id set a citation must avoid to be counted, by policy. {@code CANDIDATE_ONLY} uses the
     * candidate's resolved ids (today's behaviour); {@code ANY_COAUTHOR} uses the cited publication's full author set
     * (a strict superset — the candidate is among them); {@code NONE} → empty (no exclusion). Empty author lists yield
     * an empty set, so the filter is a safe no-op.
     */
    public static Set<String> citationExclusionAuthorIds(SelfCitationPolicy policy,
                                                  ScholardexPublicationView citedPublication,
                                                  Set<String> researcherAuthorIds) {
        return switch (policy) {
            case NONE -> Set.of();
            case CANDIDATE_ONLY -> researcherAuthorIds == null ? Set.of() : researcherAuthorIds;
            case ANY_COAUTHOR -> citedPublication == null || citedPublication.getAuthors() == null
                    ? Set.of()
                    : new HashSet<>(citedPublication.getAuthors());
        };
    }

    private static boolean sharesAnyAuthor(ScholardexPublicationView publication, Set<String> authorIds) {
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

    private static void mergeCitationScores(Map<String, Score> target, Map<String, Score> delta) {
        if (target == null || delta == null) {
            return;
        }
        for (Map.Entry<String, Score> entry : delta.entrySet()) {
            String key = entry.getKey();
            Score incoming = entry.getValue();
            if (incoming == null) {
                continue;
            }
            Score existing = target.get(key);
            if (existing == null) {
                target.put(key, incoming);
                continue;
            }
            Score merged = new Score();
            merged.setAuthorScore(existing.getAuthorScore() + incoming.getAuthorScore());
            merged.setScore(existing.getScore() + incoming.getScore());
            merged.setQuarter(existing.getQuarter() != null ? existing.getQuarter() : incoming.getQuarter());
            merged.setCoreRankingEquivalent(existing.getCoreRankingEquivalent() != null
                    ? existing.getCoreRankingEquivalent()
                    : incoming.getCoreRankingEquivalent());
            target.put(key, merged);
        }
    }

    public record CitationContext(
            Map<String, ScholardexPublicationView> citingPublicationsById,
            Map<String, List<ScholardexPublicationView>> citingPublicationsByCitedPublicationId,
            int citationFactsCount
    ) {
        public static CitationContext empty() {
            return new CitationContext(Map.of(), Map.of(), 0);
        }
    }

    public record CitationScoreResult(
            double score,
            long selectorNanos
    ) {
    }

    record CitationViewComputation(
            double totalScore,
            int totalCitationCount,
            Map<String, Map<String, Score>> displayScores,
            Map<String, ScholardexPublicationView> citationMap,
            Set<String> forumIds,
            List<String> quarterLabels,
            List<Integer> quarterValues,
            long selectorNanos
    ) {
    }
}
