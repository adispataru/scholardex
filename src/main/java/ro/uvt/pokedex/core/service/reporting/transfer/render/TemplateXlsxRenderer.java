package ro.uvt.pokedex.core.service.reporting.transfer.render;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingOverflowPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingSummaryFormula;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stamps row data into a bundled xlsx template according to a {@link TemplateBinding}. Loads the
 * template fresh on every render so concurrent renders are isolated. Only FIXED_TABLE roles are
 * implemented in H50.2.a; STACKED_BLOCKS and TILED_SHEETS arrive in later sub-slices.
 */
@Component
public class TemplateXlsxRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateXlsxRenderer.class);
    private static final String CLASSPATH_PREFIX = "classpath:";

    public byte[] render(TemplateBinding binding, Map<String, List<Map<String, Object>>> rowsByRole) {
        return render(binding, rowsByRole, Map.of());
    }

    public byte[] render(TemplateBinding binding,
                         Map<String, List<Map<String, Object>>> rowsByRole,
                         Map<String, List<TileData>> tilesByRole) {
        return render(binding, rowsByRole, tilesByRole, Map.of());
    }

    public byte[] render(TemplateBinding binding,
                         Map<String, List<Map<String, Object>>> rowsByRole,
                         Map<String, List<TileData>> tilesByRole,
                         Map<String, Double> totalsByRole) {
        try (InputStream in = openTemplate(binding.getTemplateResource());
             Workbook workbook = WorkbookFactory.create(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (BindingRole role : binding.getRoles()) {
                renderRole(workbook, role, rowsByRole, tilesByRole);
            }
            renderScalarCells(workbook, binding, totalsByRole);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render template " + binding.getTemplateResource(), e);
        }
    }

    /**
     * Write indicator-derived scalar values into fixed template cells. Only {@link BindingPolicy#INDICATOR_TOTAL}
     * cells are stamped — the value is the run's per-role total keyed by the cell's {@code source} role (e.g. the
     * perspective-d "Număr proiecte ca director" count). MANUAL cells (Hirsch indices) are left untouched for the
     * researcher to fill. A missing total or absent sheet is skipped, not fatal, so a template can declare the cell
     * before its indicator is wired.
     */
    private void renderScalarCells(Workbook workbook, TemplateBinding binding, Map<String, Double> totalsByRole) {
        if (binding.getScalarCells() == null) return;
        for (var scalarCell : binding.getScalarCells()) {
            if (scalarCell.getPolicy() != BindingPolicy.INDICATOR_TOTAL || scalarCell.getSource() == null) continue;
            Double total = totalsByRole.get(scalarCell.getSource());
            if (total == null) {
                LOG.warn("Scalar cell '{}': no total for source role '{}'; leaving blank",
                        scalarCell.getCell(), scalarCell.getSource());
                continue;
            }
            CellReference ref = new CellReference(scalarCell.getCell());
            Sheet sheet = ref.getSheetName() != null ? workbook.getSheet(ref.getSheetName()) : null;
            if (sheet == null) {
                LOG.warn("Scalar cell '{}': sheet '{}' not found; skipping", scalarCell.getCell(), ref.getSheetName());
                continue;
            }
            writeCellValue(getOrCreateCell(sheet, ref), total);
        }
    }

    private void renderRole(Workbook workbook, BindingRole role,
                            Map<String, List<Map<String, Object>>> rowsByRole,
                            Map<String, List<TileData>> tilesByRole) {
        switch (role.getKind()) {
            case FIXED_TABLE ->
                    renderFixedTable(workbook, role, rowsByRole.getOrDefault(role.getRoleKey(), List.of()));
            case STACKED_BLOCKS ->
                    renderStackedBlocks(workbook, role, rowsByRole.getOrDefault(role.getRoleKey(), List.of()));
            case TILED_SHEETS ->
                    renderTiledSheets(workbook, role, tilesByRole.getOrDefault(role.getRoleKey(), List.of()));
        }
    }

    private void renderTiledSheets(Workbook workbook, BindingRole role, List<TileData> tiles) {
        int templateIdx = workbook.getSheetIndex(role.getTemplateSheet());
        List<String> tileSheetNames = new ArrayList<>();
        // Per-tile row delta applied to aggregation cells when the inner table expanded.
        java.util.Map<String, Integer> tileOverflowByName = new java.util.HashMap<>();

        for (int i = 0; i < tiles.size(); i++) {
            TileData tile = tiles.get(i);
            Sheet tileSheet = workbook.cloneSheet(templateIdx);
            int cloneIdx = workbook.getSheetIndex(tileSheet);
            String sheetName = expandSheetName(role.getSheetNameTemplate(), i + 1);
            workbook.setSheetName(cloneIdx, sheetName);
            tileSheetNames.add(sheetName);
            CellReference titleRef = new CellReference(role.getPerTileTitleCell());
            writeStringAt(tileSheet, titleRef, expandTitle(role.getPerTileTitleTemplate(), tile.header()));

            for (Map.Entry<String, String> e : role.getPerTileScalar().entrySet()) {
                CellReference ref = new CellReference(e.getKey());
                writeCellValue(getOrCreateCell(tileSheet, ref), tile.header().get(e.getValue()));
            }

            int innerOverflow = Math.max(0, tile.innerRows().size() - role.getInnerTableMaxRows());
            tileOverflowByName.put(sheetName, innerOverflow);

            BindingRole inner = new BindingRole();
            inner.setRoleKey(role.getRoleKey() + "[" + sheetName + "]");
            inner.setSheet(sheetName);
            inner.setFirstDataRow(role.getInnerTableFirstDataRow());
            inner.setMaxRows(role.getInnerTableMaxRows());
            // Leave overflowPolicy null → renderFixedTable defaults to EXPAND, shifting the tile's
            // per-category aggregation rows (Total A*/A/B/C/D, A*+A+B, TOTAL) down by innerOverflow
            // and letting POI's FormulaShifter extend the in-sheet SUMIF/COUNTIF ranges that ended
            // at the original last data row.
            inner.setColumns(role.getInnerColumns());
            renderFixedTable(workbook, inner, tile.innerRows());
        }

        // After cloning, drop the original template sheet so the saved workbook doesn't carry it.
        workbook.removeSheetAt(workbook.getSheetIndex(role.getTemplateSheet()));

        regenerateSummaryFormulas(workbook, role, tileSheetNames, tileOverflowByName);
    }

    private void regenerateSummaryFormulas(Workbook workbook, BindingRole role,
                                           List<String> tileSheetNames,
                                           java.util.Map<String, Integer> tileOverflowByName) {
        Sheet summary = workbook.getSheet(role.getSummarySheet());
        for (BindingSummaryFormula sf : role.getSummaryFormulas()) {
            CellReference ref = new CellReference(sf.getCell());
            Cell cell = getOrCreateCell(summary, ref);
            if (tileSheetNames.isEmpty()) {
                cell.setBlank();
                cell.setCellValue(0.0);
                continue;
            }
            switch (sf.getRule()) {
                case SUM_OVER_TILES -> cell.setCellFormula(
                        buildSumOverTiles(tileSheetNames, sf.getTileCell(), tileOverflowByName));
            }
        }
    }

    private String buildSumOverTiles(List<String> tileSheetNames, String tileCell,
                                     java.util.Map<String, Integer> tileOverflowByName) {
        CellReference base = new CellReference(tileCell);
        String colLetters = CellReference.convertNumToColString(base.getCol());
        int baseRow1 = base.getRow() + 1;
        StringBuilder sb = new StringBuilder("SUM(");
        for (int i = 0; i < tileSheetNames.size(); i++) {
            if (i > 0) sb.append(',');
            String name = tileSheetNames.get(i);
            int adjustedRow1 = baseRow1 + tileOverflowByName.getOrDefault(name, 0);
            sb.append('\'').append(name.replace("'", "''")).append("'!").append(colLetters).append(adjustedRow1);
        }
        sb.append(')');
        return sb.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private String expandTitle(String template, Map<String, Object> header) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = header.get(key);
            m.appendReplacement(result, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(result);
        return result.toString();
    }

    private String expandSheetName(String template, int oneBasedIndex) {
        String name = template
                .replace("{index:02d}", String.format("%02d", oneBasedIndex))
                .replace("{index}", String.valueOf(oneBasedIndex));
        return name;
    }

    private void writeStringAt(Sheet sheet, CellReference ref, String value) {
        Cell cell = getOrCreateCell(sheet, ref);
        cell.setCellValue(value);
    }

    private Cell getOrCreateCell(Sheet sheet, CellReference ref) {
        Row row = sheet.getRow(ref.getRow());
        if (row == null) {
            row = sheet.createRow(ref.getRow());
        }
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) {
            cell = row.createCell(ref.getCol());
        }
        return cell;
    }

    private void renderStackedBlocks(Workbook workbook, BindingRole role, List<Map<String, Object>> rows) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        String groupingKey = role.getGroupingKey();
        int cumulativeShift = 0;

        for (BindingBlock block : role.getBlocks()) {
            List<Map<String, Object>> matches = rows.stream()
                    .filter(r -> block.getActivityName().equals(r.get(groupingKey)))
                    .toList();
            if (matches.isEmpty()) continue;

            int effectiveFirstDataRow1 = block.getFirstDataRow() + cumulativeShift;
            int effectiveTotalRow1 = block.getTotalRow() + cumulativeShift;

            int extra = matches.size() - 1;
            if (extra > 0) {
                expandStackedBlock(sheet, role, effectiveFirstDataRow1, effectiveTotalRow1, extra);
                cumulativeShift += extra;
            }

            for (int i = 0; i < matches.size(); i++) {
                int rowIndex0 = effectiveFirstDataRow1 - 1 + i;
                Row row = sheet.getRow(rowIndex0);
                if (row == null) row = sheet.createRow(rowIndex0);
                writeColumns(row, role.getBlockColumns(), matches.get(i));
            }
        }
    }

    /**
     * Open {@code extra} extra rows between the block's data row and its TOTAL row, then rewrite
     * the TOTAL row's {@code =SUM(K{r}:K{r})} formula to cover the full expanded range. We can't
     * lean on POI's FormulaShifter for the SUM extension here because the original range is a
     * single cell — FormulaShifter doesn't grow a one-cell range into many; it only follows whole-
     * range moves. Cross-sheet refs from the summary sheet (e.g. {@code 'D-Perspectiva D'!K10 =
     * 'Indicatorul I'!K11}) DO follow the shift since the TOTAL cell itself moves down.
     */
    private void expandStackedBlock(Sheet sheet, BindingRole role,
                                    int firstDataRow1, int totalRow1, int extra) {
        int totalRow0 = totalRow1 - 1;
        int lastRowOnSheet = sheet.getLastRowNum();
        if (totalRow0 <= lastRowOnSheet) {
            sheet.shiftRows(totalRow0, lastRowOnSheet, extra);
        }
        // Template data row stayed at firstDataRow0 (untouched). Clone its styles + formulas into
        // the new gap rows so user-typed data falls into the same formatting as the templated row.
        int firstDataRow0 = firstDataRow1 - 1;
        Row templateRow = sheet.getRow(firstDataRow0);
        if (templateRow instanceof XSSFRow xssfTemplate) {
            CellCopyPolicy policy = new CellCopyPolicy();
            for (int offset = 1; offset <= extra; offset++) {
                int targetIdx0 = firstDataRow0 + offset;
                Row existing = sheet.getRow(targetIdx0);
                if (existing != null) sheet.removeRow(existing);
                XSSFRow newRow = (XSSFRow) sheet.createRow(targetIdx0);
                newRow.copyRowFrom(xssfTemplate, policy);
            }
        } else {
            LOG.warn("Stacked block expansion: sheet is not XSSF, skipping per-row template clone");
        }
        // Rewrite the SUM formula on the moved TOTAL row to span the expanded data range.
        int newTotalRow1 = totalRow1 + extra;
        Row newTotalRow = sheet.getRow(newTotalRow1 - 1);
        if (newTotalRow == null) return;
        for (Map.Entry<String, BindingColumn> entry : role.getTotalRowColumns().entrySet()) {
            if (entry.getValue().getPolicy() != BindingPolicy.SKIP_FORMULA) continue;
            int colIdx = CellReference.convertColStringToIndex(entry.getKey());
            Cell totalCell = newTotalRow.getCell(colIdx);
            if (totalCell == null) continue;
            totalCell.setCellFormula("SUM(" + entry.getKey() + firstDataRow1
                    + ":" + entry.getKey() + (firstDataRow1 + extra) + ")");
        }
    }

    private void writeColumns(Row row, Map<String, BindingColumn> columns, Map<String, Object> rowMap) {
        for (Map.Entry<String, BindingColumn> e : columns.entrySet()) {
            BindingColumn col = e.getValue();
            if (col.getPolicy() != BindingPolicy.WRITE && col.getPolicy() != BindingPolicy.WRITE_SCORE) {
                continue;
            }
            int colIndex = CellReference.convertColStringToIndex(e.getKey());
            Cell cell = row.getCell(colIndex);
            if (cell == null) {
                cell = row.createCell(colIndex);
            }
            writeCellValue(cell, rowMap.get(col.getSource()));
        }
    }

    private void renderFixedTable(Workbook workbook, BindingRole role, List<Map<String, Object>> rows) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        int maxRows = role.getMaxRows();
        int firstDataRow = role.getFirstDataRow();
        BindingOverflowPolicy policy = role.getOverflowPolicy() != null
                ? role.getOverflowPolicy() : BindingOverflowPolicy.EXPAND;

        int writeCount = rows.size();
        if (rows.size() > maxRows) {
            switch (policy) {
                case ERROR -> throw new IllegalStateException("Role '" + role.getRoleKey()
                        + "' overflowed: " + rows.size() + " rows but maxRows=" + maxRows);
                case WARN_AND_TRUNCATE -> {
                    LOG.warn("Role '{}' overflowed: {} rows but maxRows={}; truncating",
                            role.getRoleKey(), rows.size(), maxRows);
                    writeCount = maxRows;
                }
                case EXPAND -> expandFixedTable(sheet, role, rows.size() - maxRows);
            }
        }

        for (int i = 0; i < writeCount; i++) {
            int rowIndex0 = firstDataRow - 1 + i;
            Row row = sheet.getRow(rowIndex0);
            if (row == null) {
                row = sheet.createRow(rowIndex0);
            }
            writeColumns(row, role.getColumns(), rows.get(i));
        }

        // Clear the writable cells of any unused template rows so the export doesn't carry
        // placeholder text ("Titlu articol", "(niciunul)", ...) and so the score parser stops at
        // the first blank key cell. Only relevant when we wrote fewer than maxRows.
        for (int i = writeCount; i < maxRows; i++) {
            int rowIndex0 = firstDataRow - 1 + i;
            Row row = sheet.getRow(rowIndex0);
            if (row == null) continue;
            for (Map.Entry<String, BindingColumn> e : role.getColumns().entrySet()) {
                BindingColumn col = e.getValue();
                if (col.getPolicy() != BindingPolicy.WRITE && col.getPolicy() != BindingPolicy.WRITE_SCORE) continue;
                Cell cell = row.getCell(CellReference.convertColStringToIndex(e.getKey()));
                if (cell != null) cell.setBlank();
            }
        }
    }

    /**
     * Open {@code extraRows} extra slots inside the FIXED_TABLE region by shifting from the
     * original last data row downward, then cloning that row's template (styles + formulas)
     * into the new gap. Shifting from the LAST in-range row makes POI's FormulaShifter extend
     * aggregation ranges that ended at that row (e.g. {@code SUMIF(H8:H17,...)} → {@code H8:H(17+N)})
     * and update cross-sheet references that point at moved cells.
     */
    private void expandFixedTable(Sheet sheet, BindingRole role, int extraRows) {
        int firstDataRow = role.getFirstDataRow();
        int maxRows = role.getMaxRows();
        int lastTemplatedIdx0 = firstDataRow - 1 + maxRows - 1;
        int lastRowOnSheet = sheet.getLastRowNum();

        if (lastTemplatedIdx0 > lastRowOnSheet) {
            LOG.warn("Role '{}': last templated row {} is past sheet end {}; skipping expand",
                    role.getRoleKey(), lastTemplatedIdx0, lastRowOnSheet);
            return;
        }

        sheet.shiftRows(lastTemplatedIdx0, lastRowOnSheet, extraRows);
        Row movedTemplate = sheet.getRow(lastTemplatedIdx0 + extraRows);
        if (!(movedTemplate instanceof XSSFRow xssfMoved)) {
            LOG.warn("Role '{}': sheet is not XSSF, skipping per-row template clone", role.getRoleKey());
            return;
        }
        CellCopyPolicy policy = new CellCopyPolicy();
        for (int offset = 0; offset < extraRows; offset++) {
            int targetIdx = lastTemplatedIdx0 + offset;
            Row existing = sheet.getRow(targetIdx);
            if (existing != null) {
                sheet.removeRow(existing);
            }
            XSSFRow newRow = (XSSFRow) sheet.createRow(targetIdx);
            newRow.copyRowFrom(xssfMoved, policy);
        }
    }

    private void writeCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private InputStream openTemplate(String path) throws IOException {
        String stripped = path.startsWith(CLASSPATH_PREFIX) ? path.substring(CLASSPATH_PREFIX.length()) : path;
        return new ClassPathResource(stripped).getInputStream();
    }
}
