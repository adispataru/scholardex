package ro.uvt.pokedex.core.service.reporting;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.Domain;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.model.scopus.Publication;
import ro.uvt.pokedex.core.service.CacheService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CNFISScoringService2025 {
    private static final Logger log = LoggerFactory.getLogger(CNFISScoringService2025.class);
    private final CacheService cacheService;
    private static final int LAST_YEAR = 2023;
    public CNFISReport2025 getReport(Publication publication, Domain domain) {
        Forum forum = cacheService.getCachedForums(publication.getForum());
        CNFISReport2025 report = new CNFISReport2025();
        report.setTitlu(publication.getTitle());
        report.setDoi(publication.getDoi());
        report.setDenumireJurnal(forum.getPublicationName());
        report.setIssnOnline(forum.getEIssn());
        report.setIssnPrint(forum.getIssn());

        String subtype = resolveSubtype(publication);
        if ("ar".equals(subtype) || "re".equals(subtype)) {
            String issn = forum.getIssn();
            String eIssn = forum.getEIssn();
            List<Integer> allowedYears = new ArrayList<>();
            try{
                Integer pubYear = Integer.parseInt(publication.getCoverDate().substring(0, 4));
                allowedYears.add(pubYear);
            }catch (Exception e){
                log.warn("Exception retrieving year for publication: {}", publication.getCoverDate());
            }

            // Fetch rankings from cache or repository
            List<WoSRanking> allByIssn = cacheService.getCachedRankingsByIssn(issn);
            if (allByIssn.isEmpty()) {
                allByIssn = cacheService.getCachedRankingsByIssn(eIssn);
            }

            for (WoSRanking ranking : allByIssn) {
                for (Map.Entry<String, WoSRanking.Rank> entry : ranking.getWebOfScienceCategoryIndex().entrySet()) {
                    String category = entry.getKey();
                    String catIndex = extractCategoryIndex(category);
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
        }else if ("cp".equals(subtype)) {
            if(forum.getPublicationName().contains("IEEE")) {
                report.setIeeeProceedings(true);
            }else {
                if(publication.getWosId() != null && !publication.getWosId().isEmpty()) {
                    report.setIsiProceedings(true);
                }
            }
        }else if("ch".equals(subtype)) {
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

    private String resolveSubtype(Publication publication) {
        String scopusSubtype = normalize(publication.getScopusSubtype());
        if (!scopusSubtype.isEmpty()) {
            return scopusSubtype;
        }
        return normalize(publication.getSubtype());
    }

    private String extractCategoryIndex(String category) {
        if (category == null) {
            log.warn("Encountered null WoS category while scoring CNFIS 2025.");
            return "";
        }
        String normalized = category.trim();
        if (normalized.isEmpty()) {
            log.warn("Encountered empty WoS category while scoring CNFIS 2025.");
            return "";
        }
        int delimiterPos = normalized.indexOf('-');
        if (delimiterPos < 0 || delimiterPos == normalized.length() - 1) {
            log.warn("Non-standard WoS category format '{}'; using full value as category index.", category);
            return normalized;
        }
        return normalized.substring(delimiterPos + 1).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
