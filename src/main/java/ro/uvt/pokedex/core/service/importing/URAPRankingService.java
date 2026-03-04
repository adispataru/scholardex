package ro.uvt.pokedex.core.service.importing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.repository.URAPUniversityRankingRepository;
import ro.uvt.pokedex.core.service.importing.model.ImportProcessingResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class URAPRankingService {

    private static final Pattern FILE_PATTERN = Pattern.compile("URAP_WR_\\d{4}\\.xlsx");
    private final URAPUniversityRankingRepository urapUniversityRankingRepository;

    @Transactional
    public void loadRankingsFromFolder(String folderPath) {

        if(urapUniversityRankingRepository.count() > 0)
            return ;
        Map<String, URAPUniversityRanking> rankingsMap = new HashMap<>();
        ImportProcessingResult totalResult = new ImportProcessingResult(20);

        try (Stream<Path> paths = Files.walk(Paths.get(folderPath))) {
            paths.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> FILE_PATTERN.matcher(name).matches())
                    .forEach(fileName -> {
                        int year = extractYear(fileName);
                        String fullPath = Paths.get(folderPath, fileName).toString();
                        processExcelFile(fullPath, year, rankingsMap, totalResult);
                    });
        } catch (IOException e) {
            log.error("Error while scanning directory: {}", folderPath, e);
            throw new RuntimeException("Failed to read rankings directory", e);
        }

        // Save all rankings to repository
        List<URAPUniversityRanking> rankings = new ArrayList<>(rankingsMap.values());
        urapUniversityRankingRepository.saveAll(rankings);
        log.info("URAP import summary for {}: processed={}, imported={}, skipped={}, errors={}, sample={}",
                folderPath,
                totalResult.getProcessedCount(),
                totalResult.getImportedCount(),
                totalResult.getSkippedCount(),
                totalResult.getErrorCount(),
                totalResult.getErrorsSample());
        log.info("Successfully loaded {} URAP rankings from folder: {}", rankings.size(), folderPath);
    }

    private int extractYear(String fileName) {
        return Integer.parseInt(fileName.substring(8, 12));
    }

    private void processExcelFile(String filePath, int year, Map<String, URAPUniversityRanking> rankingsMap, ImportProcessingResult result) {
        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            boolean isFirstRow = true;

            for (Row row : sheet) {
                if (isFirstRow) {
                    isFirstRow = false;
                    continue; // Skip header row
                }

                result.markProcessed();
                if (processRow(row, year, rankingsMap)) {
                    result.markImported();
                } else {
                    result.markSkipped("file=" + filePath + ", year=" + year + ", row=" + row.getRowNum());
                }
            }

        } catch (IOException e) {
            result.markError("file=" + filePath + ", error=" + e.getMessage());
            log.error("Error reading Excel file: {}", filePath, e);
            throw new RuntimeException("Failed to read Excel file: " + filePath, e);
        }
    }

    private boolean processRow(Row row, int year, Map<String, URAPUniversityRanking> rankingsMap) {
        try {
            String universityName = getStringValue(row.getCell(1));

            // Try to get existing ranking from map or database
            URAPUniversityRanking ranking = rankingsMap.computeIfAbsent(universityName, k -> {
                return urapUniversityRankingRepository.findByNameIgnoreCase(universityName)
                        .stream()
                        .findFirst()
                        .orElseGet(() -> {
                            URAPUniversityRanking newRanking = new URAPUniversityRanking();
                            newRanking.setName(universityName);
                            newRanking.setCountry(getStringValue(row.getCell(2)));
                            newRanking.setScores(new HashMap<>());
                            return newRanking;
                        });
            });

            URAPUniversityRanking.Score score = new URAPUniversityRanking.Score();
            score.setRank(Integer.parseInt(getStringValue(row.getCell(0))));
            score.setArticle(getDoubleValue(row.getCell(3)));
            score.setCitation(getDoubleValue(row.getCell(4)));
            score.setTotalDocument(getDoubleValue(row.getCell(5)));
            score.setAIT(getDoubleValue(row.getCell(6)));
            score.setCIT(getDoubleValue(row.getCell(7)));
            score.setCollaboration(getDoubleValue(row.getCell(8)));
            score.setTotal(getDoubleValue(row.getCell(9)));

            ranking.getScores().put(year, score);
            return true;

        } catch (Exception e) {
            log.warn("Error parsing row in year {}: {}", year, e.getMessage());
            return false;
        }
    }

    private String getStringValue(Cell cell) {
        if (cell == null) return "";
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue();
    }

    private double getDoubleValue(Cell cell) {
        if (cell == null) return 0.0;
        try {
            return cell.getNumericCellValue();
        } catch (IllegalStateException e) {
            String stringValue = cell.getStringCellValue().trim();
            if (stringValue.isEmpty()) return 0.0;
            return Double.parseDouble(stringValue);
        }
    }
}
