package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateDocxRenderer;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Matematica2016ReportTypeImportSupportTest {

    private Matematica2016ReportTypeImportSupport support;

    @BeforeEach
    void setUp() {
        support = new Matematica2016ReportTypeImportSupport(
                new TemplateBindingLoader(new ObjectMapper()), new TemplateDocxRenderer());
        support.loadBinding(); // @PostConstruct, invoked manually outside Spring
    }

    private PublicationSnapshotItem pub(String roleKey, String title, String authors,
                                        double forumScore, int authors_n, double authorScore) {
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setRoleKey(roleKey);
        p.setTitle(title);
        p.setAuthors(authors);
        p.setForumName("J. Math");
        p.setYear(2023);
        p.setForumScore(forumScore);   // si = journal score before per-author division
        p.setAuthorCount(authors_n);
        p.setAuthorScore(authorScore); // si/ni
        p.setScore(authorScore);
        return p;
    }

    private CitationSnapshotItem cit(String cited, double score) {
        CitationSnapshotItem c = new CitationSnapshotItem();
        c.setRoleKey("citations-per-publication");
        c.setPublicationTitle(cited);
        c.setScore(score);
        CitationSnapshotItem.CitingPublication citing = new CitationSnapshotItem.CitingPublication();
        citing.setTitle("Citing paper X");
        citing.setForumName("Some Journal");
        citing.setYear(2024);
        c.getCitingPublications().add(citing);
        return c;
    }

    @Test
    void rendersDocxWithRowsAndDedicatedIndicatorTotals() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("matematica-2016");
        snap.getItems().add(pub("journal-publications", "On manifolds", "A. Pop, B. Ion", 4.0, 2, 2.0));
        snap.getItems().add(pub("journal-publications", "On groups", "A. Pop", 3.0, 1, 3.0));
        // Recent role: same title as a main pub → marks it recent; must NOT add a duplicate row.
        snap.getItems().add(pub("journal-publications-recent", "On manifolds", "A. Pop, B. Ion", 4.0, 2, 2.0));
        snap.getItems().add(cit("On manifolds", 1.5));
        snap.getTotals().put("journal-publications", 10.5);
        snap.getTotals().put("journal-publications-recent", 6.0);
        snap.getTotals().put("citations-per-publication", 3.0);
        snap.getTotals().put("C1_UVT", 5.0);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);
        assertNotNull(bytes);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFTable pubs = doc.getTables().get(0);
            // Row 1 (first data row): reference, recent flag, si (base), ni, si/ni.
            assertTrue(cellText(pubs, 1, 1).contains("On manifolds"), "pub reference written");
            assertEquals("X", cellText(pubs, 1, 2), "recent publication marked with X");
            assertEquals("4", cellText(pubs, 1, 3), "si = journal score before division");
            assertEquals("2", cellText(pubs, 1, 4), "ni = authorCount");
            assertEquals("2", cellText(pubs, 1, 5), "si/ni = authorScore");
            // Second publication landed in row 2, not recent, and the recent role added no duplicate row.
            assertTrue(cellText(pubs, 2, 1).contains("On groups"), "second pub row written");
            assertEquals("", cellText(pubs, 2, 2), "non-recent publication not marked");
            assertTrue(cellText(pubs, 3, 1).isBlank(), "no duplicate row from the recent role");

            // Combined S / Srecent total cell carries both dedicated-indicator totals.
            XWPFParagraph sPar = findParagraph(doc, "Srecent");
            assertNotNull(sPar);
            assertTrue(sPar.getText().contains("10.50"), "S total written: " + sPar.getText());
            assertTrue(sPar.getText().contains("6"), "Srecent total written: " + sPar.getText());

            // Citations table + C total.
            XWPFTable citations = doc.getTables().get(1);
            assertTrue(cellText(citations, 1, 1).contains("On manifolds"), "citation row written");
            assertTrue(findParagraph(doc, "C =").getText().contains("3"), "C total written");

            // Indicator block total.
            assertTrue(findParagraph(doc, "Total punctaj C1_UVT").getText().contains("5"), "C1_UVT total written");
        }
    }

    @Test
    void fillsSupplementaryIndicatorBlockRowsReplacingPlaceholders() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("matematica-2016");
        // C1_UVT has two template placeholder rows ("Carte 1 : …" + a dotted line); two items fill both.
        snap.getItems().add(activity("C1_UVT", "Algebră liniară, Editura X, 2020, ISBN 1, 200p"));
        snap.getItems().add(activity("C1_UVT", "Analiză matematică, Editura Y, 2021, ISBN 2, 300p"));
        // C3_UVT gets three items → one row must be cloned beyond the template placeholders.
        snap.getItems().add(activity("C3_UVT", "Grant A, contract 1, finanțator F, 2019-2021"));
        snap.getItems().add(activity("C3_UVT", "Grant B, contract 2, finanțator G, 2020-2022"));
        snap.getItems().add(activity("C3_UVT", "Grant C, contract 3, finanțator H, 2021-2023"));
        snap.getTotals().put("C1_UVT", 9.0);
        snap.getTotals().put("C3_UVT", 15.0);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String blocks = tableText(doc, 2);
            assertTrue(blocks.contains("Algebră liniară, Editura X"), "C1 item 1 written");
            assertTrue(blocks.contains("Analiză matematică, Editura Y"), "C1 item 2 written");
            assertTrue(blocks.contains("Grant A, contract 1"), "C3 item 1 written");
            assertTrue(blocks.contains("Grant C, contract 3"), "C3 cloned item written");
            assertFalse(blocks.contains("Carte 1 :  Autori, titlu, editura"),
                    "C1 placeholder replaced");
            // Block totals still land after the (now shifted) "Total punctaj Cx =" markers.
            assertTrue(findParagraph(doc, "Total punctaj  C1_UVT").getText().contains("9"), "C1 total written");
            assertTrue(findParagraph(doc, "Total punctaj  C3_UVT").getText().contains("15"), "C3 total written");
        }
    }

    @Test
    void formatsBlockPublicationsInMathOrderAuthorsTitleForum() {
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setAuthors("Pop A., Ion B.");
        p.setTitle("On manifolds");
        p.setForumName("J. Math");
        p.setVolumeInfo("vol. 12");
        p.setYear(2021);

        // Math fișă order: Autori, titlu, revista, vol. (anul) — authors lead, comma-separated.
        assertEquals("Pop A., Ion B., On manifolds, J. Math, vol. 12 (2021)",
                support.formatBlockPublicationDescription(p));
    }

    private ActivitySnapshotItem activity(String block, String description) {
        ActivitySnapshotItem a = new ActivitySnapshotItem();
        a.setRoleKey("indicatori-suplimentari-uvt");
        a.setActivityName(block);
        a.setDescription(description);
        a.setScore(3.0);
        return a;
    }

    private static String tableText(XWPFDocument doc, int tableIdx) {
        StringBuilder sb = new StringBuilder();
        XWPFTable t = doc.getTables().get(tableIdx);
        for (XWPFTableRow r : t.getRows()) {
            for (XWPFTableCell c : r.getTableCells()) sb.append(c.getText()).append(' ');
        }
        return sb.toString();
    }

    private static String cellText(XWPFTable table, int rowIdx, int cellIdx) {
        XWPFTableRow row = table.getRow(rowIdx);
        XWPFTableCell cell = row.getCell(cellIdx);
        return cell == null ? "" : cell.getText().trim();
    }

    private static XWPFParagraph findParagraph(XWPFDocument doc, String marker) {
        String needle = marker.replaceAll("\\s+", " ").trim();
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    for (XWPFParagraph p : c.getParagraphs()) {
                        if (p.getText().replaceAll("\\s+", " ").trim().contains(needle)) return p;
                    }
                }
            }
        }
        return null;
    }
}
