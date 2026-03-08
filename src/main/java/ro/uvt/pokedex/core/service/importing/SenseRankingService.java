package ro.uvt.pokedex.core.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.repository.reporting.CoreConferenceRankingRepository;
import ro.uvt.pokedex.core.repository.reporting.SenseRankingRepository;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@Service
public class SenseRankingService {

    private static final Logger logger = LoggerFactory.getLogger(SenseRankingService.class);

    @Autowired
    private CoreConferenceRankingRepository coreConferenceRankingRepository;

    @Autowired
    private SenseRankingRepository bookRankingRepository;

    @Async("taskExecutor")
    public void importBookRankingsFromExcel(String excelFilePath) {
        importBookRankingsFromExcelSync(excelFilePath);
    }

    public void importBookRankingsFromExcelSync(String excelFilePath) {
        try (FileInputStream fis = new FileInputStream(excelFilePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // Assuming the first sheet
            int numRows = sheet.getPhysicalNumberOfRows();

            for (int i = 1; i < numRows; i++) { // Start from row 1 to skip headers
                Row row = sheet.getRow(i);
                if (row != null) {
                    SenseBookRanking bookRanking = createSenseBookRankingFromRow(row);
                    bookRankingRepository.save(bookRanking);
                    logger.debug("Read row for book {}", bookRanking.getName());
                }
            }
            logger.info("Successfully loaded and saved book rankings from the Excel file.");
        } catch (IOException e) {
            logger.error("Error reading the Excel file: ", e);
        }
    }

    private SenseBookRanking createSenseBookRankingFromRow(Row row) {
        SenseBookRanking bookRanking = new SenseBookRanking();
        bookRanking.setName(row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
        String rankingString = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue().toUpperCase();

        Optional<SenseBookRanking.Rank> rankingEnum = getEnumFromString(SenseBookRanking.Rank.class, rankingString);
        rankingEnum.ifPresent(bookRanking::setRanking);

        if (rankingEnum.isEmpty()) {
            logger.error("Invalid ranking value: {}", rankingString);
        }
        return bookRanking;
    }

    private <T extends Enum<T>> Optional<T> getEnumFromString(Class<T> enumClass, String value) {
        try {
            return Optional.of(Enum.valueOf(enumClass, value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
