package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateDocxRenderer;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H65 slice 1: the Fizică fišă renders the I (2.1) + P (2.2) article tables + the T20 summary (I / P / T). */
class Fizica2024ReportTypeImportSupportTest {

    private Fizica2024ReportTypeImportSupport support;

    @BeforeEach
    void setUp() {
        support = new Fizica2024ReportTypeImportSupport(
                new TemplateBindingLoader(new ObjectMapper()), new TemplateDocxRenderer());
        support.loadBinding();
    }

    @Test
    void rendersIAndPArticleTablesAndSummary() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("fizica-ff");

        // I article: 6 authors → Nef = 5.5; AIS = 4.0 → AIS/Nef = 0.7272…
        PublicationSnapshotItem i = new PublicationSnapshotItem();
        i.setRoleKey("fizica-articles-author");
        i.setTitle("Quantum widget"); i.setAuthors("Pop A. et al."); i.setForumName("Phys. Rev."); i.setYear(2022);
        i.setAuthorCount(6); i.setForumScore(4.0); i.setAuthorScore(4.0 / 5.5);
        snap.getItems().add(i);

        // P article: candidate is first/corresponding; P scores ΣAIS (no Nef), so authorScore = AIS = 4.0.
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setRoleKey("fizica-articles-principal");
        p.setTitle("Quantum widget"); p.setAuthors("Pop A. et al."); p.setForumName("Phys. Rev."); p.setYear(2022);
        p.setAuthorScore(4.0);
        snap.getItems().add(p);

        snap.getTotals().put("fizica-articles-author", 4.0 / 5.5);  // I
        snap.getTotals().put("fizica-articles-principal", 4.0);     // P

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 2.1 (I) article table = table 17: ref | AIS | n | Nef | AIS/Nef.
            XWPFTable iTable = doc.getTables().get(17);
            assertTrue(cell(iTable, 1, 1).contains("Quantum widget"), "I ref: " + cell(iTable, 1, 1));
            assertEquals("4", cell(iTable, 1, 2), "AIS");
            assertEquals("6", cell(iTable, 1, 3), "n");
            assertEquals("5.50", cell(iTable, 1, 4), "Nef");
            assertEquals("0.73", cell(iTable, 1, 5), "AIS/Nef");
            assertTrue(markerRowCell(iTable, "I =", 1).contains("0.73"), "I total: " + markerRowCell(iTable, "I =", 1));

            // 2.2 (P) article table = table 18: ref | AIS.
            XWPFTable pTable = doc.getTables().get(18);
            assertTrue(cell(pTable, 1, 1).contains("Quantum widget"), "P ref");
            assertEquals("4", cell(pTable, 1, 2), "P AIS");
            assertTrue(markerRowCell(pTable, "P =", 1).contains("4"), "P total");

