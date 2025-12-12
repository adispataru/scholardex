package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class CNFISScoringService {
    private final CacheService cacheService;
    private static final int LAST_YEAR = 2023;
    public CNFISReport getReport(Publication publication, Domain domain) {
        Forum forum = cacheService.getCachedForums(publication.getForum());
        AtomicReference<Double> bestPoints = new AtomicReference<>(0.0);
        AtomicReference<Integer> bestYear = new AtomicReference<>(0);
        AtomicReference<CoreConferenceRanking.Rank> bestCategory = new AtomicReference<>(CoreConferenceRanking.Rank.NON_RANK);
        AtomicReference<WoSRanking.Quarter> bestQuarter = new AtomicReference<>(WoSRanking.Quarter.NOT_FOUND);
        CNFISReport report = new CNFISReport();
        report.setTitlu(publication.getTitle());
        report.setDoi(publication.getDoi());
        report.setDenumireJurnal(forum.getPublicationName());
        report.setIssnOnline(forum.getEIssn());
        report.setIssnPrint(forum.getIssn());

        if ("ar".equals(publication.getSubtype()) || "re".equals(publication.getSubtype())) {
            String issn = forum.getIssn();
            String eIssn = forum.getEIssn();
            List<Integer> allowedYears = new ArrayList<>();
            try{
                Integer pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
                allowedYears.add(pubYear);
            }catch (Exception e){
                System.out.println("Exception retrieving year for publication: " + publication.getCoverDate());
            }

            // Fetch rankings from cache or repository
            List<WoSRanking> allByIssn = cacheService.getCachedRankingsByIssn(issn);
            if (allByIssn.isEmpty()) {
                allByIssn = cacheService.getCachedRankingsByIssn(eIssn);
            }

            for (WoSRanking ranking : allByIssn) {
                for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                    String category = entry.getKey();
                    WoSRanking.Rank score = entry.getValue();

                    if (!"ALL".equals(domain.getName()) && !domain.getWosCategories().contains(category)) {
                        continue;
                    }
                    int year = allowedYears.getLast();
                    if(year > LAST_YEAR)
                        year = LAST_YEAR;
                    while (year <= LAST_YEAR) {

                        if (score.getQAis().get(year) != null) {

                            switch (score.getQAis().get(year)) {
                                case Q1 -> {
                                        report.setIsiRosu(true);
                                }
                                case Q2 -> {
                                    report.setIsiGalben(true);
                                }
                                case Q3, Q4 -> {
                                    report.setIsiAlb(true);
                                }
                            }
                            if(category.contains("ESCI")) {
                                report.setIsiEmergingSourcesCitationIndex(true);
                            }else if (category.contains("AHCI")) {
                                report.setIsiArtsHumanities(true);
                            }else if (category.contains("CCPI")) {
                                report.setIsiProceedings(true);
                            }else {
                                report.setErihPlus(true);
                            }
                            break;
                        }
                        year++;
                    }
                }
            }
        }else if ("cp".equals(publication.getSubtype())) {
            report.setIsiProceedings(true);
        }
        report.setNumarAutori(publication.getAuthors().size());
        //TODO compute number of authors from the same institution

        return report;
    }
}
