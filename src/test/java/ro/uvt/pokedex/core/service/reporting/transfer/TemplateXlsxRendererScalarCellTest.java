package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingScalarCell;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H81 Slice 2 — the INDICATOR_TOTAL scalar-cell export policy: a named indicator's per-role run total
 * (e.g. the perspective-d "Număr proiecte ca director" count) is stamped into a fixed template cell.
 */
class TemplateXlsxRendererScalarCellTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";
    private static final String CELL = "Centralizator!D40";
    private static final String SOURCE_ROLE = "perspectiva-d-director-count";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @Test
    void indicatorTotalScalarCellIsStampedFromTotals() throws Exception {
        TemplateBinding binding = bindingWithScalarCell(BindingPolicy.INDICATOR_TOTAL);

        byte[] bytes = renderer.render(binding, Map.of(), Map.of(), Map.of(SOURCE_ROLE, 1.0));

        assertThat(numericAt(bytes, CELL)).isEqualTo(1.0);
    }

    @Test
    void manualScalarCellIsLeftUntouched() throws Exception {
        TemplateBinding binding = bindingWithScalarCell(BindingPolicy.MANUAL);

        // Even with a matching total available, a MANUAL cell (Hirsch indices) must not be written.
        byte[] bytes = renderer.render(binding, Map.of(), Map.of(), Map.of(SOURCE_ROLE, 7.0));

        assertThat(cellIsBlankOrAbsent(bytes, CELL)).isTrue();
    }

    @Test
    void indicatorTotalWithNoMatchingTotalIsSkipped() throws Exception {
        TemplateBinding binding = bindingWithScalarCell(BindingPolicy.INDICATOR_TOTAL);

        byte[] bytes = renderer.render(binding, Map.of(), Map.of(), Map.of("some-other-role", 3.0));

        assertThat(cellIsBlankOrAbsent(bytes, CELL)).isTrue();
    }

    /** Reuse the real 2016 template resource but declare a single scalar cell so the test is self-contained
     *  (the 2016 binding ships only MANUAL Hirsch cells; the INDICATOR_TOTAL cell arrives with the 2026 template). */
    private TemplateBinding bindingWithScalarCell(BindingPolicy policy) throws Exception {
        TemplateBinding real = loader.load(BINDING_RESOURCE);
        TemplateBinding b = new TemplateBinding();
        b.setTemplateResource(real.getTemplateResource());
        BindingScalarCell sc = new BindingScalarCell();
        sc.setCell(CELL);
        sc.setPolicy(policy);
        sc.setSource(SOURCE_ROLE);
        b.setScalarCells(List.of(sc));
        return b;
    }

    private double numericAt(byte[] bytes, String cellRef) throws Exception {
        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            CellReference ref = new CellReference(cellRef);
            Cell cell = out.getSheet(ref.getSheetName()).getRow(ref.getRow()).getCell(ref.getCol());
            return cell.getNumericCellValue();
        }
    }

    private boolean cellIsBlankOrAbsent(byte[] bytes, String cellRef) throws Exception {
        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            CellReference ref = new CellReference(cellRef);
            Sheet sheet = out.getSheet(ref.getSheetName());
            if (sheet.getRow(ref.getRow()) == null) return true;
            Cell cell = sheet.getRow(ref.getRow()).getCell(ref.getCol());
            return cell == null || cell.getCellType() == CellType.BLANK;
        }
    }
}
