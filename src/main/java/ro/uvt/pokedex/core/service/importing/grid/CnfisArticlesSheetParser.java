package ro.uvt.pokedex.core.service.importing.grid;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import ro.uvt.pokedex.core.service.reporting.JournalDatabases;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H142 slice 7 — reads a person's CNFIS Anexa 5 ("Fişa individuală de articole şi brevete", IC2.3): one row per article
 * or patent with the reference year (B), the title (C), the DOI, the WoS code and the patent code, then the journal or
 * volume and its ISSN / ISBN columns. The marks that follow ("Încadrare articole": Nature/Science, ISI Q1 …) are the
 * person's own classification and are not read — the journal's lists decide (H145). A patent row is skipped.
 */
public final class CnfisArticlesSheetParser {

    /** One article: the ISSNs normalised (eight characters, no hyphen), print first. */
    public record ArticleRow(Integer year, String title, String doi, String wosCode, String journal, List<String> issns,
                             List<String> isbns) {
    }

    public record ParsedArticles(List<ArticleRow> rows, int patents) {
    }

    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/\\S+");
    private static final Pattern ISBN = Pattern.compile("(?:97[89][- ]?)?(?:\\d[- ]?){9}[\\dXx]");

    private CnfisArticlesSheetParser() {
    }

    public static boolean looksLikeArticlesSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (header(sheet) != null) {
                return true;
            }
        }
        return false;
    }

    public static ParsedArticles parse(Workbook workbook) {
        List<ArticleRow> rows = new ArrayList<>();
        int patents = 0;
        for (Sheet sheet : workbook) {
            Columns c = header(sheet);
            if (c == null) continue;
            for (int r = c.headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String first = MusicGridLayout.normalize(MusicGridParser.text(row.getCell(0)));
                if (first.startsWith("total")) break;
                String title = MusicGridParser.text(row.getCell(c.title)).replaceAll("\\s+", " ").trim();
                String yearCell = MusicGridParser.text(row.getCell(c.title - 1)).trim();
                if (title.isEmpty() || isGuidance(title, yearCell)) continue;
                String patent = c.patent < 0 ? "" : MusicGridParser.text(row.getCell(c.patent)).trim();
                if (!patent.isEmpty()) {
                    patents++;
                    continue;
                }
                Integer year = yearCell.matches("(19|20)\\d{2}(\\.0)?") ? Integer.valueOf(yearCell.substring(0, 4))
                        : CnfisSheets.lastYear(title);
                String doiCell = MusicGridParser.text(row.getCell(c.doi)).trim();
                Matcher doi = DOI.matcher(doiCell.replaceAll("(?i)https?://(dx\\.)?doi\\.org/", ""));
                String journal = c.journal < 0 ? "" : MusicGridParser.text(row.getCell(c.journal)).replaceAll("\\s+", " ").trim();
                Set<String> issns = new LinkedHashSet<>();
                List<String> isbns = new ArrayList<>();
                for (int col = c.firstIdentifier; col <= c.lastIdentifier; col++) {
                    String v = MusicGridParser.text(row.getCell(col)).trim();
                    for (String part : v.split("[;,/\\s]+(?=\\S)")) {
                        String issn = JournalDatabases.normalizeIssn(part);
                        if (issn != null && part.replaceAll("[^0-9Xx]", "").length() == 8) {
                            issns.add(issn);
                        }
                    }
                    Matcher isbn = ISBN.matcher(v);
                    while (isbn.find()) {
                        if (isbn.group().replaceAll("[^0-9Xx]", "").length() >= 10) isbns.add(isbn.group().trim());
                    }
                }
                rows.add(new ArticleRow(year, title, doi.find() ? doi.group().replaceAll("[.,;)]+$", "") : null,
                        c.wos < 0 ? null : blankToNull(MusicGridParser.text(row.getCell(c.wos)).trim()),
                        journal.isEmpty() ? null : journal, List.copyOf(issns), List.copyOf(isbns)));
            }
        }
        return new ParsedArticles(rows, patents);
    }

    private static boolean isGuidance(String title, String yearCell) {
        String t = MusicGridLayout.normalize(title);
        return t.startsWith("se completeaza") || t.startsWith("titlul articolului") || t.equals("c")
                || MusicGridLayout.normalize(yearCell).startsWith("se selecteaza");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** Where the columns are, from the header row naming the title ("Titlul articolului / Titlul brevetului"). */
    private record Columns(int headerRow, int title, int doi, int wos, int patent, int journal, int firstIdentifier,
                           int lastIdentifier) {
    }

    private static Columns header(Sheet sheet) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 25); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int title = -1, doi = -1, journal = -1, identifiers = -1, marks = Integer.MAX_VALUE;
            for (Cell cell : row) {
                String h = MusicGridLayout.normalize(MusicGridParser.text(cell));
                int col = cell.getColumnIndex();
                if (h.startsWith("titlul articolului")) title = col;
                else if (h.startsWith("doi articol") || h.startsWith("doi")) doi = col;
                else if (h.startsWith("denumirea jurnalului")) journal = col;
                else if (h.startsWith("cod issn")) identifiers = col;
                else if (h.startsWith("nature") || h.startsWith("isi q1") || h.startsWith("incadrare")) marks = Math.min(marks, col);
            }
            if (title < 1) continue;
            // the guidance row under the header names the three code columns: DOI, WoS, patent
            int wos = -1, patent = -1;
            Row guidance = sheet.getRow(r + 1);
            if (guidance != null) {
                for (Cell cell : guidance) {
                    String h = MusicGridLayout.normalize(MusicGridParser.text(cell));
                    if (h.startsWith("cod doi")) doi = cell.getColumnIndex();
                    else if (h.startsWith("cod wos")) wos = cell.getColumnIndex();
                    else if (h.startsWith("cod brevet")) patent = cell.getColumnIndex();
                    else if (journal < 0 && h.startsWith("se completeaza denumirea")) journal = cell.getColumnIndex();
                }
            }
            if (doi < 0) doi = title + 1;
            int first = identifiers >= 0 ? identifiers : (journal >= 0 ? journal + 1 : title + 5);
            int last = marks == Integer.MAX_VALUE ? first + 3 : marks - 1;
            return new Columns(r, title, doi, wos, patent, journal, first, Math.max(first, last));
        }
        return null;
    }
}
