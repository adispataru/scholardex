package ro.uvt.pokedex.core.service.reporting;

import org.mvel2.MVEL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.scopus.Publication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScientificProductionService {
    @Autowired
    ScoringFactoryService scoringFactoryService;


    public Map<String, Score> calculateScientificProductionScore(List<Publication> publications, Indicator indicator) {

        if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
            Map<String, Score> result = new HashMap<>();
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return result;
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());

        double totalScore = 0;
        Map<String, Score> interResult = new HashMap<>();
        if(scoringService != null) {
            for (Publication publication : publications) {
                Score score = calculatePublicationScore(publication, indicator, scoringService);
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    interResult.put(publication.getTitle(), score);
                    totalScore += score.getAuthorScore();
                }
            }
        }
        Map<String, Score> result = new HashMap<>();
        if(indicator.getSelector() != null && !indicator.getSelector().equals(Indicator.Selector.ALL)) {
            if(indicator.getSelector().equals(Indicator.Selector.TOP_10)) {
                totalScore = 0.0;
                if(publications.size() > 10) {
                    publications.sort((p1, p2) -> Double.compare(
                            interResult.get(p2.getTitle()) != null ? interResult.get(p2.getTitle()).getAuthorScore(): 0,
                            interResult.get(p1.getTitle()) != null ? interResult.get(p1.getTitle()).getAuthorScore() : 0));
                }
                int limit = Math.min(10, publications.size());
                for (int i = 0; i < limit; i++) {
                    Publication pub = publications.get(i);
                    Score score = interResult.get(pub.getTitle());
                    if(score != null) {
                        result.put(pub.getTitle(), score);
                        totalScore += score.getAuthorScore();
                    }
                }
            }
        }else{
            result = interResult;
        }
        Score total = new Score();
        total.setAuthorScore(totalScore);
        result.put("total", total);

        return result;
    }

    public Map<String, Score> calculateScientificImpactScore(Publication cited, List<Publication> publications, Indicator indicator) {
        Map<String, Score> result = new HashMap<>();
        if(indicator.getScoringStrategy().equals(Indicator.Strategy.GENERIC_COUNT)) {
            publications.forEach(pub -> {
                Score score = new Score();
                score.setScore(1.0);
                score.setAuthorScore(1.0);
                result.put(pub.getTitle(), score);
            });
            Score total = new Score();
            total.setAuthorScore(publications.size());
            result.put("total", total);
            return result;
        }
        ScoringService scoringService = scoringFactoryService.getScoringService(indicator.getScoringStrategy());
        double totalAuthorScore = 0;
        double totalScore = 0;
        if(scoringService != null) {
            for (Publication publication : publications) {
                Score score = calculateCitationScore(cited, publication, indicator, scoringService);
                if(score.getScore() + score.getAuthorScore() > 0.0) {
                    totalAuthorScore += score.getAuthorScore();
                    totalScore += score.getScore();
                    result.put(publication.getTitle(), score);
                }
            }
        }
        Score total = new Score();
        total.setAuthorScore(totalAuthorScore);
        total.setScore(totalScore);

        result.put("total", total);
        return result;
    }

    private Score calculatePublicationScore(Publication publication, Indicator indicator, ScoringService scoringService) {
        return getScore(publication, publication, indicator, scoringService);
    }

    private Score calculateCitationScore(Publication cited, Publication citing, Indicator indicator, ScoringService scoringService) {
        return getScore(cited, citing, indicator, scoringService);
    }

    private Score getScore(Publication cited, Publication citing, Indicator indicator, ScoringService scoringService) {
        Score result = scoringService.getScore(citing, indicator);
        if(result.getScore() > 0) {
            int numberOfAuthors = cited.getAuthors().size();

            Map<String, Object> variables = new HashMap<>();
            variables.put("S", result.getScore());
            variables.put("N", numberOfAuthors);
            variables.put("Q", result.getQuarter());
            for (String key : result.getExtra().keySet()) {
                variables.put(key, result.getExtra().get(key));
            }

            String formula = indicator.getFormula();
            if (formula.contains("max")) {
                formula = formula.replaceAll("max", "Math.max");
                variables.put("Math", Math.class);
            }

            double finalScore = MVEL.eval(formula, variables, Double.class);
            result.setAuthorScore(finalScore);
        }
        return result;
    }


}

