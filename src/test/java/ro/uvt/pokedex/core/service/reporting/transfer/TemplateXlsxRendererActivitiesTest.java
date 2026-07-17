package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateXlsxRendererActivitiesTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @Test
    void rendersActivitiesIntoCorrectBlocksAndPreservesTotalFormulas() throws Exception {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        ActivitySnapshotItem book = activity("Carti/Capitole", "Co-authored research monograph",  "AA", 12.0);
        ActivitySnapshotItem grant = activity("Granturi", "PI on Horizon Europe project",  "A",   8.0);
        ActivitySnapshotItem prizeLow  = activity("Premii",     "Faculty teaching award",         "C",   2.0);
        ActivitySnapshotItem prizeHigh = activity("Premii",     "Best paper award (top conf)",    "A",   8.0);

        byte[] bytes = renderer.render(binding, Map.of(
                "activities-perspectiva-d",
                List.of(book.toRowMap(), grant.toRowMap(), prizeLow.toRowMap(), prizeHigh.toRowMap())
        ));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = out.getSheet("Indicatorul I");
            assertThat(sheet).isNotNull();

            // Carti/Capitole — firstDataRow=10 (index 9).
            Row carti = sheet.getRow(9);
            assertThat(carti.getCell(2).getStringCellValue()).isEqualTo("Co-authored research monograph");
            assertThat(carti.getCell(7).getStringCellValue()).isEqualTo("AA");
            assertThat(carti.getCell(10).getNumericCellValue()).isEqualTo(12.0);

            // Grant Cercetare (CS "Granturi" block) — firstDataRow=34 (index 33).
            Row granturi = sheet.getRow(33);
            assertThat(granturi.getCell(2).getStringCellValue()).isEqualTo("PI on Horizon Europe project");
            assertThat(granturi.getCell(7).getStringCellValue()).isEqualTo("A");
            assertThat(granturi.getCell(10).getNumericCellValue()).isEqualTo(8.0);

            // Premii — two matching rows; both written, block expanded by 1.
            // firstDataRow=76 (index 75), second row at 77 (index 76), total moves to 78 (index 77).
            Row premiiFirst = sheet.getRow(75);
            assertThat(premiiFirst.getCell(2).getStringCellValue()).isEqualTo("Faculty teaching award");
            assertThat(premiiFirst.getCell(7).getStringCellValue()).isEqualTo("C");
            assertThat(premiiFirst.getCell(10).getNumericCellValue()).isEqualTo(2.0);

            Row premiiSecond = sheet.getRow(76);
            assertThat(premiiSecond.getCell(2).getStringCellValue()).isEqualTo("Best paper award (top conf)");
            assertThat(premiiSecond.getCell(7).getStringCellValue()).isEqualTo("A");
            assertThat(premiiSecond.getCell(10).getNumericCellValue()).isEqualTo(8.0);

            // A block with no matches (Editor proceedings, firstDataRow=16 / index 15) must not be touched:
            // template placeholder for the data row was empty, so its C cell should remain null/blank.
            Row editor = sheet.getRow(15);
            assertThat(editor.getCell(2)).matches(c -> c == null || c.getStringCellValue().isEmpty()
                    || c.getCellType() == CellType.BLANK);

            // Total row moved down by 1; its SUM was rewritten to span the expanded data range.
            Cell premiiTotal = sheet.getRow(77).getCell(10);
            assertThat(premiiTotal.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(premiiTotal.getCellFormula()).isEqualTo("SUM(K76:K77)");
        }
    }

    private ActivitySnapshotItem activity(String name, String description, String category, double score) {
        ActivitySnapshotItem a = new ActivitySnapshotItem();
        a.setItemKey(name + ":" + description);
        a.setActivityName(name);
        a.setDescription(description);
        a.setCategory(category);
        a.setScore(score);
        return a;
    }
}
