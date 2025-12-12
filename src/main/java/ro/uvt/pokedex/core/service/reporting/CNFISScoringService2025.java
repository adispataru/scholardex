package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
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
public class CNFISScoringService2025 {
    private final CacheService cacheService;
    private static final int LAST_YEAR = 2023;
    public CNFISReport2025 getReport(Publication publication, Domain domain) {
        Forum forum = cacheService.getCachedForums(publication.getForum());
        AtomicReference<Double> bestPoints = new AtomicReference<>(0.0);
        AtomicReference<Integer> bestYear = new AtomicReference<>(0);
        AtomicReference<CoreConferenceRanking.Rank> bestCategory = new AtomicReference<>(CoreConferenceRanking.Rank.NON_RANK);
        AtomicReference<WoSRanking.Quarter> bestQuarter = new AtomicReference<>(WoSRanking.Quarter.NOT_FOUND);
        CNFISReport2025 report = new CNFISReport2025();
        report.setTitlu(publication.getTitle());
        report.setDoi(publication.getDoi());
        report.setDenumireJurnal(forum.getPublicationName());
        report.setIssnOnline(forum.getEIssn());
        report.setIssnPrint(forum.getIssn());

        if ("ar".equals(publication.getScopusSubtype()) || "re".equals(publication.getScopusSubtype())) {
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
                    String[] parts = category.split("-");
                    if(parts.length < 2) {

                        continue; // Invalid category format
                    }
                    String catIndex = parts[1];
                    WoSRanking.Rank score = entry.getValue();

                    if (!"ALL".equals(domain.getName()) && !domain.getWosCategories().contains(category)) {
                        continue;
                    }

                    int year = !allowedYears.isEmpty() ? allowedYears.removeFirst() : LAST_YEAR;
                    if(year > LAST_YEAR)
                        year = LAST_YEAR;
                    while (year <= LAST_YEAR) {

                        if (score.getQAis().get(year) != null) {


                            if(catIndex.contains("SCIE") || catIndex.contains("SSCI")) {

                                switch (score.getQAis().get(year)) {
                                    case Q1 -> {
                                        report.setIsiQ1(true);
                                    }
                                    case Q2 -> {
                                        report.setIsiQ2(true);
                                    }
                                    case Q3 -> {
                                        report.setIsiQ3(true);
                                    }
                                    case Q4 -> {
                                        report.setIsiQ4(true);
                                    }
                                }
                            }
                            if(catIndex.contains("ESCI")) {
                                report.setIsiEmergingSourcesCitationIndex(true);
                            }else if (catIndex.contains("AHCI")) {
                                report.setIsiArtsHumanities(true);
                            }else {
                                report.setErihPlus(true);
                            }
                            break;
                        }
                        if(!allowedYears.isEmpty()) {
                            year = allowedYears.removeFirst();
                        }else{
                            break;
                        }
                    }
                }
            }
        }else if ("cp".equals(publication.getScopusSubtype())) {
            if(forum.getPublicationName().contains("IEEE")) {
                report.setIeeeProceedings(true);
            }else {
                if(publication.getWosId() != null && !publication.getWosId().isEmpty()) {
                    report.setIsiProceedings(true);
                }
            }
        }else if("ch".equals(publication.getScopusSubtype())) {
            if (forum.getPublicationName().contains("Lecture Notes")) {
                if (publication.getWosId() != null && !publication.getWosId().isEmpty()) {
                    report.setIsiProceedings(true);
                }
            }
        }
        report.setNumarAutori(publication.getAuthors().size());
        report.setNumarAutoriUniversitate((int) publication.getAuthors().stream().filter(a -> cacheService.getUniversityAuthorIds().contains(a)).count());

        return report;
    }
}
