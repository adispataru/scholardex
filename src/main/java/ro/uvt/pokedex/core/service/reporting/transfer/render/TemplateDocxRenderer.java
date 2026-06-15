package ro.uvt.pokedex.core.service.reporting.transfer.render;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingBlock;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingDocxTotal;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Renders a no-formula DOCX template: fills Word tables from per-role row data and writes computed
 * totals into label-bearing cells (the template carries no formulas, so every final value is placed
 * here). Addressing is by table index + 0-based cell index (see {@link BindingRole} DOCX fields);
 * totals are located by marker text and written after the trailing "=", preserving surrounding
 * layout (e.g. a combined "S =  …  Srecent =" cell).
 */
@Component
public class TemplateDocxRenderer {

    private static final String CLASSPATH_PREFIX = "classpath:";

    public byte[] render(TemplateBinding binding,
                         Map<String, List<Map<String, Object>>> rowsByRole,
                         Map<String, Double> totals) {
        try (InputStream in = openTemplate(binding.getTemplateResource());
             XWPFDocument doc = new XWPFDocument(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (BindingRole role : binding.getRoles()) {
                List<Map<String, Object>> rows = rowsByRole.getOrDefault(role.getRoleKey(), List.of());
                if (role.getKind() == BindingKind.STACKED_BLOCKS && role.getTableIndex() != null) {
                    fillStackedBlocks(doc, role, rows);
                } else if (role.getTableIndex() != null && !role.getColumns().isEmpty()) {
                    fillTable(doc, role, rows);
                }
                writeTotals(doc, role, totals);
            }

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render DOCX template " + binding.getTemplateResource(), e);
        }
    }

    private void fillTable(XWPFDocument doc, BindingRole role, List<Map<String, Object>> rows) {
        List<XWPFTable> tables = doc.getTables();
        if (role.getTableIndex() < 0 || role.getTableIndex() >= tables.size()) return;
        XWPFTable table = tables.get(role.getTableIndex());

        int firstDataRow = role.getFirstDataRow() != null ? role.getFirstDataRow() : 1;
        int templateRows = role.getMaxRows() != null ? role.getMaxRows() : 1;
        int lastTemplateDataRow = firstDataRow + templateRows - 1;
        // Pristine copy of a template data row, captured BEFORE any filling, so cloned rows never
        // inherit a previously-written row's values.
        CTRow pristine = lastTemplateDataRow < table.getNumberOfRows()
                ? (CTRow) table.getRow(lastTemplateDataRow).getCtRow().copy() : null;

        for (int i = 0; i < rows.size(); i++) {
            int rowIndex = firstDataRow + i;
            XWPFTableRow row;
            if (i < templateRows && rowIndex < table.getNumberOfRows()) {
                row = table.getRow(rowIndex);
            } else if (pristine != null) {
                // Insert a cloned (pristine) row *before* the total/notes rows. insertNewTableRow
                // gives a row backed by a LIVE CTRow in the table; copy the template XML into it,
                // then wrap that live CTRow so cell edits actually land in the document. (addRow's
                // copy semantics leave the passed/re-fetched wrapper detached — edits are lost.)
                XWPFTableRow placeholder = table.insertNewTableRow(rowIndex);
                placeholder.getCtRow().set(pristine.copy());
                row = new XWPFTableRow(placeholder.getCtRow(), table);
            } else {
                continue;
            }

            // Clear every cell so neither template placeholders ("1.", "…") nor a clone's source
            // values survive, then write the ordinal + the bound columns.
            for (XWPFTableCell cell : row.getTableCells()) {
                setCellText(cell, "");
            }
            if (!row.getTableCells().isEmpty()) {
                setCellText(row.getCell(0), String.valueOf(i + 1));
            }
            Map<String, Object> rowData = rows.get(i);
            for (Map.Entry<String, BindingColumn> col : role.getColumns().entrySet()) {
                BindingColumn binding = col.getValue();
                if (binding.getPolicy() == BindingPolicy.MANUAL) continue;
                int cellIdx = parseIndex(col.getKey());
                if (cellIdx < 0 || cellIdx >= row.getTableCells().size()) continue;
                setCellText(row.getCell(cellIdx), format(rowData.get(binding.getSource())));
            }
        }
    }

    /**
     * Fills a STACKED_BLOCKS role in a DOCX table: each block (e.g. {@code C1_UVT}) owns a run of
     * placeholder item rows between its header row and its "Total punctaj … =" row. We discover that
     * region by marker (the total cell) and walk up to the block header — no per-block row indices in
     * the binding, so the fill survives template edits. Each item's value is written into the bound
     * content cell, replacing the template placeholder ("Carte 1 : Autori, titlu, …"); extra items
     * clone the first data row; unused placeholder rows are blanked.
     */
    private void fillStackedBlocks(XWPFDocument doc, BindingRole role, List<Map<String, Object>> rows) {
        List<XWPFTable> tables = doc.getTables();
        if (role.getTableIndex() < 0 || role.getTableIndex() >= tables.size()) return;
        XWPFTable table = tables.get(role.getTableIndex());
        String groupingKey = role.getGroupingKey();
        if (groupingKey == null || role.getBlockColumns() == null || role.getBlockColumns().isEmpty()) return;

        for (BindingBlock block : role.getBlocks()) {
            String blockName = block.getActivityName();
            List<Map<String, Object>> items = rows.stream()
                    .filter(r -> blockName != null && blockName.equals(String.valueOf(r.get(groupingKey))))
                    .toList();

            // Locate the block's total row by its marker, then the data rows directly above it.
            int totalIdx = findRowIndexContaining(table, "Total punctaj " + blockName);
            if (totalIdx < 0) continue;
            List<Integer> dataIdxs = new java.util.ArrayList<>();
            for (int r = totalIdx - 1; r >= 0; r--) {
                XWPFTableRow row = table.getRow(r);
                if (row == null || row.getTableCells().size() < 2) break; // hit the merged block header
                if (normalize(rowText(row)).contains("Total punctaj")) break; // a previous block's total
                dataIdxs.add(0, r);
            }
            if (dataIdxs.isEmpty()) continue;

            CTRow pristine = (CTRow) table.getRow(dataIdxs.get(0)).getCtRow().copy();
            int lastDataIdx = dataIdxs.get(dataIdxs.size() - 1);

            for (int i = 0; i < items.size(); i++) {
                XWPFTableRow row;
                boolean clearAll;
                if (i < dataIdxs.size()) {
                    row = table.getRow(dataIdxs.get(i)); // overwrite the template placeholder in place
                    clearAll = false;
                } else {
                    // Insert a cloned data row just before the (shifting) total row. Re-wrap the LIVE
                    // CTRow so cell edits land — same POI constraint as fillTable.
                    int pos = lastDataIdx + 1 + (i - dataIdxs.size());
                    XWPFTableRow placeholder = table.insertNewTableRow(pos);
                    placeholder.getCtRow().set(pristine.copy());
                    row = new XWPFTableRow(placeholder.getCtRow(), table);
                    clearAll = true;
                }
                writeBlockRow(row, role.getBlockColumns(), items.get(i), clearAll);
            }
            // Blank any leftover placeholder rows so "Carte 1 : Autori, …" / "……" don't survive.
            for (int i = items.size(); i < dataIdxs.size(); i++) {
                writeBlockRow(table.getRow(dataIdxs.get(i)), role.getBlockColumns(), Map.of(), false);
            }
        }
    }

    private void writeBlockRow(XWPFTableRow row, Map<String, BindingColumn> columns,
                               Map<String, Object> rowData, boolean clearAll) {
        if (row == null) return;
        if (clearAll) {
            for (XWPFTableCell cell : row.getTableCells()) setCellText(cell, "");
        }
        for (Map.Entry<String, BindingColumn> col : columns.entrySet()) {
            BindingColumn binding = col.getValue();
            if (binding.getPolicy() == BindingPolicy.MANUAL) continue;
            int cellIdx = parseIndex(col.getKey());
            if (cellIdx < 0 || cellIdx >= row.getTableCells().size()) continue;
            setCellText(row.getCell(cellIdx), format(rowData.get(binding.getSource())));
        }
    }

    /** Index of the first row whose whitespace-normalized text contains the needle, else -1. */
    private int findRowIndexContaining(XWPFTable table, String needle) {
        String n = normalize(needle);
        for (int r = 0; r < table.getNumberOfRows(); r++) {
            if (normalize(rowText(table.getRow(r))).contains(n)) return r;
        }
        return -1;
    }

    private static String rowText(XWPFTableRow row) {
        if (row == null) return "";
        StringBuilder sb = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cell.getText());
        }
        return sb.toString();
    }

    private void writeTotals(XWPFDocument doc, BindingRole role, Map<String, Double> totals) {
        for (BindingDocxTotal total : role.getDocxTotals()) {
            Double value = totals.get(total.getTotalKey());
            if (value == null) continue;
            XWPFParagraph target = findParagraph(doc, total.getMarker());
            if (target != null) {
                writeAfterEquals(target, total.getEqualsIndex(), format(value));
            }
        }
    }

    /** First paragraph (table cell or body) whose whitespace-normalized text contains the marker. */
    private XWPFParagraph findParagraph(XWPFDocument doc, String marker) {
        String needle = normalize(marker);
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        if (normalize(p.getText()).contains(needle)) return p;
                    }
                }
            }
        }
        for (XWPFParagraph p : doc.getParagraphs()) {
            if (normalize(p.getText()).contains(needle)) return p;
        }
        return null;
    }

    /** Append the value just after the {@code equalsIndex}-th '=' in the paragraph, run-preservingly. */
    private void writeAfterEquals(XWPFParagraph paragraph, int equalsIndex, String value) {
        int seen = -1;
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.getText(0);
            if (text == null) continue;
            int from = 0, idx;
            while ((idx = text.indexOf('=', from)) >= 0) {
                seen++;
                if (seen == equalsIndex) {
                    String updated = text.substring(0, idx + 1) + " " + value + text.substring(idx + 1);
                    run.setText(updated, 0);
                    return;
                }
                from = idx + 1;
            }
        }
    }

    private void setCellText(XWPFTableCell cell, String text) {
        for (XWPFParagraph p : cell.getParagraphs()) {
            for (int r = p.getRuns().size() - 1; r >= 0; r--) {
                p.removeRun(r);
            }
        }
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.createRun().setText(text == null ? "" : text);
    }

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String format(Object value) {
        if (value == null) return "";
        if (value instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite()) return String.valueOf(d.longValue());
            return String.format(java.util.Locale.ROOT, "%.2f", d);
        }
        return String.valueOf(value);
    }

    private InputStream openTemplate(String path) throws IOException {
        String stripped = path.startsWith(CLASSPATH_PREFIX) ? path.substring(CLASSPATH_PREFIX.length()) : path;
        return new ClassPathResource(stripped).getInputStream();
    }
}
