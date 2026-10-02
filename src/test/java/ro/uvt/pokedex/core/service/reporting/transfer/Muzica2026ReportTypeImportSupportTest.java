package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.service.importing.grid.GridWorkbooks;
import ro.uvt.pokedex.core.service.importing.grid.MusicGridLayout;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.parse.TemplateXlsxScoreParser;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H142 slice 2 — FV Muzică 2026 out in the faculty's own grid, and an uploaded grid read back by its row labels. */
class Muzica2026ReportTypeImportSupportTest {

    private Muzica2026ReportTypeImportSupport support;

    @BeforeEach
    void setUp() {
        support = new Muzica2026ReportTypeImportSupport(new TemplateBindingLoader(new ObjectMapper()),
                new TemplateXlsxRenderer(), new TemplateXlsxScoreParser());
        support.loadBinding();
    }

    private static ActivitySnapshotItem item(String role, String block, String description, double score) {
        ActivitySnapshotItem item = new ActivitySnapshotItem();
        item.setRoleKey(role);
        item.setActivityName(block);
        item.setItemKey(block + ":" + description);
        item.setDescription(description);
        item.setScore(score);
        return item;
    }

    private ReportInstanceSnapshot snapshot() {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey(Muzica2026ReportTypeImportSupport.REPORT_TYPE_KEY);
        snap.getItems().add(item("muzica-cs", "CS 1.2", "Concert de Crăciun, Corul FMT-UVT, dirijor (2024)", 10));
        snap.getItems().add(item("muzica-cs", "CS 1.2", "Recital coral,\nsala Capitol, Timișoara (2023)", 10));
        snap.getItems().add(item("muzica-did", "DID 2.1", "CD „Colinde”, Electrecord EDC 1234 (2022)", 30));
        snap.getItems().add(item("muzica-ria", "RIA 3.2", "Președinte, filiala Timișoara a UCMR, 2019–2024", 60));
        snap.getTotals().put("CS 1.2", 20.0);
        snap.getTotals().put("DID 2.1", 30.0);
        snap.getTotals().put("RIA 3.2", 60.0);
        return snap;
    }

    @Test
    void theBindingNamesEveryRowOfTheThreeTablesAsTheImporterKnowsThem() {
        assertEquals(List.of("muzica-did", "muzica-cs", "muzica-ria"), support.declaredRoles());
        Map<String, List<String>> blocks = support.declaredBlocksByRole();
        assertEquals(5, blocks.get("muzica-did").size());
        assertEquals(7, blocks.get("muzica-cs").size());
        assertEquals(15, blocks.get("muzica-ria").size());
        Set<String> rows = Arrays.stream(MusicGridLayout.Row.values()).map(MusicGridLayout.Row::label)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> declared = blocks.values().stream().flatMap(List::stream).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(rows, declared, "one block per row of the standard, named like the grid importer's rows");
    }

