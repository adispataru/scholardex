package ro.uvt.pokedex.core.service.reporting.transfer.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.SnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TileData;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateXlsxScoreParserTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();
    private final TemplateXlsxScoreParser parser = new TemplateXlsxScoreParser();

    @Test
    void parsesPunctajPFromRenderedJournalRowsByEvaluatingFormula() {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        // Category A (8 base points) with a single author → MAX(1, 1-2) = 1 → score 8.
        PublicationSnapshotItem soloAuthorA = journal("Single-author A paper", "A", 1);
        // Category AA (12 base) with 5 authors → /MAX(1, 5-2) = /3 → 4.
        PublicationSnapshotItem fiveAuthorAA = journal("Five-author A* paper", "AA", 5);

        byte[] bytes = renderer.render(binding, Map.of(
                "journal-publications", List.of(soloAuthorA.toRowMap(), fiveAuthorAA.toRowMap())
        ));

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bytes));

        List<PublicationSnapshotItem> journals = parsed.stream()
                .filter(i -> i instanceof PublicationSnapshotItem)
                .map(i -> (PublicationSnapshotItem) i)
                .filter(i -> "journal-publications".equals(i.getRoleKey()))
                .toList();

        assertThat(journals).hasSize(2);
        PublicationSnapshotItem solo = byTitle(journals, "Single-author A paper");
        PublicationSnapshotItem five = byTitle(journals, "Five-author A* paper");
        assertThat(solo.getScore()).isEqualTo(8.0);
        assertThat(five.getScore()).isEqualTo(4.0);
    }

    @Test
    void stopsAtBlankSeparatorRowAndIgnoresAggregationBlock() {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        byte[] bytes = renderer.render(binding, Map.of(
                "journal-publications", List.of(journal("Only paper", "B", 1).toRowMap())
        ));
        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bytes));
        List<PublicationSnapshotItem> journals = parsed.stream()
                .filter(i -> i instanceof PublicationSnapshotItem p && "journal-publications".equals(p.getRoleKey()))
                .map(i -> (PublicationSnapshotItem) i)
                .toList();
        // Exactly one data row parsed — the "Total categoria A*" aggregation labels must not leak in.
        assertThat(journals).hasSize(1);
        assertThat(journals.get(0).getTitle()).isEqualTo("Only paper");
        assertThat(journals.get(0).getScore()).isEqualTo(4.0); // B = 4 base, single author.
    }

    @Test
    void parsesCitationTilesSummingInnerPunctajPerCitedPublication() {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        CitationSnapshotItem cited = new CitationSnapshotItem();
        cited.setPublicationTitle("Use of genetic algorithms in numerical weather prediction");
        cited.setPublicationForumName("Meteorological Soc.");
        cited.setPublicationYear(2018);
        cited.setPublicationAuthorCount(7); // divisor MAX(1, 7-2) = 5
        // Two citing rows: A* (12) workshop=NU and B (4) → inner Punctaj J = raw category points.
        cited.getCitingPublications().add(citing("Citing A* paper", "AA", "NU"));
        cited.getCitingPublications().add(citing("Citing B paper", "B", "NU"));

        byte[] bytes = renderer.render(binding, Map.of(),
                Map.of("citations-per-publication", List.of(
                        new TileData(cited.toHeaderMap(), cited.toInnerRowMaps()))));

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bytes));
        List<CitationSnapshotItem> tiles = parsed.stream()
                .filter(i -> i instanceof CitationSnapshotItem)
                .map(i -> (CitationSnapshotItem) i)
                .toList();

        assertThat(tiles).hasSize(1);
        CitationSnapshotItem tile = tiles.get(0);
        assertThat(tile.getRoleKey()).isEqualTo("citations-per-publication");
        // Title recovered from the C5 template string.
        assertThat(tile.getPublicationTitle()).isEqualTo("Use of genetic algorithms in numerical weather prediction");
        // Tile score reads the author-divided TOTAL row, not the raw sum (which would be 16):
        // per-category divided by MAX(1, authors-2)=5 → 12/5 + 4/5 = 3.2.
        assertThat(tile.getScore()).isEqualTo(3.2);
        // Breakdown keeps the raw per-citation points (12 and 4).
        assertThat(tile.getCitingPublications()).hasSize(2);
        assertThat(tile.getCitingPublications().stream().mapToDouble(c -> c.getScore()).sum()).isEqualTo(16.0);
    }

    @Test
    void parsesActivityBlocksToCorrectBlockDespiteHeaderWordDriftAndEmptyBlocks() {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        // "Editor proceedings" left empty; the real template's curs header reads
        // "CURS UNIVERSITAR IN FORMAT ELECTRONIC" while the block is "Curs in format electronic".
        ActivitySnapshotItem curs = act("Curs in format electronic", "Electronic course on distributed systems", "A", 5.0);
        ActivitySnapshotItem premiu = act("Premii", "Best paper award at a top venue", "A", 8.0);

        byte[] bytes = renderer.render(binding, Map.of(
                "activities-perspectiva-d", List.of(curs.toRowMap(), premiu.toRowMap())));

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bytes));
        List<ActivitySnapshotItem> acts = parsed.stream()
                .filter(i -> i instanceof ActivitySnapshotItem)
                .map(i -> (ActivitySnapshotItem) i)
                .toList();

        ActivitySnapshotItem parsedCurs = acts.stream()
                .filter(a -> "Electronic course on distributed systems".equals(a.getDescription()))
                .findFirst().orElseThrow();
        // Attributed to the curs block, NOT swept under the empty "Editor proceedings".
        assertThat(parsedCurs.getActivityName()).isEqualTo("Curs in format electronic");
        assertThat(acts).noneMatch(a -> "Editor proceedings".equals(a.getActivityName()));
    }

    @Test
    void grantRowsParseIntoTheGrantsBlockNotThePrecedingRevistaBlock() {
        // Regression (user import, cnfis2023 FV): the binding named the block "Grant Cercetare" while
        // the template header reads "GRANTURI" and the report's block assignment says "Granturi" —
        // token containment scored 0.0, the grants header went unrecognized, and every grant row was
        // swallowed by the preceding recognized section ("Director/editor revista").
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        ActivitySnapshotItem grant = act("Granturi", "SCAPE, www.scape-project.eu", "membru, grant FP7", 2.0);

        byte[] bytes = renderer.render(binding, Map.of(
                "activities-perspectiva-d", List.of(grant.toRowMap())));

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bytes));
        ActivitySnapshotItem parsedGrant = parsed.stream()
                .filter(i -> i instanceof ActivitySnapshotItem a
                        && "SCAPE, www.scape-project.eu".equals(a.getDescription()))
                .map(i -> (ActivitySnapshotItem) i)
                .findFirst().orElseThrow();
        assertThat(parsedGrant.getActivityName()).isEqualTo("Granturi");
    }

    @Test
    void customSectionHeaderStartsItsOwnBlockInsteadOfBeingSwallowed() throws Exception {
        // Researchers edit the official template and add sections the binding doesn't know (seen in a
        // real CNFIS FV: "Profesor/cercetător asociat/visiting"). The marker row must start its OWN
        // block named from the header — previously its rows silently attached to the previous section.
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        ActivitySnapshotItem premiu = act("Premii", "Best paper award", "A", 8.0);
        byte[] bytes = renderer.render(binding, Map.of(
                "activities-perspectiva-d", List.of(premiu.toRowMap())));

        // Append a custom section at the bottom of the activities sheet: header + one data row.
        org.apache.poi.ss.usermodel.Workbook wb =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes));
        org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("Indicatorul I");
        int base = sheet.getLastRowNum() + 2;
        sheet.createRow(base).createCell(2)
                .setCellValue("C1. Justificări pentru indicatorul Profesor/cercetător asociat/visiting (perspectiva D)");
        org.apache.poi.ss.usermodel.Row data = sheet.createRow(base + 1);
        data.createCell(2).setCellValue("Universitatea din Viena, 4*74 = 296");
        data.createCell(10).setCellValue(24.0);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        wb.write(bos);
        wb.close();

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bos.toByteArray()));
        ActivitySnapshotItem custom = parsed.stream()
                .filter(i -> i instanceof ActivitySnapshotItem a
                        && a.getDescription() != null && a.getDescription().startsWith("Universitatea din Viena"))
                .map(i -> (ActivitySnapshotItem) i)
                .findFirst().orElseThrow();
        assertThat(custom.getActivityName()).isEqualTo("Profesor/cercetător asociat/visiting");
        assertThat(custom.getScore()).isEqualTo(24.0);
        // The known block keeps its own rows.
        assertThat(parsed).anyMatch(i -> i instanceof ActivitySnapshotItem a
                && "Premii".equals(a.getActivityName()) && "Best paper award".equals(a.getDescription()));
    }

    @Test
    void officiallyNamedCitationTileSheetsAreParsed() throws Exception {
        // Real researcher-filled FVs keep the official tile-sheet names (C-Citari-TPL, C-Citari-TPL1,
        // …) instead of our export's Citari-NN — those citations were silently unparsed before.
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        CitationSnapshotItem tile = new CitationSnapshotItem();
        tile.setPublicationTitle("Use of genetic algorithms in numerical weather prediction");
        tile.setPublicationForumName("J Forecast");
        tile.setPublicationYear(2020);
        tile.setPublicationAuthorCount(3);
        tile.getCitingPublications().add(citing("Citing paper one", "A", "NU"));
        byte[] bytes = renderer.render(binding, Map.of(),
                Map.of("citations-per-publication", List.of(
                        new TileData(tile.toHeaderMap(), tile.toInnerRowMaps()))));

        // Rename the export-named tiles to the official naming.
        org.apache.poi.ss.usermodel.Workbook wb =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes));
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            String n = wb.getSheetName(s);
            if (n.startsWith("Citari-")) {
                wb.setSheetName(s, "C-Citari-TPL" + n.substring("Citari-".length()));
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        wb.write(bos);
        wb.close();

        List<SnapshotItem> parsed = parser.parse(binding, new ByteArrayInputStream(bos.toByteArray()));
        CitationSnapshotItem parsedTile = parsed.stream()
                .filter(i -> i instanceof CitationSnapshotItem)
                .map(i -> (CitationSnapshotItem) i)
                .findFirst().orElseThrow();
        assertThat(parsedTile.getPublicationTitle())
                .isEqualTo("Use of genetic algorithms in numerical weather prediction");
        assertThat(parsedTile.getCitingPublications()).hasSize(1);
    }

    private ActivitySnapshotItem act(String blockName, String description, String category, double score) {
        ActivitySnapshotItem a = new ActivitySnapshotItem();
        a.setActivityName(blockName);
        a.setDescription(description);
        a.setCategory(category);
        a.setScore(score);
        return a;
    }

    private CitationSnapshotItem.CitingPublication citing(String title, String category, String workshop) {
        CitationSnapshotItem.CitingPublication c = new CitationSnapshotItem.CitingPublication();
        c.setTitle(title);
        c.setForumCategoryLetter(category);
        c.setIsWorkshopDaNu(workshop);
        return c;
    }

    private PublicationSnapshotItem journal(String title, String category, int authorCount) {
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setItemKey(title);
        p.setTitle(title);
        p.setForumCategoryLetter(category);
        p.setAuthorCount(authorCount);
        return p;
    }

    private PublicationSnapshotItem byTitle(List<PublicationSnapshotItem> items, String title) {
        return items.stream().filter(i -> title.equals(i.getTitle())).findFirst().orElseThrow();
    }
}