            // T20 summary = table 20, "Valoare realizata" row: I (cell 2), P (cell 3), T (cell 6) = I/2 + P/2.
            XWPFTable summary = doc.getTables().get(20);
            assertEquals("0.73", markerRowCell(summary, "Valoare realizata", 2), "summary I");
            assertEquals("4", markerRowCell(summary, "Valoare realizata", 3), "summary P");
            assertEquals("2.36", markerRowCell(summary, "Valoare realizata", 6), "summary T = I/2 + P/2");
        }
    }

    @Test
    void rendersA1AndA4DidacticActivityBlocksAndASubtotal() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("fizica-ff");

        // A1 book, 6 authors → 4/Nef = 4/5.5 = 0.727…
        ActivitySnapshotItem a1 = new ActivitySnapshotItem();
        a1.setRoleKey("fizica-a1"); a1.setActivityName("A1");
        a1.setDescription("Cartea mea, Springer (2022)"); a1.setScore(4.0 / 5.5);
        snap.getItems().add(a1);
        // A4 national manual, 1 author → 0.5/Nef = 0.5.
        ActivitySnapshotItem a4 = new ActivitySnapshotItem();
        a4.setRoleKey("fizica-a4"); a4.setActivityName("A4");
        a4.setDescription("Manual de laborator, Editura UVT (2021)"); a4.setScore(0.5);
        snap.getItems().add(a4);

        snap.getTotals().put("A1", 4.0 / 5.5);
        snap.getTotals().put("A4", 0.5);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // A1 = table 7: book row (col1=desc, col4=score) + total in the "Punctaj total indicator A1" row.
            XWPFTable a1Table = doc.getTables().get(7);
            assertTrue(cell(a1Table, 1, 1).contains("Cartea mea"), "A1 desc: " + cell(a1Table, 1, 1));
            assertEquals("0.73", cell(a1Table, 1, 4), "A1 item score");
            assertEquals("0.73", markerRowCell(a1Table, "Punctaj total indicator A1", 1), "A1 total");

            // A4 = table 10.
            XWPFTable a4Table = doc.getTables().get(10);
            assertTrue(cell(a4Table, 1, 1).contains("Manual de laborator"), "A4 desc");
            assertEquals("0.50", cell(a4Table, 1, 4), "A4 item score");
            assertEquals("0.50", markerRowCell(a4Table, "Punctaj total indicator A4", 1), "A4 total");

            // Summary: A = A1 + A4 = 1.227 → "1.23"; T = A + I/2 + P/2 (I=P=0 here) = "1.23".
            XWPFTable summary = doc.getTables().get(20);
            assertEquals("1.23", markerRowCell(summary, "Valoare realizata", 1), "summary A");
            assertEquals("1.23", markerRowCell(summary, "Valoare realizata", 6), "summary T");
        }
    }

    @Test
    void rendersA7ToA10PatentAndProjectBlocksAndASubtotal() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("fizica-ff");

        // A7 intl patent, 2 inventors → 3/Nef = 3/2 = 1.5
        ActivitySnapshotItem a7 = new ActivitySnapshotItem();
        a7.setRoleKey("fizica-a7"); a7.setActivityName("A7");
        a7.setDescription("Brevet triadic, EPO (2021)"); a7.setScore(1.5);
        snap.getItems().add(a7);
        // A8 national patent, 1 inventor → 0.5/Nef = 0.5
        ActivitySnapshotItem a8 = new ActivitySnapshotItem();
        a8.setRoleKey("fizica-a8"); a8.setActivityName("A8");
        a8.setDescription("Brevet OSIM (2020)"); a8.setScore(0.5);
        snap.getItems().add(a8);
        // A9 program/study director → 0.5 (count)
        ActivitySnapshotItem a9 = new ActivitySnapshotItem();
        a9.setRoleKey("fizica-a9"); a9.setActivityName("A9");
        a9.setDescription("Director program de studii (2022)"); a9.setScore(0.5);
        snap.getItems().add(a9);
        // A10 research project, trusted budget 270000 → 270000/100000 = 2.7
        ActivitySnapshotItem a10 = new ActivitySnapshotItem();
        a10.setRoleKey("fizica-a10"); a10.setActivityName("A10");
        a10.setDescription("PN-III grant, 270.000 EUR"); a10.setScore(2.7);
        snap.getItems().add(a10);

        snap.getTotals().put("A7", 1.5);
        snap.getTotals().put("A8", 0.5);
        snap.getTotals().put("A9", 0.5);
        snap.getTotals().put("A10", 2.7);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFTable a7Table = doc.getTables().get(13);
            assertTrue(cell(a7Table, 1, 1).contains("triadic"), "A7 desc: " + cell(a7Table, 1, 1));
            assertEquals("1.50", cell(a7Table, 1, 4), "A7 item score");
            assertEquals("1.50", markerRowCell(a7Table, "Punctaj total indicator A7", 1), "A7 total");

            XWPFTable a8Table = doc.getTables().get(14);
            assertEquals("0.50", markerRowCell(a8Table, "Punctaj total indicator A8", 1), "A8 total");

            XWPFTable a9Table = doc.getTables().get(15);
            assertEquals("0.50", markerRowCell(a9Table, "Punctaj total indicator A9", 1), "A9 total");

            XWPFTable a10Table = doc.getTables().get(16);
            assertTrue(cell(a10Table, 1, 1).contains("270.000"), "A10 desc");
            assertEquals("2.70", cell(a10Table, 1, 4), "A10 item score");
            assertEquals("2.70", markerRowCell(a10Table, "Punctaj total indicator A10", 1), "A10 total");

            // Summary: A = A7+A8+A9+A10 = 5.2 → "5.20"; T = A + I/2 + P/2 (I=P=0) = "5.20".
            XWPFTable summary = doc.getTables().get(20);
            assertEquals("5.20", markerRowCell(summary, "Valoare realizata", 1), "summary A");
            assertEquals("5.20", markerRowCell(summary, "Valoare realizata", 6), "summary T");
        }
    }

    @Test
    void rendersCHAndCompositeTInSummary() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("fizica-ff");
        snap.getTotals().put("A1", 2.0);                        // A = ΣAᵢ = 2.0
        snap.getTotals().put("fizica-articles-author", 4.0);    // I
        snap.getTotals().put("fizica-articles-principal", 2.0); // P
        snap.getTotals().put("fizica-c", 30.0);                 // C
        snap.getTotals().put("fizica-h", 8.0);                  // h

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // Summary docxTotals format trims trailing zeros (integers → "2", non-integers keep needed decimals).
            XWPFTable summary = doc.getTables().get(20);
            assertEquals("2", markerRowCell(summary, "Valoare realizata", 1), "summary A");
            assertEquals("4", markerRowCell(summary, "Valoare realizata", 2), "summary I");
            assertEquals("2", markerRowCell(summary, "Valoare realizata", 3), "summary P");
            assertEquals("30", markerRowCell(summary, "Valoare realizata", 4), "summary C");
            assertEquals("8", markerRowCell(summary, "Valoare realizata", 5), "summary h");
            // T = A + P/2 + I/2 + C/20 + h/5 = 2 + 1 + 2 + 1.5 + 1.6 = 8.10 (non-integer → 2 decimals)
            assertEquals("8.10", markerRowCell(summary, "Valoare realizata", 6), "summary T");
        }
    }

    @Test
    void rendersCCitationDetailTable() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("fizica-ff");
        snap.getItems().add(citation("My cited paper", "Phys. Rev. B", 2019, 1.5,
                citing("Citing one", "Nature", 2021), citing("Citing two", "Science", 2022)));
        // a second cited pub → fills slot II. (rows 4–6)
        snap.getItems().add(citation("Second paper", "J. Phys.", 2018, 0.5,
                citing("Another citing", "PRL", 2023)));
        snap.getTotals().put("fizica-c", 2.0);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFTable t = doc.getTables().get(19);
            // Cited slot I. (row 1): reference in cell 1, score in the last cell (4); citing rows 2/3 cell 2.
            assertTrue(cell(t, 1, 1).contains("My cited paper"), "cited I ref: " + cell(t, 1, 1));
            assertEquals("1.50", cell(t, 1, 4), "cited I score");
            assertTrue(cell(t, 2, 2).contains("Citing one"), "citing I.1");
            assertTrue(cell(t, 3, 2).contains("Citing two"), "citing I.2");
            // Cited slot II. (row 4): second cited pub + its one citing row (5); row 6 stays empty.
            assertTrue(cell(t, 4, 1).contains("Second paper"), "cited II ref");
            assertEquals("0.50", cell(t, 4, 4), "cited II score");
            assertTrue(cell(t, 5, 2).contains("Another citing"), "citing II.1");
            assertEquals("", cell(t, 6, 2), "citing II.2 empty");
            // Footer C total = Σ cited scores = 1.5 + 0.5 = 2.00
            assertTrue(markerRowText(t, "C =").contains("C = 2.00"), "footer C total: " + markerRowText(t, "C ="));
        }
    }

    private static String markerRowText(XWPFTable t, String marker) {
        for (int r = 0; r < t.getNumberOfRows(); r++) {
            for (int c = 0; c < t.getRow(r).getTableCells().size(); c++) {
                if (cell(t, r, c).contains(marker)) return cell(t, r, c);
            }
        }
        return "";
    }

    private static ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem citation(
            String title, String forum, int year, double score,
            ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem.CitingPublication... citing) {
        var cit = new ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem();
        cit.setRoleKey("fizica-c");
        cit.setPublicationTitle(title);
        cit.setPublicationForumName(forum);
        cit.setPublicationYear(year);
        cit.setScore(score);
        for (var c : citing) cit.getCitingPublications().add(c);
        return cit;
    }

    private static ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem.CitingPublication citing(
            String title, String forum, int year) {
        var c = new ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem.CitingPublication();
        c.setTitle(title); c.setForumName(forum); c.setYear(year);
        return c;
    }

    private static String cell(XWPFTable t, int row, int col) {
        XWPFTableRow r = t.getRow(row);
        XWPFTableCell c = r == null ? null : r.getCell(col);
        return c == null ? "" : c.getText().trim();
    }

    private static String markerRowCell(XWPFTable t, String marker, int col) {
        String needle = marker.replaceAll("\\s+", " ").trim();
        for (int r = 0; r < t.getNumberOfRows(); r++) {
            StringBuilder sb = new StringBuilder();
            for (XWPFTableCell c : t.getRow(r).getTableCells()) sb.append(c.getText()).append(' ');
            if (sb.toString().replaceAll("\\s+", " ").trim().contains(needle)) return cell(t, r, col);
        }
        return "(marker not found)";
    }
}
