package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportFormat;
import ro.uvt.pokedex.core.model.reporting.transfer.ReportInstanceSnapshot;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateDocxRenderer;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Feaa2024ReportTypeImportSupportTest {

    private Feaa2024ReportTypeImportSupport support;

    @BeforeEach
    void setUp() {
        support = new Feaa2024ReportTypeImportSupport(
                new TemplateBindingLoader(new ObjectMapper()), new TemplateDocxRenderer());
        support.loadBinding();
    }

    @Test
    void rendersArticlesCitationsAndSummaryTotals() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("feaa-2024");

        // Article: M=10, N=2, AIS=1.5 → Pi = 10·(1-(2-1)·0.1)·1.5 = 13.5
        PublicationSnapshotItem art = new PublicationSnapshotItem();
        art.setRoleKey("journal-publications");
        art.setAuthors("Pop A., Ion B.");
        art.setTitle("Market dynamics");
        art.setForumName("J. Econ");
        art.setYear(2022);
        art.setMultiplier(10);
        art.setAuthorCount(2);
        art.setForumScore(1.5);   // AIS
        art.setAuthorScore(13.5); // Pi
        snap.getItems().add(art);

        // Citation: one citing publication, AIS=2.0, Q1 → Cj=1.0
        CitationSnapshotItem cit = new CitationSnapshotItem();
        cit.setRoleKey("citations-per-publication");
        cit.setPublicationTitle("Market dynamics");
        CitationSnapshotItem.CitingPublication citing = new CitationSnapshotItem.CitingPublication();
        citing.setAuthors("Citing X");
        citing.setTitle("Cites market dynamics");
        citing.setForumName("Some Econ Journal");
        citing.setYear(2023);
        citing.setScore(2.0);       // AIS (base/journal score)
        citing.setQuartile("Q1");
        citing.setAuthorScore(1.0); // Cj
        cit.getCitingPublications().add(citing);
        snap.getItems().add(cit);

        snap.getTotals().put("journal-publications", 13.5);       // P
        snap.getTotals().put("citations-per-publication", 1.0);   // C

        byte[] bytes = support.render(snap, ReportFormat.DOCX);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFTable articles = doc.getTables().get(3);
            assertTrue(cell(articles, 1, 1).contains("Market dynamics"), "article reference: " + cell(articles, 1, 1));
            assertEquals("10", cell(articles, 1, 2), "M");
            assertEquals("2", cell(articles, 1, 3), "N");
            assertEquals("1.50", cell(articles, 1, 4), "AIS");
            assertEquals("13.50", cell(articles, 1, 5), "Pi");
            // Articles total (P) in the dedicated value cell of the total row.
            assertEquals("13.50", lastRowCell(articles, 2), "P total");

            XWPFTable citations = doc.getTables().get(8);
            assertTrue(cell(citations, 1, 1).contains("Cites market dynamics"), "citation reference");
            assertEquals("2", cell(citations, 1, 2), "citation AIS");
            assertEquals("Q1", cell(citations, 1, 3), "quartile");
            assertEquals("1", cell(citations, 1, 4), "Cj");
            assertEquals("1", lastRowCell(citations, 2), "C total");

            // Summary: obtained P / C / S=P+C in the 4th cell of each row.
            XWPFTable summary = doc.getTables().get(9);
            assertEquals("13.50", markerRowCell(summary, "Punctaj publica", 3), "summary P");
            assertEquals("1", markerRowCell(summary, "Punctaj cit", 3), "summary C");
            assertEquals("14.50", markerRowCell(summary, "Punctaj final", 3), "summary S=P+C");
            // Core/Info count: the single article has M=10 (Core Economics) and AIS>0 → 1.
            assertEquals("1", markerRowCell(summary, "Număr articole ISI", 3), "Core/Info article count");
        }
    }

    @Test
    void groupsBooksIntoSlotsButExcludesThemFromP() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("feaa-2024");
        // One article (P_articles = 6.0).
        PublicationSnapshotItem art = new PublicationSnapshotItem();
        art.setRoleKey("journal-publications");
        art.setTitle("An econ paper"); art.setMultiplier(10); art.setAuthorCount(1);
        art.setForumScore(0.6); art.setAuthorScore(6.0);
        snap.getItems().add(art);
        // One book per slot: 0.5→7, 0.25→8, 0.2→9, 0.1→10 (each N=1 so Pi == coefficient).
        snap.getItems().add(book("Carte intl", 0.5));   // slot 7
        snap.getItems().add(book("Capitol intl", 0.25)); // slot 8
        snap.getItems().add(book("Carte nat", 0.2));     // slot 9
        snap.getItems().add(book("Proceedings", 0.1));   // slot 10
        snap.getTotals().put("journal-publications", 6.0);
        snap.getTotals().put("citations-per-publication", 0.0);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // Each slot table (4..7) carries its book + per-slot total in the value cell.
            assertTrue(cell(doc.getTables().get(4), 1, 1).contains("Carte intl"), "slot7 carte intl");
            assertEquals("0.50", lastRowCell(doc.getTables().get(4), 2), "slot7 total");
            assertTrue(cell(doc.getTables().get(5), 1, 1).contains("Capitol intl"), "slot8 capitol intl");
            assertEquals("0.25", lastRowCell(doc.getTables().get(5), 2), "slot8 total");
            assertTrue(cell(doc.getTables().get(6), 1, 1).contains("Carte nat"), "slot9 carte nat");
            assertEquals("0.20", lastRowCell(doc.getTables().get(6), 2), "slot9 total");
            assertTrue(cell(doc.getTables().get(7), 1, 1).contains("Proceedings"), "slot10 proceedings");
            assertEquals("0.10", lastRowCell(doc.getTables().get(7), 2), "slot10 total");
            // Books are shown in the slots but NOT counted: P = articles only (6), S = P + C(0) = 6.
            XWPFTable summary = doc.getTables().get(9);
            assertEquals("6", markerRowCell(summary, "Punctaj publica", 3), "P = articles only (books excluded)");
            assertEquals("6", markerRowCell(summary, "Punctaj final", 3), "S = P + C");
        }
    }

    @Test
    void coreInfoCountExcludesSocialScienceM6() throws Exception {
        ReportInstanceSnapshot snap = new ReportInstanceSnapshot();
        snap.setReportTypeKey("feaa-2024");
        snap.getItems().add(coreArticle("Core Economics paper", 10)); // counts
        snap.getItems().add(coreArticle("Infoeconomics paper", 8));   // counts
        snap.getItems().add(coreArticle("Social Science paper", 6));  // M=6 → excluded
        snap.getTotals().put("journal-publications", 3.0);

        byte[] bytes = support.render(snap, ReportFormat.DOCX);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals("2", markerRowCell(doc.getTables().get(9), "Număr articole ISI", 3),
                    "only M>=8 (Core/Info) articles counted");
        }
    }

    private PublicationSnapshotItem coreArticle(String title, int multiplier) {
        PublicationSnapshotItem a = new PublicationSnapshotItem();
        a.setRoleKey("journal-publications");
        a.setTitle(title); a.setMultiplier(multiplier); a.setAuthorCount(1);
        a.setForumScore(1.0); a.setAuthorScore(multiplier * 1.0);
        return a;
    }

    private PublicationSnapshotItem book(String title, double coefficient) {
        PublicationSnapshotItem b = new PublicationSnapshotItem();
        b.setRoleKey("book-publications");
        b.setTitle(title);
        b.setAuthors("Author A");
        b.setAuthorCount(1);
        b.setForumScore(coefficient);   // Pi coefficient
        b.setAuthorScore(coefficient);  // Pi = coefficient / N (N=1)
        return b;
    }

    private static String cell(XWPFTable t, int row, int col) {
        XWPFTableRow r = t.getRow(row);
        XWPFTableCell c = r == null ? null : r.getCell(col);
        return c == null ? "" : c.getText().trim();
    }

    private static String lastRowCell(XWPFTable t, int col) {
        return cell(t, t.getNumberOfRows() - 1, col);
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
