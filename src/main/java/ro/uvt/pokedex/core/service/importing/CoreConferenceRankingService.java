package ro.uvt.pokedex.core.service.importing;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoreConferenceRankingService {

    private static final Logger logger = LoggerFactory.getLogger(CoreConferenceRankingService.class);

    @Autowired
    private CoreConferenceRankingRepository coreConferenceRankingRepository;

    @Autowired
    private CacheService cacheService;
    @Async("taskExecutor")
    public void loadRankingsFromCSV(String directoryPath) {
        loadRankingsFromCSVSync(directoryPath);
    }

    public void loadRankingsFromCSVSync(String directoryPath) {
        File dir = new File(directoryPath);
        File[] files = dir.listFiles((d, name) -> name.matches("CORE\\d{4}-all\\.csv"));
        ImportProcessingResult totalResult = new ImportProcessingResult(20);

        if (files == null) {
            logger.error("Directory not found or is empty: {}", directoryPath);
            return;
        }

        for (File file : files) {
            String fileName = file.getName();
            logger.info("Parsing file: {}", fileName);
            ImportProcessingResult fileResult = new ImportProcessingResult(20);

            int year = Integer.parseInt(fileName.substring(4, 8));

            try (CSVReader reader = new CSVReader(new FileReader(file))) {
                List<String[]> rows = reader.readAll();

                for (int i = 1; i < rows.size(); i++) { // Start from row 1 to skip headers
                    fileResult.markProcessed();
                    totalResult.markProcessed();
                    try {
                        updateRankingFromRow(rows.get(i), year);
                        fileResult.markImported();
                        totalResult.markImported();
                    } catch (RuntimeException ex) {
                        fileResult.markSkipped("file=" + fileName + ", row=" + i + ", error=" + ex.getMessage());
                        totalResult.markSkipped("file=" + fileName + ", row=" + i + ", error=" + ex.getMessage());
                        logger.warn("Skipping malformed CORE row: file={}, row={}, message={}", fileName, i, ex.getMessage());
                    }
                }

                logger.info("CORE import summary for {}: processed={}, imported={}, skipped={}, errors={}, sample={}",
                        fileName,
                        fileResult.getProcessedCount(),
                        fileResult.getImportedCount(),
                        fileResult.getSkippedCount(),
                        fileResult.getErrorCount(),
                        fileResult.getErrorsSample());

                logger.info("Syncing cache...");
                cacheService.syncCoreConferenceRankingCacheToDb(); // Sync the caches to the database at the end
                logger.info("Cache synced...");
            } catch (IOException | CsvException e) {
                totalResult.markError("file=" + fileName + ", error=" + e.getMessage());
                logger.error("Error reading the CSV file: {}", e);
            }
        }
        logger.info("Total CORE import summary: processed={}, imported={}, skipped={}, errors={}, sample={}",
                totalResult.getProcessedCount(),
                totalResult.getImportedCount(),
                totalResult.getSkippedCount(),
                totalResult.getErrorCount(),
                totalResult.getErrorsSample());
    }

    private void updateRankingFromRow(String[] row, int year) {
        switch (year) {
            case 2008:
                updateRankingFromRow2008(row, year);
                break;
            case 2010:
                updateRankingFromRow2010(row, year);
                break;
            case 2013:
                updateRankingFromRow2013(row, year);
                break;
            case 2014:
                updateRankingFromRow2014(row, year);
                break;
            case 2017:
                updateRankingFromRow2017(row, year);
                break;
            case 2018:
                updateRankingFromRow2018(row, year);
                break;
            case 2020:
                updateRankingFromRow2020(row, year);
                break;
            case 2021:
                updateRankingFromRow2021(row, year);
                break;
            case 2023:
                updateRankingFromRow2023(row, year);
                break;
            default:
                logger.error("Unsupported year format: {}", year);
                break;
        }
    }

    private void updateRankingFromRow2008(String[] row, int year) {
        String id = null;
        String name = row[1].trim();
        String source = row[3].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = null;
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, "", name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2010(String[] row, int year) {
        // Update column indices according to the CSV structure for 2010
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = null;
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2013(String[] row, int year) {
        // Update column indices according to the CSV structure for 2013
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, null, null, year);
    }

    private void updateRankingFromRow2014(String[] row, int year) {
        // Update column indices according to the CSV structure for 2014
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = null;
        String[] fieldsOfResearchNames = null;
        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2017(String[] row, int year) {
        // Update column indices according to the CSV structure for 2017
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = null;
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2018(String[] row, int year) {
        // Update column indices according to the CSV structure for 2018
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = null;
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2020(String[] row, int year) {
        // Update column indices according to the CSV structure for 2020
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = row[6].trim().split(";");
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2021(String[] row, int year) {
        // Update column indices according to the CSV structure for 2021
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = row[6].trim().split(";");
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRankingFromRow2023(String[] row, int year) {
        // Update column indices according to the CSV structure for 2023
        String id = null;
        String source = row[3].trim();
        String sourceId = row[0].trim();
        String name = row[1].trim();
        String acronym = row[2].trim();
        CoreConferenceRanking.Rank rank = parseRank(row[4].trim());
        String rankString = row[4].trim();
        String[] fieldsOfResearch = row[6].trim().split(";");
        String[] fieldsOfResearchNames = null;

        updateRanking(id, source, sourceId, name, acronym, rank, rankString, fieldsOfResearch, fieldsOfResearchNames, year);
    }

    private void updateRanking(String id, String source, String sourceId, String name, String acronym, CoreConferenceRanking.Rank rank, String rankString, String[] fieldsOfResearch, String[] fieldsOfResearchNames, int year) {
        CoreConferenceRanking ranking = null;
        int pos = -1;

        List<CoreConferenceRanking> cachedConfRankings = cacheService.getCachedConfRankings(acronym);
        for (int i = 0; i < cachedConfRankings.size(); i++) {
            CoreConferenceRanking cr = cachedConfRankings.get(i);
            if (cr.getId().equals(CoreConferenceRanking.getGeneratedId(acronym, name))) {
                ranking = cr;
                pos = i;
                break;
            }
        }


        if (ranking == null) {
            ranking = new CoreConferenceRanking();
            ranking.setId(id);
            ranking.setSource(source);
            ranking.setSourceId(sourceId);
            ranking.setName(name);
            ranking.setAcronym(acronym);
            ranking.generateId();
        }

        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(rank);
        yearlyRanking.setRankString(rankString);
        yearlyRanking.setFieldsOfResearch(fieldsOfResearch);
        yearlyRanking.setFieldsOfResearchNames(fieldsOfResearchNames);

        Map<Integer, CoreConferenceRanking.YearlyRanking> yearlyRankings = ranking.getYearlyRankings();
        if (yearlyRankings == null) {
            yearlyRankings = new HashMap<>();
            ranking.setYearlyRankings(yearlyRankings);
        }
        yearlyRankings.put(year, yearlyRanking);

//        coreConferenceRankingRepository.save(ranking);
        if(pos > -1){
            cacheService.getCachedConfRankings(acronym).set(pos, ranking);
        } else{
            cacheService.getCachedConfRankings(acronym).add(ranking);
        }

        logger.debug("Updated ranking for conference {}", ranking.getName());
    }

    private CoreConferenceRanking.Rank parseRank(String rankString) {
        rankString = rankString.replace("*", "_STAR");
        if(rankString.contains("National"))
            rankString = "D";
        try {
            return CoreConferenceRanking.Rank.valueOf(rankString);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid rank value: {}", rankString);
            return CoreConferenceRanking.Rank.NON_RANK;
        }
    }
}
