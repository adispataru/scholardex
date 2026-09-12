package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingScalarCell;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TileData;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H106 S1 — every cell a binding declares as a write target must accept a write on the bundled template,
 * whatever tool last saved that template. The Informatică 2026 template was re-saved with inline strings
 * (openpyxl, H81) and POI silently dropped every string written into its pre-existing cells for three
 * months; nothing caught it because the renderer tests only loaded the 2016 file. This test stamps a
 * distinct sentinel into every WRITE column of every FIXED_TABLE / STACKED_BLOCKS role, into every tile
 * title + inner column of every TILED_SHEETS role and into every INDICATOR_TOTAL scalar cell, then re-reads
 * the workbook and asserts each sentinel is there — and that no template sample text survived in those cells.
 */
class TemplateXlsxRendererTemplateWritabilityTest {

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @ParameterizedTest
    @ValueSource(strings = {
            "report-templates/informatica-2016/binding.json",
            "report-templates/informatica-2026/binding.json"
    })
    void everyBoundCellAcceptsAWrite(String bindingResource) throws Exception {
        TemplateBinding binding = loader.load(bindingResource);
        Map<String, String> sampleTextByCell = templateSampleText(binding);

        Map<String, List<Map<String, Object>>> rowsByRole = new LinkedHashMap<>();
        Map<String, List<TileData>> tilesByRole = new LinkedHashMap<>();
        Map<String, Double> totalsByRole = new HashMap<>();
        // sheet!A1 → expected sentinel
        Map<String, String> expectations = new LinkedHashMap<>();
        int tileIndex = 0;

        for (BindingRole role : binding.getRoles()) {
            switch (role.getKind()) {
                case FIXED_TABLE -> {
                    Map<String, Object> row = new HashMap<>();
                    for (Map.Entry<String, BindingColumn> e : role.getColumns().entrySet()) {
                        if (!isWrite(e.getValue())) continue;
                        String sentinel = "W:" + role.getRoleKey() + ":" + e.getKey();
                        row.put(e.getValue().getSource(), sentinel);
                        expectations.put(role.getSheet() + "!" + e.getKey() + role.getFirstDataRow(), sentinel);
                    }
                    rowsByRole.put(role.getRoleKey(), List.of(row));
                }
                case STACKED_BLOCKS -> {
                    BindingBlock block = role.getBlocks().get(0);
                    Map<String, Object> row = new HashMap<>();
                    row.put(role.getGroupingKey(), block.getActivityName());
                    for (Map.Entry<String, BindingColumn> e : role.getBlockColumns().entrySet()) {
                        if (!isWrite(e.getValue())) continue;
                        String sentinel = "W:" + role.getRoleKey() + ":" + e.getKey();
                        row.put(e.getValue().getSource(), sentinel);
                        expectations.put(role.getSheet() + "!" + e.getKey() + block.getFirstDataRow(), sentinel);
                    }
                    rowsByRole.put(role.getRoleKey(), List.of(row));
                }
                case TILED_SHEETS -> {
                    tileIndex++;
                    String sheetName = role.getSheetNameTemplate()
                            .replace("{index:02d}", String.format("%02d", tileIndex))
                            .replace("{index}", String.valueOf(tileIndex));
                    Map<String, Object> header = new HashMap<>();
                    header.put("publication.title", "W:" + role.getRoleKey() + ":title");
                    Map<String, Object> inner = new HashMap<>();
                    for (Map.Entry<String, BindingColumn> e : role.getInnerColumns().entrySet()) {
                        if (!isWrite(e.getValue())) continue;
                        String sentinel = "W:" + role.getRoleKey() + ":" + e.getKey();
                        inner.put(e.getValue().getSource(), sentinel);
                        expectations.put(sheetName + "!" + e.getKey() + role.getInnerTableFirstDataRow(), sentinel);
                    }
                    for (Map.Entry<String, String> e : role.getPerTileScalar().entrySet()) {
                        String sentinel = "W:" + role.getRoleKey() + ":" + e.getKey();
                        header.put(e.getValue(), sentinel);
                        expectations.put(sheetName + "!" + e.getKey(), sentinel);
                    }
                    tilesByRole.put(role.getRoleKey(), List.of(new TileData(header, List.of(inner))));
                    // The title cell gets the expanded template, which must contain the sentinel title.
                    expectations.put(sheetName + "!" + role.getPerTileTitleCell(), "W:" + role.getRoleKey() + ":title");
                }
            }
        }
        List<String> numericScalarCells = new ArrayList<>();
        for (BindingScalarCell scalar : binding.getScalarCells()) {
            if (scalar.getPolicy() != BindingPolicy.INDICATOR_TOTAL || scalar.getSource() == null) continue;
            totalsByRole.put(scalar.getSource(), 42.0);
            numericScalarCells.add(scalar.getCell());
        }

        byte[] bytes = renderer.render(binding, rowsByRole, tilesByRole, totalsByRole);

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Map.Entry<String, String> e : expectations.entrySet()) {
                Cell cell = cellAt(out, e.getKey());
                assertThat(cell).as("cell %s exists", e.getKey()).isNotNull();
                assertThat(cell.getCellType()).as("cell %s is a string", e.getKey()).isEqualTo(CellType.STRING);
                assertThat(cell.getStringCellValue()).as("cell %s carries the written value", e.getKey())
                        .contains(e.getValue());
                String sample = sampleTextByCell.get(e.getKey());
                if (sample != null && !sample.isBlank()) {
                    assertThat(cell.getStringCellValue()).as("cell %s dropped the template sample text", e.getKey())
                            .doesNotContain(sample);
                }
            }
            for (String ref : numericScalarCells) {
                Cell cell = cellAt(out, ref);
                assertThat(cell).as("scalar %s exists", ref).isNotNull();
                assertThat(cell.getCellType()).as("scalar %s is numeric", ref).isEqualTo(CellType.NUMERIC);
                assertThat(cell.getNumericCellValue()).isEqualTo(42.0);
            }
            // No cell of the output may still be an inline string: the normaliser ran on the template and
            // every write went through the reset-first helpers.
            for (Sheet sheet : out) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell instanceof XSSFCell x) {
                            assertThat(x.getCTCell().getT()).as("%s!%s inline string", sheet.getSheetName(), cell.getAddress())
                                    .isNotEqualTo(STCellType.INLINE_STR);
                        }
                    }
                }
            }
        }
    }

    private static boolean isWrite(BindingColumn col) {
        return col.getPolicy() == BindingPolicy.WRITE || col.getPolicy() == BindingPolicy.WRITE_SCORE;
    }

    /** The template's own text at the cells this test will write, read straight from the bundled file. */
    private Map<String, String> templateSampleText(TemplateBinding binding) throws Exception {
        String path = binding.getTemplateResource().replace("classpath:", "");
        Map<String, String> out = new HashMap<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path);
             Workbook wb = WorkbookFactory.create(in)) {
            for (BindingRole role : binding.getRoles()) {
                switch (role.getKind()) {
                    case FIXED_TABLE -> role.getColumns().forEach((col, c) -> {
                        if (isWrite(c)) put(out, wb, role.getSheet() + "!" + col + role.getFirstDataRow());
                    });
                    case STACKED_BLOCKS -> role.getBlockColumns().forEach((col, c) -> {
                        if (isWrite(c)) put(out, wb, role.getSheet() + "!" + col + role.getBlocks().get(0).getFirstDataRow());
                    });
                    case TILED_SHEETS -> {
                        // The output sheet is a clone of the template sheet; key the sample text by the
                        // first clone's name so the expectation lookup above finds it.
                        String tpl = role.getTemplateSheet();
                        String clone = role.getSheetNameTemplate().replace("{index:02d}", "01").replace("{index}", "1");
                        put(out, wb, tpl + "!" + role.getPerTileTitleCell(), clone + "!" + role.getPerTileTitleCell());
                        role.getInnerColumns().forEach((col, c) -> {
                            if (isWrite(c)) put(out, wb, tpl + "!" + col + role.getInnerTableFirstDataRow(),
                                    clone + "!" + col + role.getInnerTableFirstDataRow());
                        });
                    }
                }
            }
        }
        return out;
    }

    private static void put(Map<String, String> out, Workbook wb, String ref) {
        put(out, wb, ref, ref);
    }

    private static void put(Map<String, String> out, Workbook wb, String templateRef, String outputRef) {
        Cell cell = cellAt(wb, templateRef);
        if (cell != null && cell.getCellType() == CellType.STRING) {
            out.put(outputRef, cell.getStringCellValue());
        }
    }

    /** {@code sheet!A1} with the sheet name unquoted (binding scalar cells may already quote it). */
    private static Cell cellAt(Workbook wb, String ref) {
        int bang = ref.lastIndexOf('!');
        String sheetName = ref.substring(0, bang).replace("'", "");
        CellReference cr = new CellReference(ref.substring(bang + 1));
        Sheet sheet = wb.getSheet(sheetName);
        if (sheet == null) return null;
        Row row = sheet.getRow(cr.getRow());
        return row == null ? null : row.getCell(cr.getCol());
    }
}
