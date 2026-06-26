package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
