package ro.uvt.pokedex.core.service.reporting;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * H129 — the CNATDCU domains a person reports in (cod_DS), as the "Domenii-CNATDCU" sheet of the Anexa 5
 * template lists them: the code, the domain, its branch of science. Read once from the template file of
 * the edition; empty (and logged) when the file is not there, so the page still works without the list.
 */
@Component
public class CnfisDomainCatalog {

    private static final Logger log = LoggerFactory.getLogger(CnfisDomainCatalog.class);
    static final String SHEET = "Domenii-CNATDCU";

    public record CnfisDomain(String code, String name, String branch) {
        public String label() {
            return code + " — " + name + (branch == null || branch.isBlank() ? "" : " (" + branch + ")");
        }
    }

    private volatile List<CnfisDomain> domains;

    public List<CnfisDomain> domains() {
        List<CnfisDomain> loaded = domains;
        if (loaded == null) {
            synchronized (this) {
                if (domains == null) {
                    domains = load(CnfisEdition.EDITION_2025.anexa5Template());
                }
                loaded = domains;
            }
        }
        return loaded;
    }

    public Optional<CnfisDomain> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String wanted = code.trim();
        return domains().stream().filter(d -> d.code().equals(wanted)).findFirst();
    }

    static List<CnfisDomain> load(String templatePath) {
        try (InputStream in = new FileInputStream(templatePath); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheet(SHEET);
            if (sheet == null) {
                log.warn("CNFIS template {} has no sheet {}", templatePath, SHEET);
                return List.of();
            }
            DataFormatter formatter = new DataFormatter();
            List<CnfisDomain> out = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String code = text(formatter, row.getCell(0));
                String name = text(formatter, row.getCell(2));
                if (code.isBlank() || name.isBlank() || !code.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                out.add(new CnfisDomain(code, name, text(formatter, row.getCell(3))));
            }
            return Collections.unmodifiableList(out);
        } catch (IOException | RuntimeException ex) {
            log.warn("CNFIS domain list not read from {}: {}", templatePath, ex.toString());
            return List.of();
        }
    }

    private static String text(DataFormatter formatter, Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
