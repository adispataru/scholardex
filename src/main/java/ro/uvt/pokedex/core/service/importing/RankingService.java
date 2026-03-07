package ro.uvt.pokedex.core.service.importing;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.repository.reporting.RankingRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;
import ro.uvt.pokedex.core.service.importing.wos.WosFactBuilderService;
import ro.uvt.pokedex.core.service.importing.wos.WosImportEventIngestionService;
import ro.uvt.pokedex.core.service.importing.wos.WosIdentityResolutionService;
import ro.uvt.pokedex.core.service.importing.wos.model.WosIdentitySourceContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@Service
public class RankingService {

    private static final Logger logger = LoggerFactory.getLogger(RankingService.class);

    @Autowired
    private RankingRepository rankingRepository;
    @Autowired(required = false)
    private WosImportEventIngestionService wosImportEventIngestionService;
    @Autowired(required = false)
    private WosFactBuilderService wosFactBuilderService;
    @Autowired(required = false)
    private WosIdentityResolutionService wosIdentityResolutionService;

    private Map<Integer, Map<WoSRanking.Quarter, Integer>> yearCounters = new HashMap<>();
    public void initializeCategoriesFromExcel(String filePath, String excelPassword) {
        if(rankingRepository.count() != 0)
            return;
        logger.info("Initializing ranking data from excel {}", filePath);
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis, excelPassword)) {

            Sheet sheet = workbook.getSheetAt(0); // Assuming the first sheet
            int numRows = sheet.getPhysicalNumberOfRows();

            for (int i = 1; i < numRows; i++) { // Start from row 1 to skip headers
                Row row = sheet.getRow(i);
                if (row != null) {
                    initializeRankingFromRow(row, 2022);
                }
            }
            logger.info("Successfully initialized categories from the Excel file.");
        } catch (IOException | EncryptedDocumentException e) {
            logger.error("Error reading the Excel file: {}", e);
        }
    }

    private void initializeRankingFromRow(Row row, int year) {
        String name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
        String issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
        String eIssn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
        String category = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();

        String compositeId = WoSRanking.getGeneratedId(issn, eIssn);
        WoSRanking ranking = compositeId == null ? null : rankingRepository.findById(compositeId).orElse(null);

        if (ranking == null) {
            ranking = new WoSRanking();
            ranking.setName(name);
            ranking.setIssn(issn);
            ranking.setEIssn(eIssn);
            ranking.generateId();
        }

        WoSRanking.Score score = ranking.getScore();
        if(score == null){
            score = new WoSRanking.Score();
        }
        score.getAis().put(year, parseDoubleSafely(row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)));
        WoSRanking.Rank rank = ranking.getWebOfScienceCategoryIndex().get(category);
        if(rank == null){
            rank = new WoSRanking.Rank();
        }
        rank.getQAis().put(year, parseQuarter(row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue()));
        ranking.getWebOfScienceCategoryIndex().put(category, rank);
        ranking.setScore(score);

        rankingRepository.save(ranking);
        logger.debug("Initialized ranking for journal {}", ranking.getName());
    }

    @Async("taskExecutor")
    public void loadRankingsFromExcel(String directoryPath, String excelPassword) {
        ingestWosEventsIfConfigured(directoryPath);
        buildWosFactsIfConfigured();
        ImportProcessingResult totalResult = new ImportProcessingResult(20);
        File dir = new File(directoryPath);
        File[] files = dir.listFiles((d, name) -> name.matches("AIS_\\d{4}\\.xlsx*") || name.matches("RIS_\\d{4}\\.xlsx*"));

        if (files == null) {
            logger.error("Directory not found or is empty: {}", directoryPath);
            return;
        }else{
            for(File f : files){
                logger.info("Found file: {}", f.getName());
            }
        }

        for (File file : files) {
            String fileName = file.getName();
            logger.info("Parsing file: {}", fileName);
            ImportProcessingResult fileResult = new ImportProcessingResult(20);

            int year = Integer.parseInt(fileName.substring(4, 8));
            String metricType = fileName.substring(0, 3);

            try (FileInputStream fis = new FileInputStream(file);
                 Workbook workbook = metricType.equals("AIS") ? WorkbookFactory.create(fis, excelPassword) : WorkbookFactory.create(fis)) {

                Sheet sheet = workbook.getSheetAt(0); // Assuming the first sheet
                int numRows = sheet.getPhysicalNumberOfRows();

                WoSRanking.Quarter prevQuarter = null;

                for (int i = 1; i < numRows; i++) { // Start from row 1 to skip headers
                    Row row = sheet.getRow(i);
                    if (row != null) {
                        fileResult.markProcessed();
                        totalResult.markProcessed();
                        WoSRanking.Quarter currentQuarter = null;
                        if (metricType.equals("AIS") && (year >= 2018 && year <= 2019)) {
                            currentQuarter = parseQuarter(row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getNumericCellValue());
                        } else if (metricType.equals("AIS") && year == 2020) {
                            currentQuarter = parseQuarter(row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getNumericCellValue());
                        } else if (metricType.equals("AIS") && year >= 2021 &&  year < 2023) {
                            currentQuarter = parseQuarter(row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
                        }
                        if (prevQuarter != null && !prevQuarter.equals(currentQuarter)) {
                            resetYearCounters(year, currentQuarter);
                        }
                        prevQuarter = currentQuarter;
                        boolean success = updateRankingFromRow(row, year, metricType);
                        if(!success){
                            fileResult.markSkipped("file=" + fileName + ", row=" + i);
                            totalResult.markSkipped("file=" + fileName + ", row=" + i);
                            logger.error("Error in row {} of file {}", i, fileName);
                        } else {
                            fileResult.markImported();
                            totalResult.markImported();
                        }
                    }
                }
                logger.info("Ranking import summary for {}: processed={}, imported={}, skipped={}, errors={}, sample={}",
                        fileName,
                        fileResult.getProcessedCount(),
                        fileResult.getImportedCount(),
                        fileResult.getSkippedCount(),
                        fileResult.getErrorCount(),
                        fileResult.getErrorsSample());

                logger.info("Processed legacy ranking import file {}", fileName);
            } catch (IOException | EncryptedDocumentException e) {
                totalResult.markError("file=" + fileName + ", error=" + e.getMessage());
                logger.error("Error reading the Excel file: {}", file.getName(), e);
            }
        }
        logger.info("Total ranking import summary: processed={}, imported={}, skipped={}, errors={}, sample={}",
                totalResult.getProcessedCount(),
                totalResult.getImportedCount(),
                totalResult.getSkippedCount(),
                totalResult.getErrorCount(),
                totalResult.getErrorsSample());
    }

    private boolean updateRankingFromRow(Row row, int year, String metricType) {
        // TODO(H14.2/H14.4/H14.5): route parsed source rows through wos.import_events and
        // WosCanonicalContractSupport normalization before writing canonical facts/projections.
        String issn = "";
        String eIssn = "";
        String category = "general";
        Double value = null;
        WoSRanking.Quarter quarter = null;
        String name = "";

        switch (metricType) {
            case "AIS":
                // TODO(H14.4): normalize edition/index tokens with explicit SCIE/SSCI rules
                // (including SCIENCE -> SCIE and bundled SCIE+SSCI split handling).
                if(year >= 2011 && year <= 2013){
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                }else if (year >= 2014 && year < 2018) {
                    name = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                } else if (year <= 2019) {
                    value = parseDoubleSafely(row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                    category = row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    String index = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    category = category + " - " + index;
                    quarter = parseQuarter(row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getNumericCellValue());
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                }
                else if (year == 2020) {
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    eIssn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                    String index = row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    category = row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    category = category + " - " + index;
                    quarter = parseQuarter(row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getNumericCellValue());

                } else if (year == 2021) {
                    eIssn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                    category = row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    quarter = parseQuarter(row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                } else if (year == 2022) {
                    eIssn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                    category = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    quarter = parseQuarter(row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                }
                else if (year == 2023 || year == 2024) {
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    eIssn = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    category = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    String index = row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    category = category + " - " + index;
                    value = parseDoubleSafely(row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                }

                break;
            case "RIS":
                if (year == 2019) {
                    value = parseDoubleSafely(row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                    issn = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                } else if (year >= 2020) {
                    Cell cell = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    if(cell.getCellType().equals(CellType.STRING)) {
                        issn = cell.getStringCellValue();
                    }
                    cell = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    if(cell.getCellType().equals(CellType.STRING)) {
                        eIssn = cell.getStringCellValue();
                    }
                    name = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue();
                    value = parseDoubleSafely(row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK));
                }
                break;
        }

        String compositeId = WoSRanking.getGeneratedId(issn, eIssn);
        if(compositeId == null){
            logger.error("Composite ID is null for journal {} with issn {} and eIssn {}", name, issn, eIssn);
            return false;
        }
        resolveIdentityIfConfigured(issn, eIssn, name, year);
        WoSRanking ranking = rankingRepository.findById(compositeId).orElse(null);

        if (ranking == null) {
            ranking = new WoSRanking();
            ranking.setName(name);
            ranking.setIssn(issn);
            ranking.setEIssn(eIssn);
            ranking.generateId();
        }

        WoSRanking.Score score = ranking.getScore();
        if(score == null){
            score = new WoSRanking.Score();
        }
        switch (metricType) {
            case "AIS" -> score.getAis().put(year, value);
            case "RIS" -> score.getRis().put(year, value);
        }
        ranking.setScore(score);

        if(!category.equals("general")){
            if(ranking.getWebOfScienceCategoryIndex().get(category) != null){
                if(ranking.getWebOfScienceCategoryIndex().get(category).getQAis().isEmpty()){
                    ranking.getWebOfScienceCategoryIndex().remove(category);
                }
            }
            WoSRanking.Rank rank = retrieveCategory(ranking.getWebOfScienceCategoryIndex(), category);
            WoSRanking.Rank generalscore = ranking.getWebOfScienceCategoryIndex().getOrDefault("general", new WoSRanking.Rank());
            switch (metricType) {
                case "AIS":
                    if (quarter != null) {
                        rank.getQAis().put(year, quarter);
                    }
                    break;
            }
            ranking.getWebOfScienceCategoryIndex().put(category, rank);
        }

        rankingRepository.save(ranking);
        logger.debug("Updated ranking for journal {}", ranking.getName());
        return true;
    }

    private void resolveIdentityIfConfigured(String issn, String eIssn, String title, int year) {
        if (wosIdentityResolutionService == null) {
            return;
        }
        try {
            wosIdentityResolutionService.resolveIdentity(
                    issn,
                    eIssn,
                    title,
                    new WosIdentitySourceContext(year, null, null, null, null, null)
            );
        } catch (Exception e) {
            logger.warn("WoS identity resolution hook failed for title='{}': {}", title, e.getMessage());
        }
    }

    private void ingestWosEventsIfConfigured(String directoryPath) {
        if (wosImportEventIngestionService == null) {
            return;
        }
        try {
            ImportProcessingResult result = wosImportEventIngestionService.ingestDirectory(directoryPath, null);
            logger.info("WoS import-events ledger synced before legacy ranking import: processed={}, imported={}, updated={}, skipped={}, errors={}",
                    result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                    result.getSkippedCount(), result.getErrorCount());
        } catch (Exception e) {
            logger.warn("WoS import-events ingestion failed before legacy ranking import: {}", e.getMessage());
        }
    }

    private void buildWosFactsIfConfigured() {
        if (wosFactBuilderService == null) {
            return;
        }
        try {
            ImportProcessingResult result = wosFactBuilderService.buildFactsFromImportEvents();
            logger.info("WoS facts built from ledger: processed={}, imported={}, updated={}, skipped={}, errors={}",
                    result.getProcessedCount(), result.getImportedCount(), result.getUpdatedCount(),
                    result.getSkippedCount(), result.getErrorCount());
        } catch (Exception e) {
            logger.warn("WoS fact-builder failed: {}", e.getMessage());
        }
    }

    private WoSRanking.Rank retrieveCategory(Map<String, WoSRanking.Rank> webOfScienceCategoryIndex, String category) {
        WoSRanking.Rank result =  new WoSRanking.Rank();
        if(webOfScienceCategoryIndex.containsKey(category)) {
            result = webOfScienceCategoryIndex.get(category);
        }else {
            for(String key : webOfScienceCategoryIndex.keySet()){
                if(key.startsWith(category)){
                    result = webOfScienceCategoryIndex.get(key);
                    break;
                }
            }
        }
        return result;
    }

    private Double parseDoubleSafely(Cell cell) {
        try {
            return cell.getNumericCellValue();
        } catch (Exception e) {
            try {
                return Double.parseDouble(cell.getStringCellValue().replace(",", "."));
            }
            catch (Exception e2){
            logger.error("Exception when retrieving Double value: {}", e2.getMessage());
                    return null;
                }
        }
    }

    private WoSRanking.Quarter parseQuarter(String quarterValue) {
        try {
            return "N/A".equals(quarterValue) ? WoSRanking.Quarter.REMOVED : WoSRanking.Quarter.valueOf(quarterValue);
        } catch (Exception e) {
//            logger.error("Exception when retrieving Quarter value: {}", e.getMessage());
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }

    private WoSRanking.Quarter parseQuarter(double quarterValue) {
        String quarter = "Q" + ((int) quarterValue);
        try {
            return WoSRanking.Quarter.valueOf(quarter);
        } catch (Exception e) {
//            logger.error("Exception when retrieving Quarter value: {}", e.getMessage());
            return WoSRanking.Quarter.NOT_FOUND;
        }
    }

    private void resetYearCounters(int year, WoSRanking.Quarter quarter) {
        yearCounters.putIfAbsent(year, new HashMap<>());
        yearCounters.get(year).put(quarter, 0);
    }

    private void updateRankCounters(Map<Integer, Integer> rankCounters, int year, WoSRanking.Quarter quarter) {
        resetYearCounters(year, quarter);
        int currentRank = yearCounters.get(year).get(quarter) + 1;
        yearCounters.get(year).put(quarter, currentRank);
        rankCounters.put(year, currentRank);
    }

    @Deprecated(forRemoval = false)
    public void updateImpactFactorsFromExcel(String fileName, int year) {
        logger.warn("Ignoring deprecated JIF gov ingestion path for file {} and year {}. IF is accepted only from official WoS extracted data.", fileName, year);
    }


    public void deleteWosRankings() {
        logger.info("Deleting all WoS rankings...");
        rankingRepository.deleteAll();
        logger.info("Successfully deleted all WoS rankings.");
    }
}
