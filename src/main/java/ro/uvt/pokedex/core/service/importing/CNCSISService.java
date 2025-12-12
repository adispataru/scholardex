package ro.uvt.pokedex.core.service.importing;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.CNCSISPublisher;
import ro.uvt.pokedex.core.model.SenseBookRanking;
import ro.uvt.pokedex.core.repository.reporting.CNCSISPublisherRepository;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;

@Service
public class CNCSISService {

    private static final Logger logger = LoggerFactory.getLogger(CNCSISService.class);

    @Autowired
    private CNCSISPublisherRepository cncsisPublisherRepository;

    @Async("taskExecutor")
    public void importPublisherListFromExcel(String excelFilePath) {
        if(cncsisPublisherRepository.count() > 0) return;
        try (FileInputStream fis = new FileInputStream(excelFilePath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // Assuming the first sheet
            int numRows = sheet.getPhysicalNumberOfRows();

            for (int i = 1; i < numRows; i++) { // Start from row 1 to skip headers
                Row row = sheet.getRow(i);
                if (row != null) {
                    CNCSISPublisher publisher = createSenseBookRankingFromRow(row);
                    logger.debug("Read row for publisher {}", publisher.getName());
                    cncsisPublisherRepository.save(publisher);
                }
            }
            logger.info("Successfully loaded and saved book rankings from the Excel file.");
        } catch (IOException e) {
            logger.error("Error reading the Excel file: ", e);
        }
    }

    private CNCSISPublisher createSenseBookRankingFromRow(Row row) {
        CNCSISPublisher publisher = new CNCSISPublisher();
        publisher.setCncsisId((long) row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getNumericCellValue());
        publisher.setName(row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
        publisher.setCity(row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());
        publisher.setWebpage(row.getCell(4, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getStringCellValue());

        return publisher;
    }

}
