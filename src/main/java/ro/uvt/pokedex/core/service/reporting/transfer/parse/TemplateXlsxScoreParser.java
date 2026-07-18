package ro.uvt.pokedex.core.service.reporting.transfer.parse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses an uploaded xlsx into {@link SnapshotItem}s carrying the per-item score, evaluating
 * formulas so the Punctaj columns are recomputed from whatever category / author-count the user
 * left in the file. Used by the import score-verification flow — never writes anything.
 *
 * Only FIXED_TABLE roles that declare {@code keyColumn} + {@code scoreColumn} are parsed (currently
 * the two publication sheets). Reading stops at the first row with a blank key cell, which is the
 * separator the template keeps between the data rows and the aggregation block.
 */
@Component
public class TemplateXlsxScoreParser {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateXlsxScoreParser.class);

    public List<SnapshotItem> parse(TemplateBinding binding, InputStream input) {
        return parse(binding, input, new ArrayList<>());
    }

    /**
     * Parse the uploaded workbook against the binding. Researchers sometimes restructure the official
     * template (columns shifted whole letters, tables moved) — those sheets cannot be compared
     * honestly, so instead of guessing we append a human-readable note per deviated sheet to
     * {@code layoutWarnings}: the user is responsible for filling the official template.
     */
    public List<SnapshotItem> parse(TemplateBinding binding, InputStream input, List<String> layoutWarnings) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Set<String> ignored = new HashSet<>();
            if (binding.getIgnoredKeyValues() != null) {
                binding.getIgnoredKeyValues().forEach(v -> ignored.add(v.trim().toLowerCase()));
            }
            List<SnapshotItem> out = new ArrayList<>();
            for (BindingRole role : binding.getRoles()) {
                if (role.getKind() == BindingKind.FIXED_TABLE
                        && !isBlank(role.getKeyColumn()) && !isBlank(role.getScoreColumn())) {
                    List<SnapshotItem> items = parseFixedTable(workbook, evaluator, role, ignored);
                    if (items.isEmpty()) detectFixedTableShift(workbook, role, layoutWarnings);
                    out.addAll(items);
                } else if (role.getKind() == BindingKind.TILED_SHEETS
                        && !isBlank(role.getInnerScoreColumn())) {
                    out.addAll(parseTiledSheets(workbook, evaluator, role, ignored, layoutWarnings));
                } else if (role.getKind() == BindingKind.STACKED_BLOCKS) {
                    List<SnapshotItem> items = parseStackedBlocks(workbook, evaluator, role, ignored);
                    if (items.isEmpty()) detectStackedBlocksShift(workbook, role, layoutWarnings);
                    out.addAll(items);
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded workbook", e);
        }
    }

    /**
     * A FIXED_TABLE role that parsed nothing: check whether the sheet's "Titlu" header sits in a
     * different column than the binding's key column — the researcher restructured the template.
     */
    private void detectFixedTableShift(Workbook workbook, BindingRole role, List<String> warnings) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        if (sheet == null) return;
        int expectedCol = CellReference.convertColStringToIndex(role.getKeyColumn());
        for (int r = 0; r <= Math.min(9, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String v = normalizeLoose(stringValue(cell));
                if ("titlu".equals(v) && cell.getColumnIndex() != expectedCol) {
                    warnings.add(columnShiftWarning(role.getSheet(), "'Titlu' column",
                            cell.getColumnIndex(), expectedCol));
                    return;
                }
            }
        }
    }

    /**
     * A STACKED_BLOCKS role that parsed nothing: check whether the marker headers live in a
     * different column than the binding's description column.
     */
    private void detectStackedBlocksShift(Workbook workbook, BindingRole role, List<String> warnings) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        if (sheet == null) return;
        String marker = normalizeLoose(role.getBlockHeaderMarker());
        if (marker == null || marker.isBlank()) return;
        int expectedCol = columnFor(role.getBlockColumns(), "activity.description");
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String v = normalizeLoose(stringValue(cell));
                if (v != null && v.contains(marker) && cell.getColumnIndex() != expectedCol) {
                    warnings.add(columnShiftWarning(role.getSheet(), "section headers",
                            cell.getColumnIndex(), expectedCol));
                    return;
                }
            }
        }
    }

    private String columnShiftWarning(String sheetName, String what, int foundCol, int expectedCol) {
        return "Sheet '" + sheetName + "': the layout differs from the official template (found " + what
                + " in column " + CellReference.convertNumToColString(foundCol)
                + ", expected " + CellReference.convertNumToColString(expectedCol)
                + "). Its rows were NOT compared — please fill in the official template.";
    }

    private boolean isIgnored(String title, Set<String> ignored) {
        return title != null && ignored.contains(title.trim().toLowerCase());
    }

    private List<SnapshotItem> parseStackedBlocks(Workbook workbook, FormulaEvaluator evaluator,
                                                  BindingRole role, Set<String> ignored) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        if (sheet == null) return List.of();
        int descCol = columnFor(role.getBlockColumns(), "activity.description");
        int categoryCol = columnFor(role.getBlockColumns(), "activity.category");
        int scoreCol = columnFor(role.getBlockColumns(), "activity.score");
        if (descCol < 0 || scoreCol < 0) return List.of();

        // Header detection: a header row shares a common marker ("Justificări pentru indicatorul")
        // and names one block. We assign it to the block whose name tokens are best CONTAINED in the
        // header text — tolerant of extra words (e.g. "CURS UNIVERSITAR IN FORMAT ELECTRONIC" still
        // resolves to "Curs in format electronic"). Falls back to a substring test when no marker set.
        String marker = normalizeLoose(role.getBlockHeaderMarker());
        List<Object[]> headers = new ArrayList<>(); // [rowIndex, blockName]
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String raw = stringValue(row.getCell(descCol));
            String c = normalizeLoose(raw);
            if (c == null || c.isBlank()) continue;
            boolean isHeader = marker != null && !marker.isBlank() ? c.contains(marker) : false;

            int bestBlock = -1;
            double bestScore = 0.6; // require most of the block name's tokens present
            for (int b = 0; b < role.getBlocks().size(); b++) {
                String name = normalizeLoose(role.getBlocks().get(b).getActivityName());
                if (name == null || name.isBlank()) continue;
                double score = isHeader ? tokenContainment(c, name)
                        : (c.contains(name) ? 1.0 : 0.0); // legacy fallback
                if (score >= bestScore) { bestScore = score; bestBlock = b; }
            }
            if (bestBlock >= 0) {
                headers.add(new Object[]{r, role.getBlocks().get(bestBlock).getActivityName()});
            } else if (isHeader) {
                // Researchers add custom sections the official template doesn't have (e.g.
                // "Profesor/cercetător asociat/visiting"). A marker row that matches no configured
                // block still STARTS ITS OWN block, named from the header text — otherwise its data
                // rows would silently attach to the previous section, mislabeling them.
                headers.add(new Object[]{r, customBlockName(raw)});
            }
        }

        List<SnapshotItem> out = new ArrayList<>();
        for (int h = 0; h < headers.size(); h++) {
            int headerRow = (Integer) headers.get(h)[0];
            String blockName = (String) headers.get(h)[1];
            int nextHeaderRow = (h + 1 < headers.size()) ? (Integer) headers.get(h + 1)[0] : sheet.getLastRowNum() + 1;

            for (int r = headerRow + 1; r < nextHeaderRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String desc = stringValue(row.getCell(descCol));
                if (desc == null || desc.isBlank()) continue;
                String descNorm = desc.trim().toLowerCase();
                // Stop at the block's TOTAL row; skip the column-header row and aggregation labels.
                if (descNorm.equals("total") || descNorm.startsWith("explica")) {
                    if (descNorm.equals("total")) break;
                    continue;
                }
                double score = numericValue(row.getCell(scoreCol), evaluator);
                if (isIgnored(desc, ignored) || score == 0.0) continue;

                ActivitySnapshotItem item = new ActivitySnapshotItem();
                item.setRoleKey(role.getRoleKey());
                item.setActivityName(blockName);
                item.setItemKey(blockName + ":" + desc);
                item.setDescription(desc);
                if (categoryCol >= 0) item.setCategory(stringValue(row.getCell(categoryCol)));
                item.setScore(score);
                out.add(item);
            }
        }
        return out;
    }

    private int columnFor(Map<String, ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn> cols, String source) {
        if (cols == null) return -1;
        for (Map.Entry<String, ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingColumn> e : cols.entrySet()) {
            if (source.equals(e.getValue().getSource())) {
                return CellReference.convertColStringToIndex(e.getKey());
            }
        }
        return -1;
    }

    /**
     * Display name for a custom (unconfigured) section, from its raw header text: the part after
     * "indicatorul", minus a trailing "(perspectiva …)" suffix — e.g. "C1. Justificări pentru
     * indicatorul Profesor/cercetător asociat/visiting (perspectiva D)" → "Profesor/cercetător
     * asociat/visiting". Falls back to the trimmed header when the pattern is absent.
     */
    private String customBlockName(String rawHeader) {
        String s = rawHeader == null ? "" : rawHeader.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("indicatorul\\s+(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(s);
        String name = m.find() ? m.group(1) : s;
        name = name.replaceAll("\\(\\s*perspectiv[^)]*\\)\\s*$", "").trim();
        return name.isBlank() ? s : name;
    }

    /** Fraction of {@code blockNameNorm}'s tokens (≥3 chars) present in {@code headerNorm}. */
    private double tokenContainment(String headerNorm, String blockNameNorm) {
        Set<String> headerTokens = new HashSet<>();
        for (String t : headerNorm.split(" ")) if (t.length() >= 3) headerTokens.add(t);
        int total = 0, present = 0;
        for (String t : blockNameNorm.split(" ")) {
            if (t.length() < 3) continue;
            total++;
            if (headerTokens.contains(t)) present++;
        }
        return total == 0 ? 0.0 : (double) present / total;
    }

    private String normalizeLoose(String s) {
        if (s == null) return null;
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The "Total categoria …" / "TOTAL" rows that mark the end of a data block and the start of
     *  the aggregation section. Used as the stop boundary so blank gaps inside the data don't
     *  truncate parsing. */
    private boolean isAggregationLabel(String title) {
        return title != null && title.trim().toLowerCase().startsWith("total");
    }

    private List<SnapshotItem> parseFixedTable(Workbook workbook, FormulaEvaluator evaluator, BindingRole role,
                                               Set<String> ignored) {
        Sheet sheet = workbook.getSheet(role.getSheet());
        if (sheet == null) {
            LOG.warn("Role '{}' references missing sheet '{}' in uploaded file; skipping",
                    role.getRoleKey(), role.getSheet());
            return List.of();
        }
        int keyCol = CellReference.convertColStringToIndex(role.getKeyColumn());
        int scoreCol = CellReference.convertColStringToIndex(role.getScoreColumn());
        int categoryCol = isBlank(role.getCategoryColumn()) ? -1
                : CellReference.convertColStringToIndex(role.getCategoryColumn());
        int authorCountCol = isBlank(role.getAuthorCountColumn()) ? -1
                : CellReference.convertColStringToIndex(role.getAuthorCountColumn());
        int firstDataRow0 = role.getFirstDataRow() - 1;

        List<SnapshotItem> out = new ArrayList<>();
        for (int r = firstDataRow0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String title = row != null ? stringValue(row.getCell(keyCol)) : null;
            if (title == null || title.isBlank()) continue;     // skip blank/gap rows, keep scanning
            if (isAggregationLabel(title)) break;               // reached the "Total ..." block → done
            double score = numericValue(row.getCell(scoreCol), evaluator);
            // Skip unfilled template rows: placeholder titles ("Titlu articol") or 0 points.
            if (isIgnored(title, ignored) || score == 0.0) continue;

            PublicationSnapshotItem item = new PublicationSnapshotItem();
            item.setRoleKey(role.getRoleKey());
            item.setItemKey(title);
            item.setTitle(title);
            item.setScore(score);
            if (categoryCol >= 0) item.setForumCategoryLetter(stringValue(row.getCell(categoryCol)));
            if (authorCountCol >= 0) {
                double ac = numericValue(row.getCell(authorCountCol), evaluator);
                if (ac > 0) item.setAuthorCount((int) Math.round(ac));
            }
            out.add(item);
        }
        return out;
    }

    private List<SnapshotItem> parseTiledSheets(Workbook workbook, FormulaEvaluator evaluator, BindingRole role,
                                                Set<String> ignored, List<String> layoutWarnings) {
        // Two accepted namings: our export's "Citari-NN" (sheetNameTemplate) AND the official
        // template's own tile-sheet name as a prefix ("C-Citari-TPL", "C-Citari-TPL1", …) — real
        // researcher-filled FVs keep the official names, and their citations were silently
        // unparsed before this. The summary sheet ("C-Citari-centralizare") shares no prefix.
        String sheetNamePrefix = sheetNamePrefix(role.getSheetNameTemplate());
        String officialPrefix = role.getTemplateSheet();
        if (sheetNamePrefix == null && (officialPrefix == null || officialPrefix.isBlank())) return List.of();
        CellReference titleRef = new CellReference(role.getPerTileTitleCell());
        int keyCol = titleRef.getCol();
        int titleRow0 = titleRef.getRow();
        int scoreCol = CellReference.convertColStringToIndex(role.getInnerScoreColumn());
        int firstDataRow0 = role.getInnerTableFirstDataRow() - 1;

        List<SnapshotItem> out = new ArrayList<>();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            String name = sheet.getSheetName();
            boolean exportNamed = sheetNamePrefix != null && name.startsWith(sheetNamePrefix);
            boolean officialNamed = officialPrefix != null && !officialPrefix.isBlank()
                    && name.startsWith(officialPrefix);
            if (!exportNamed && !officialNamed) continue;

            // Real files come in two shapes: one tile per sheet (our exports, per-publication sheet
            // copies) and MANY tiles stacked vertically in a single sheet — sometimes with the first
            // title shifted off the configured cell. Locate every title row by the title template's
            // literal prefix ("B2. CITĂRI PENTRU LUCRAREA:") and parse each tile segment; fall back
            // to the configured cell when the template has no literal prefix.
            String titlePrefix = titleTemplatePrefix(role.getPerTileTitleTemplate());
            List<Integer> titleRows = new ArrayList<>();
            if (titlePrefix != null) {
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    String v = row != null ? stringValue(row.getCell(keyCol)) : null;
                    if (v != null && v.trim().startsWith(titlePrefix)) titleRows.add(r);
                }
                if (titleRows.isEmpty() && detectTiledTitleShift(sheet, titlePrefix, keyCol, layoutWarnings)) {
                    continue; // restructured layout — columns unknown, comparing would produce garbage
                }
            }
            if (titleRows.isEmpty()) titleRows.add(titleRow0);
            int dataOffset = firstDataRow0 - titleRow0; // template geometry: rows from title to first data row

            for (int t = 0; t < titleRows.size(); t++) {
                int titleAt = titleRows.get(t);
                int segmentEnd = (t + 1 < titleRows.size()) ? titleRows.get(t + 1) - 1 : sheet.getLastRowNum();
                Row titleRow = sheet.getRow(titleAt);
                String rawTitle = titleRow != null ? stringValue(titleRow.getCell(keyCol)) : null;
                String title = extractTitle(rawTitle, role.getPerTileTitleTemplate());
                if (title == null || title.isBlank()) continue;

                double rawSum = 0.0;
                List<CitationSnapshotItem.CitingPublication> citingRows = new ArrayList<>();
                int r = titleAt + dataOffset;
                for (; r <= segmentEnd; r++) {
                    Row row = sheet.getRow(r);
                    String innerTitle = row != null ? stringValue(row.getCell(keyCol)) : null;
                    if (innerTitle == null || innerTitle.isBlank()) continue;   // skip blank/gap rows
                    if (isAggregationLabel(innerTitle)) break;                  // "Total ..." block → done
                    double rowScore = numericValue(row.getCell(scoreCol), evaluator);
                    // Skip unfilled template rows: placeholder titles ("Titlu articol care citeaza") or 0 points.
                    if (isIgnored(innerTitle, ignored) || rowScore == 0.0) continue;
                    rawSum += rowScore;
                    CitationSnapshotItem.CitingPublication cp = new CitationSnapshotItem.CitingPublication();
                    cp.setTitle(innerTitle);
                    cp.setScore(rowScore); // raw per-citation points for the breakdown
                    citingRows.add(cp);
                }

                // The tile's authoritative score is the grand-total row (author-divided), not the raw
                // sum. Bounded to this tile's segment — a stacked sheet where THIS tile has no TOTAL
                // must not pick up the next tile's.
                Double total = findTileTotal(sheet, evaluator, keyCol, scoreCol, role.getTileTotalLabel(), r, segmentEnd);
                double tileScore = total != null ? total : rawSum;

                // An untouched master tile (the template ships C-Citari-TPL with a sample title and
                // placeholder rows) parses to a zero tile with no citing rows — skip it, don't report
                // a phantom publication.
                if (citingRows.isEmpty() && tileScore == 0.0) continue;

                out.add(buildCitationTile(role, title, tileScore, citingRows));
            }
        }
        return out;
    }

    private CitationSnapshotItem buildCitationTile(BindingRole role, String title, double tileScore,
                                                   List<CitationSnapshotItem.CitingPublication> citingRows) {
        CitationSnapshotItem tile = new CitationSnapshotItem();
        tile.setRoleKey(role.getRoleKey());
        tile.setItemKey(title);
        tile.setPublicationTitle(title);
        tile.setScore(tileScore);
        tile.setCitingPublications(citingRows);
        return tile;
    }

    /**
     * Scan {@code fromRow..toRow} for the row whose key cell equals {@code totalLabel} (the tile's
     * grand total, e.g. "TOTAL") and return its evaluated score-column value. Null if no label
     * configured or not found — caller falls back to the raw row sum.
     */
    private Double findTileTotal(Sheet sheet, FormulaEvaluator evaluator, int keyCol, int scoreCol,
                                 String totalLabel, int fromRow, int toRow) {
        if (totalLabel == null || totalLabel.isBlank()) return null;
        for (int r = fromRow; r <= Math.min(toRow, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String label = stringValue(row.getCell(keyCol));
            if (label != null && label.trim().equalsIgnoreCase(totalLabel)) {
                return numericValue(row.getCell(scoreCol), evaluator);
            }
        }
        return null;
    }

    /**
     * A tile sheet where the title prefix is absent from the expected column: look for it anywhere
     * (tolerating a missing "B2. "-style label prefix, seen in restructured files) and warn when the
     * researcher moved the tiles to another column. Returns true when a shift warning was recorded.
     */
    private boolean detectTiledTitleShift(Sheet sheet, String titlePrefix, int expectedCol, List<String> warnings) {
        String needle = normalizeLoose(titlePrefix.replaceFirst("^\\s*B\\d+\\.\\s*", ""));
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                String v = normalizeLoose(stringValue(cell));
                if (v != null && v.contains(needle) && cell.getColumnIndex() != expectedCol) {
                    warnings.add(columnShiftWarning(sheet.getSheetName(), "the citation tile titles",
                            cell.getColumnIndex(), expectedCol));
                    return true;
                }
            }
        }
        return false;
    }

    /** "Prefix: {publication.title} (…)" → "Prefix:". Null when the template has no literal prefix. */
    private String titleTemplatePrefix(String template) {
        if (template == null) return null;
        int brace = template.indexOf('{');
        String prefix = (brace >= 0 ? template.substring(0, brace) : template).trim();
        return prefix.isEmpty() ? null : prefix;
    }

    /** "Citari-{index:02d}" → "Citari-". Null if the template has no literal prefix. */
    private String sheetNamePrefix(String template) {
        if (template == null) return null;
        int brace = template.indexOf('{');
        String prefix = brace >= 0 ? template.substring(0, brace) : template;
        return prefix.isEmpty() ? null : prefix;
    }

    /**
     * Reverse the per-tile title template to recover the publication title. For
     * "Prefix: {publication.title} ({publication.forumName}, {publication.year})" this strips the
     * literal prefix and cuts at the separator that follows the title placeholder (" (").
     */
    private String extractTitle(String cellValue, String template) {
        if (cellValue == null) return null;
        if (template == null) return cellValue.trim();
        int firstOpen = template.indexOf('{');
        if (firstOpen < 0) return cellValue.trim();
        String prefix = template.substring(0, firstOpen);
        int firstClose = template.indexOf('}', firstOpen);
        int nextOpen = template.indexOf('{', firstClose);
        String sep = nextOpen >= 0 ? template.substring(firstClose + 1, nextOpen) : "";

        String v = cellValue;
        if (!prefix.isEmpty() && v.startsWith(prefix)) v = v.substring(prefix.length());
        if (!sep.isEmpty()) {
            int idx = v.lastIndexOf(sep);
            if (idx >= 0) v = v.substring(0, idx);
        }
        return v.trim();
    }

    private String stringValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private double numericValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return 0.0;
        try {
            if (cell.getCellType() == CellType.FORMULA) {
                CellValue v = evaluator.evaluate(cell);
                return v != null && v.getCellType() == CellType.NUMERIC ? v.getNumberValue() : 0.0;
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
        } catch (RuntimeException e) {
            LOG.debug("Failed to evaluate score cell {}: {}", cell.getAddress(), e.toString());
        }
        return 0.0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
