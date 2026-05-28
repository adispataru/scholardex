package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateXlsxRendererExpansionTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @Test
    void twelveConferencesFitWhenFixedTableExpands() throws Exception {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            PublicationSnapshotItem p = new PublicationSnapshotItem();
            p.setItemKey("p" + i);
            p.setTitle("Conference paper " + i);
            p.setAuthors("Author " + i);
            p.setForumName("Conf " + i);
            p.setVolumeInfo("Vol " + i);
            p.setYear(2020 + (i % 5));
            p.setForumCategoryLetter("A");
            p.setAuthorCount(2);
            p.setIsWorkshopDaNu("NU");
            rows.add(p.toRowMap());
        }

        byte[] bytes = renderer.render(binding, Map.of("conference-publications", rows));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = out.getSheet("B-Conferinte");
            assertThat(sheet).isNotNull();

            // All 12 rows present: rows 8..19 (0-based 7..18) carry the data we wrote.
            for (int i = 0; i < 12; i++) {
                Row r = sheet.getRow(7 + i);
                assertThat(r).as("row %d", 7 + i).isNotNull();
                assertThat(r.getCell(2).getStringCellValue()).isEqualTo("Conference paper " + (i + 1));
                assertThat(r.getCell(7).getStringCellValue()).isEqualTo("A");
            }

            // The Punctaj P formula must exist on every data row, including the new ones we cloned.
            // Spot-check rows 8 (original), 17 (last original), 18 (first new), 19 (last new).
            for (int dataRowIdx0 : new int[] { 7, 16, 17, 18 }) {
                Cell k = sheet.getRow(dataRowIdx0).getCell(10);
                assertThat(k.getCellType()).as("K cell on row %d", dataRowIdx0 + 1).isEqualTo(CellType.FORMULA);
                assertThat(k.getCellFormula()).contains("H" + (dataRowIdx0 + 1));
            }

            // Aggregation block moved down by 2 rows (was at 19-25, now at 21-27).
            // K21 should be SUMIF on the extended range H8:H19.
            Cell totalAA = findRowByLabel(sheet, "Total categoria A*");
            assertThat(totalAA).as("Total categoria A* row").isNotNull();
            int totalAARowIdx0 = totalAA.getRowIndex();
            Cell aggK = sheet.getRow(totalAARowIdx0).getCell(10);
            assertThat(aggK.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(aggK.getCellFormula())
                    .as("Aggregation SUMIF should extend through the new last data row")
                    .contains("H8:H19").contains("K8:K19");

            // The TOTAL row formula should reference the moved-down aggregation rows.
            Cell totalLabel = findRowByLabel(sheet, "TOTAL criteriu conferinte");
            assertThat(totalLabel).isNotNull();
            Cell totalK = sheet.getRow(totalLabel.getRowIndex()).getCell(10);
            assertThat(totalK.getCellType()).isEqualTo(CellType.FORMULA);
        }
    }

    @Test
    void warnAndTruncatePolicyStillTruncatesWhenSet() throws Exception {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        // Switch the conference role to legacy policy in-memory.
        binding.getRoles().stream()
                .filter(r -> "conference-publications".equals(r.getRoleKey()))
                .findFirst().orElseThrow()
                .setOverflowPolicy(ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingOverflowPolicy.WARN_AND_TRUNCATE);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            PublicationSnapshotItem p = new PublicationSnapshotItem();
            p.setTitle("Conference paper " + i);
            p.setForumCategoryLetter("A");
            rows.add(p.toRowMap());
        }

        byte[] bytes = renderer.render(binding, Map.of("conference-publications", rows));
        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = out.getSheet("B-Conferinte");
            // Only first 10 written; row 18 (1-based) keeps template placeholder, row 19+ untouched.
            assertThat(sheet.getRow(16).getCell(2).getStringCellValue()).isEqualTo("Conference paper 10");
            // Aggregation block stays at row 19 (index 18) — not shifted.
            Cell totalAA = findRowByLabel(sheet, "Total categoria A*");
            assertThat(totalAA.getRowIndex()).isEqualTo(18);
        }
    }

    private Cell findRowByLabel(Sheet sheet, String label) {
        for (Row r : sheet) {
            Cell c = r.getCell(2); // column C carries the labels
            if (c != null && c.getCellType() == CellType.STRING && label.equals(c.getStringCellValue())) {
                return c;
            }
        }
        return null;
    }
}