    @Test
    void eachRowListsItsItemsInOneCellBesideTheRowsPointsAndTheTablesAreTotalled() throws Exception {
        byte[] bytes = support.render(snapshot(), ReportFormat.XLSX);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            Sheet cs = wb.getSheet("Tabelul 2 - CS");
            Row regional = cs.getRow(4);
            assertTrue(regional.getCell(1).getStringCellValue().startsWith("1.2 Prestații"), "the CS 1.2 row of the template");
            assertEquals("• Concert de Crăciun, Corul FMT-UVT, dirijor (2024)\n• Recital coral, sala Capitol, Timișoara (2023)",
                    regional.getCell(4).getStringCellValue(), "one item per line; a line break inside an item is folded");
            assertEquals(20.0, regional.getCell(5).getNumericCellValue());
            Row top = cs.getRow(3);
            assertTrue(top.getCell(4) == null || top.getCell(4).getStringCellValue().isEmpty(), "a row without items stays empty");
            assertEquals(20.0, evaluator.evaluate(cs.getRow(11).getCell(5)).getNumberValue(), "the CS total sums the rows");

            Sheet did = wb.getSheet("Tabelul 1 - DID");
            assertEquals(30.0, evaluator.evaluate(did.getRow(9).getCell(5)).getNumberValue());
            Sheet ria = wb.getSheet("Tabelul 3 - RIA");
            assertEquals(60.0, ria.getRow(12).getCell(5).getNumericCellValue());
            assertEquals(60.0, evaluator.evaluate(ria.getRow(19).getCell(5)).getNumberValue());
            assertTrue(wb.getSheet("Baze de Date") != null, "the list of databases travels with the grid");
        }
    }

    @Test
    void theRunsTotalWinsOverTheSumOfTheItemsListed() throws Exception {
        ReportInstanceSnapshot snap = snapshot();
        snap.getTotals().put("CS 1.2", 15.0); // e.g. a cap on the indicator

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(support.render(snap, ReportFormat.XLSX)))) {
            assertEquals(15.0, wb.getSheet("Tabelul 2 - CS").getRow(4).getCell(5).getNumericCellValue());
        }
    }

    @Test
    void anExportedGridReadsBackAsTheSameRowsItemsAndPoints() {
        byte[] bytes = support.render(snapshot(), ReportFormat.XLSX);

        List<SnapshotItem> parsed = support.parse(new ByteArrayInputStream(bytes), ReportFormat.XLSX);

        Map<String, List<ActivitySnapshotItem>> byRow = parsed.stream().map(ActivitySnapshotItem.class::cast)
                .collect(Collectors.groupingBy(ActivitySnapshotItem::getActivityName));
        assertEquals(Set.of("CS 1.2", "DID 2.1", "RIA 3.2"), byRow.keySet());
        assertEquals(List.of("Concert de Crăciun, Corul FMT-UVT, dirijor (2024)", "Recital coral, sala Capitol, Timișoara (2023)"),
                byRow.get("CS 1.2").stream().map(ActivitySnapshotItem::getDescription).toList());
        assertEquals(List.of(10.0, 10.0), byRow.get("CS 1.2").stream().map(ActivitySnapshotItem::getScore).toList());
        assertEquals(30.0, byRow.get("DID 2.1").getFirst().getScore());
        assertEquals("muzica-ria", byRow.get("RIA 3.2").getFirst().getRoleKey());
    }

    @Test
    void aFacultyFileIsReadByItsRowLabelsWithItsTypedPoints() throws Exception {
        // the faculty's own layout: a heading above the first table, so its rows sit lower than the template's,
        // and the points typed as text
        XSSFWorkbook wb = GridWorkbooks.musicGrid("Lect.univ.dr. POPESCU ION",
                List.of(new GridWorkbooks.GridLine("2. Documentarea realizărilor", "2.1. Înregistrări (cu cod de autentificare)",
                        "• CD Electrecord 2021\n• Înregistrare video, concert public 2023 https://youtu.be/abc")),
                List.of(new GridWorkbooks.GridLine("1. Concert / recital", "1.2 Prestații realizate în condiții de vizibilitate regională",
                        "- Concert 12.05.2024, Timișoara\n- Recital 2023, Arad\n- Gală 2022, Lugoj")),
                List.of());
        points(wb.getSheet("Tabelul 1 - DID"), "2.1", "60 p");
        points(wb.getSheet("Tabelul 2 - CS"), "1.2", "30p");

        List<SnapshotItem> parsed = support.parse(new ByteArrayInputStream(GridWorkbooks.bytes(wb)), ReportFormat.XLSX);

        Map<String, Double> totals = parsed.stream().map(ActivitySnapshotItem.class::cast)
                .collect(Collectors.groupingBy(ActivitySnapshotItem::getActivityName,
                        Collectors.summingDouble(ActivitySnapshotItem::getScore)));
        assertEquals(Map.of("DID 2.1", 60.0, "CS 1.2", 30.0), totals);
        assertEquals(3, parsed.stream().filter(i -> "CS 1.2".equals(((ActivitySnapshotItem) i).getActivityName())).count());
    }

    private static void points(Sheet sheet, String label, String points) {
        for (Row row : sheet) {
            if (row.getCell(1) != null && row.getCell(1).getStringCellValue().startsWith(label)) {
                row.createCell(5).setCellValue(points);
                return;
            }
        }
        throw new AssertionError("no row " + label + " in " + sheet.getSheetName());
    }
}
