package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingKind;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingRole;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingTileLayout;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.parse.TemplateXlsxScoreParser;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TileData;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateXlsxRendererCitationsTest {

    // H106 S1: both xlsx bindings — the 2026 template is stored with inline strings and used to lose every write.
    private static final String BINDING_2016 = "report-templates/informatica-2016/binding.json";
    private static final String BINDING_2026 = "report-templates/informatica-2026/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void clonesPerPublicationCitationSheetsAndRegeneratesSummaryFormulas(String bindingResource) throws Exception {
        TemplateBinding binding = perSheet(loader.load(bindingResource));

        CitationSnapshotItem tile1 = citedPub("Self-supervised graph learning",
                "Journal of Machine Learning Research", 2023, 2,
                citing("Follow-up on graph SSL", "Doe, J.; Roe, R.", "ICLR Proceedings", "pp. 1-12", 2024, "NU", "AA"),
                citing("Survey of representation learning", "Smith, A.", "Survey J.", "Vol 5", 2024, "NU", "B"));
        CitationSnapshotItem tile2 = citedPub("Sparse attention is all you need",
                "Neural Computation", 2024, 3,
                citing("Attention efficiency study", "Lee, K.", "NeurIPS", "pp. 11-22", 2025, "NU", "A"));

        byte[] bytes = renderer.render(binding, Map.of(),
                Map.of("citations-per-publication", List.of(
                        new TileData(tile1.toHeaderMap(), tile1.toInnerRowMaps()),
                        new TileData(tile2.toHeaderMap(), tile2.toInnerRowMaps())
                )));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            // Original template sheet is gone.
            assertThat(out.getSheet("C-Citari-TPL")).isNull();

            // New per-publication sheets exist with expected names.
            Sheet citari01 = out.getSheet("Citari-01");
            Sheet citari02 = out.getSheet("Citari-02");
            assertThat(citari01).isNotNull();
            assertThat(citari02).isNotNull();

            // Per-tile title at C5 expanded with publication header values.
            assertThat(citari01.getRow(4).getCell(2).getStringCellValue())
                    .isEqualTo("B2. CITĂRI PENTRU LUCRAREA: Self-supervised graph learning (Journal of Machine Learning Research, 2023)");
            assertThat(citari02.getRow(4).getCell(2).getStringCellValue())
                    .contains("Sparse attention is all you need");

            // Per-tile scalar at J21 (index 20, col 9) = author count.
            assertThat(citari01.getRow(20).getCell(9).getNumericCellValue()).isEqualTo(2.0);
            assertThat(citari02.getRow(20).getCell(9).getNumericCellValue()).isEqualTo(3.0);

            // First citing publication row on tile 1: index 7 (row 8). C/D/E/G/H/I.
            Row row1 = citari01.getRow(7);
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("Follow-up on graph SSL");
            assertThat(row1.getCell(3).getStringCellValue()).isEqualTo("Doe, J.; Roe, R.");
            assertThat(row1.getCell(4).getStringCellValue()).isEqualTo("ICLR Proceedings");
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(2024.0);
            assertThat(row1.getCell(7).getStringCellValue()).isEqualTo("NU");
            assertThat(row1.getCell(8).getStringCellValue()).isEqualTo("AA");
            // J on row 8 stays formula.
            assertThat(row1.getCell(9).getCellType()).isEqualTo(CellType.FORMULA);

            // Second citing row written too.
            assertThat(citari01.getRow(8).getCell(2).getStringCellValue()).isEqualTo("Survey of representation learning");

            // Summary sheet I7 is now a SUM over tile cells.
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellType()).isEqualTo(CellType.FORMULA);
            String i7Formula = i7.getCellFormula();
            assertThat(i7Formula).contains("'Citari-01'!I22").contains("'Citari-02'!I22");
            assertThat(i7Formula).doesNotContain("C-Citari-TPL");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void tileWithMoreThan12CitationsExpandsAndAdjustsSummaryReference(String bindingResource) throws Exception {
        TemplateBinding binding = perSheet(loader.load(bindingResource));

        CitationSnapshotItem tile = citedPub("Use of genetic algorithms in numerical weather prediction",
                "Meteorological Soc.", 2018, 3);
        for (int i = 1; i <= 15; i++) {
            tile.getCitingPublications().add(
                    citing("Citing paper " + i, "Author " + i, "Forum " + i, "pp " + i, 2019 + i % 3,
                            "NU", i == 1 ? "AA" : "B"));
        }

        byte[] bytes = renderer.render(binding, java.util.Map.of(),
                java.util.Map.of("citations-per-publication", List.of(
                        new ro.uvt.pokedex.core.service.reporting.transfer.render.TileData(
                                tile.toHeaderMap(), tile.toInnerRowMaps()))));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet citari = out.getSheet("Citari-01");
            assertThat(citari).isNotNull();

            // All 15 citing rows present (rows 8..22, 0-based 7..21).
            for (int i = 0; i < 15; i++) {
                Row row = citari.getRow(7 + i);
                assertThat(row).as("row %d", 7 + i).isNotNull();
                assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Citing paper " + (i + 1));
            }

            // J21 (Numar autori lucrare) sat just below the data range, so the inner-table expand
            // pushed it down by 3 along with the aggregation block. Its original value rides along
            // and the formulas in the aggregation rows still reference it correctly via POI's
            // FormulaShifter.
            assertThat(citari.getRow(23).getCell(9).getNumericCellValue()).isEqualTo(3.0);

            // Per-category totals were at rows 22-28 (0-based 21-27). With 3 extra rows they sit at
            // rows 25-31. Row 25 = "Total categoria A*" with the AA tile that we created.
            Cell totalAaCount = citari.getRow(24).getCell(8); // I22 → I25 after +3 shift
            assertThat(totalAaCount.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(totalAaCount.getCellFormula()).contains("I8:I22");

            // Summary sheet's I7 must reference the SHIFTED total cell I25, not the original I22.
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(i7.getCellFormula()).contains("'Citari-01'!I25");
            assertThat(i7.getCellFormula()).doesNotContain("'Citari-01'!I22");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void zeroTilesNeutralizesSummaryFormulasToLiteralZero(String bindingResource) throws Exception {
        TemplateBinding binding = perSheet(loader.load(bindingResource));

        byte[] bytes = renderer.render(binding, Map.of(), Map.of("citations-per-publication", List.of()));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(out.getSheet("C-Citari-TPL")).isNull();
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(i7.getNumericCellValue()).isEqualTo(0.0);
        }
    }

    // ── H106 S2: STACKED layout (the bindings' default since 2026-09-12) ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void stackedLayoutPutsEveryTileInOneSheetAndRoundTripsThroughTheParser(String bindingResource) throws Exception {
        TemplateBinding binding = loader.load(bindingResource);
        assertThat(tiledRole(binding).getTileLayout()).isEqualTo(BindingTileLayout.STACKED);

        CitationSnapshotItem tile1 = citedPub("Self-supervised graph learning",
                "Journal of Machine Learning Research", 2023, 2,
                citing("Follow-up on graph SSL", "Doe, J.; Roe, R.", "ICLR Proceedings", "pp. 1-12", 2024, "NU", "AA"),
                citing("Survey of representation learning", "Smith, A.", "Survey J.", "Vol 5", 2024, "NU", "B"));
        tile1.setPublicationDoi("https://doi.org/10.48550/arxiv.0905.4601");
        tile1.getCitingPublications().get(0).setDoi("10.3233/WEB-190396");
        CitationSnapshotItem tile2 = citedPub("Sparse attention is all you need",
                "Neural Computation", 2024, 3,
                citing("Attention efficiency study", "Lee, K.", "NeurIPS", "pp. 11-22", 2025, "NU", "A"));

        byte[] bytes = renderer.render(binding, Map.of(),
                Map.of("citations-per-publication", List.of(
                        new TileData(tile1.toHeaderMap(), tile1.toInnerRowMaps()),
                        new TileData(tile2.toHeaderMap(), tile2.toInnerRowMaps())
                )));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(out.getSheet("C-Citari-TPL")).isNull();
            assertThat(out.getSheet("Citari-01")).isNull();
            // H106 S4: DOI = hyperlink ON the title cells (cited work + citing row), text unchanged, no link without a DOI.
            Sheet cit = out.getSheet("C-Citari");
            assertThat(cit.getRow(4).getCell(2).getHyperlink().getAddress()).isEqualTo("https://doi.org/10.48550/arxiv.0905.4601");
            assertThat(cit.getRow(7).getCell(2).getHyperlink().getAddress()).isEqualTo("https://doi.org/10.3233/WEB-190396");
            assertThat(cit.getRow(8).getCell(2).getHyperlink()).isNull();
            assertThat(cit.getRow(29).getCell(2).getHyperlink()).isNull();
            Sheet sheet = out.getSheet("C-Citari");
            assertThat(sheet).isNotNull();
            // The single sheet keeps the template's slot (right after the summary sheet), not the clone's last place.
            assertThat(out.getSheetIndex("C-Citari")).isEqualTo(out.getSheetIndex("C-Citari-centralizare") + 1);

            // Block geometry: title C5 … TOTAL row 28 = 24 rows, one blank row, next tile's title at C30.
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue())
                    .isEqualTo("B2. CITĂRI PENTRU LUCRAREA: Self-supervised graph learning (Journal of Machine Learning Research, 2023)");
            assertThat(sheet.getRow(29).getCell(2).getStringCellValue())
                    .contains("Sparse attention is all you need");
            assertThat(sheet.getRow(7).getCell(2).getStringCellValue()).isEqualTo("Follow-up on graph SSL");
            assertThat(sheet.getRow(8).getCell(2).getStringCellValue()).isEqualTo("Survey of representation learning");
            assertThat(sheet.getRow(32).getCell(2).getStringCellValue()).isEqualTo("Attention efficiency study");
            // Per-tile scalar (J21 → J46) and the TOTAL label travel with each block.
            assertThat(sheet.getRow(20).getCell(9).getNumericCellValue()).isEqualTo(2.0);
            assertThat(sheet.getRow(45).getCell(9).getNumericCellValue()).isEqualTo(3.0);
            assertThat(sheet.getRow(27).getCell(2).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(sheet.getRow(52).getCell(2).getStringCellValue()).isEqualTo("TOTAL");
            // The copied block's formulas were shifted to its own rows.
            assertThat(sheet.getRow(46).getCell(8).getCellFormula()).isEqualTo("COUNTIF(I33:I44,\"=AA\")");
            assertThat(sheet.getRow(52).getCell(9).getCellFormula()).isEqualTo("SUM(J47:J51)");

            // Summary sums the per-tile aggregation cells inside the single sheet.
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(i7.getCellFormula()).isEqualTo("SUM('C-Citari'!I22,'C-Citari'!I47)");
        }

        // The verification parser reads our export back as two tiles with their citing rows.
        List<CitationSnapshotItem> parsed = parseTiles(binding, bytes);
        assertThat(parsed).extracting(CitationSnapshotItem::getPublicationTitle)
                .containsExactly("Self-supervised graph learning", "Sparse attention is all you need");
        assertThat(parsed.get(0).getCitingPublications()).extracting(CitationSnapshotItem.CitingPublication::getTitle)
                .containsExactly("Follow-up on graph SSL", "Survey of representation learning");
        assertThat(parsed.get(1).getCitingPublications()).hasSize(1);
        // Tile 1: AA(12) + B(4) over max(1, 2-2)=1 → 16; tile 2: A(8) over max(1, 3-2)=1 → 8.
        assertThat(parsed.get(0).getScore()).isEqualTo(16.0);
        assertThat(parsed.get(1).getScore()).isEqualTo(8.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void stackedLayoutExpansionPushesTheFollowingTilesDown(String bindingResource) throws Exception {
        TemplateBinding binding = loader.load(bindingResource);

        CitationSnapshotItem big = citedPub("Use of genetic algorithms in numerical weather prediction",
                "Meteorological Soc.", 2018, 3);
        for (int i = 1; i <= 15; i++) {
            big.getCitingPublications().add(citing("Citing paper " + i, "Author " + i, "Forum " + i,
                    "pp " + i, 2019 + i % 3, "NU", i == 1 ? "AA" : "B"));
        }
        CitationSnapshotItem small = citedPub("Sparse attention is all you need", "Neural Computation", 2024, 3,
                citing("Attention efficiency study", "Lee, K.", "NeurIPS", "pp. 11-22", 2025, "NU", "A"));

        byte[] bytes = renderer.render(binding, Map.of(),
                Map.of("citations-per-publication", List.of(
                        new TileData(big.toHeaderMap(), big.toInnerRowMaps()),
                        new TileData(small.toHeaderMap(), small.toInnerRowMaps()))));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = out.getSheet("C-Citari");
            for (int i = 0; i < 15; i++) {
                assertThat(sheet.getRow(7 + i).getCell(2).getStringCellValue()).isEqualTo("Citing paper " + (i + 1));
            }
            // 3 extra rows: tile 1's TOTAL moved 28 → 31, tile 2's title 30 → 33, its data 33 → 36, its TOTAL 53 → 56.
            assertThat(sheet.getRow(30).getCell(2).getStringCellValue()).isEqualTo("TOTAL");
            assertThat(sheet.getRow(32).getCell(2).getStringCellValue()).contains("Sparse attention is all you need");
            assertThat(sheet.getRow(35).getCell(2).getStringCellValue()).isEqualTo("Attention efficiency study");
            assertThat(sheet.getRow(55).getCell(2).getStringCellValue()).isEqualTo("TOTAL");
            // Tile 1's range grew, tile 2's formulas followed the shift.
            assertThat(sheet.getRow(24).getCell(8).getCellFormula()).isEqualTo("COUNTIF(I8:I22,\"=AA\")");
            assertThat(sheet.getRow(49).getCell(8).getCellFormula()).isEqualTo("COUNTIF(I36:I47,\"=AA\")");
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellFormula()).isEqualTo("SUM('C-Citari'!I25,'C-Citari'!I50)");
        }

        List<CitationSnapshotItem> parsed = parseTiles(binding, bytes);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getCitingPublications()).hasSize(15);
        assertThat(parsed.get(1).getCitingPublications()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {BINDING_2016, BINDING_2026})
    void stackedLayoutWithZeroTilesProducesNoCitationSheetAndAZeroSummary(String bindingResource) throws Exception {
        TemplateBinding binding = loader.load(bindingResource);

        byte[] bytes = renderer.render(binding, Map.of(), Map.of("citations-per-publication", List.of()));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(out.getSheet("C-Citari-TPL")).isNull();
            assertThat(out.getSheet("C-Citari")).isNull();
            Cell i7 = out.getSheet("C-Citari-centralizare").getRow(6).getCell(8);
            assertThat(i7.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(i7.getNumericCellValue()).isEqualTo(0.0);
        }
    }

    private static BindingRole tiledRole(TemplateBinding binding) {
        return binding.getRoles().stream()
                .filter(r -> r.getKind() == BindingKind.TILED_SHEETS).findFirst().orElseThrow();
    }

    /** The per-sheet tests above pin the original H50.2 layout, which the bindings no longer select. */
    private static TemplateBinding perSheet(TemplateBinding binding) {
        tiledRole(binding).setTileLayout(BindingTileLayout.SHEET_PER_TILE);
        return binding;
    }

    private List<CitationSnapshotItem> parseTiles(TemplateBinding binding, byte[] bytes) {
        return new TemplateXlsxScoreParser().parse(binding, new ByteArrayInputStream(bytes)).stream()
                .filter(CitationSnapshotItem.class::isInstance)
                .map(CitationSnapshotItem.class::cast)
                .toList();
    }

    private CitationSnapshotItem citedPub(String title, String forum, int year, int authorCount,
                                          CitationSnapshotItem.CitingPublication... citing) {
        CitationSnapshotItem c = new CitationSnapshotItem();
        c.setItemKey(title);
        c.setPublicationTitle(title);
        c.setPublicationForumName(forum);
        c.setPublicationYear(year);
        c.setPublicationAuthorCount(authorCount);
        for (CitationSnapshotItem.CitingPublication p : citing) c.getCitingPublications().add(p);
        return c;
    }

    private CitationSnapshotItem.CitingPublication citing(String title, String authors, String forum,
                                                          String volume, int year, String workshop, String category) {
        CitationSnapshotItem.CitingPublication p = new CitationSnapshotItem.CitingPublication();
        p.setTitle(title);
        p.setAuthors(authors);
        p.setForumName(forum);
        p.setVolumeInfo(volume);
        p.setYear(year);
        p.setIsWorkshopDaNu(workshop);
        p.setForumCategoryLetter(category);
        return p;
    }
}
