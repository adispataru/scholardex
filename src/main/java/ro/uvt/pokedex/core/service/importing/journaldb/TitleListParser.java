package ro.uvt.pokedex.core.service.importing.journaldb;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.jsoup.parser.Parser;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ro.uvt.pokedex.core.service.reporting.JournalDatabases;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * H142 slice 4 — reads a database's title list, whatever shape the vendor publishes it in: KBART (the librarians'
 * standard, tab-separated with fixed column names), another delimited text (tab, pipe, semicolon or comma), an HTML
 * table, an Excel workbook (.xls, or .xlsx read as a stream, so a large one does not fill the memory), or a ZIP of any
 * of these (OUP publishes its KBART files zipped). Columns are recognised by their header, which may follow a few
 * lines of notes: the title, the print and the online ISSN (or one ISSN column), the first and last year covered, an
 * embargo or moving wall, the kind of publication. A row without an ISSN is skipped (a list is matched by ISSN only),
 * and so is a row that is no serial (a book, a newspaper, a report, a thesis, a recording, a website).
 */
public final class TitleListParser {

    /** One journal of a list: its ISSNs normalised ({@link JournalDatabases#normalizeIssn}), print and online first. */
    public record TitleRow(String title, String printIssn, String onlineIssn, Set<String> issns, Integer from, Integer to,
                           String embargo) {
    }

    public record Parsed(List<TitleRow> rows, int skipped) {
    }

    private static final List<String> TITLE = List.of("publication_title", "title", "journal title", "publication title",
            "publication name", "journal", "journal name", "source title", "full title");
    private static final List<String> PRINT_ISSN = List.of("print_identifier", "issn", "print issn", "pissn", "p-issn",
            "issn (print)", "issn print", "issn-print");
    private static final List<String> ONLINE_ISSN = List.of("online_identifier", "eissn", "e-issn", "online issn",
            "issn (online)", "issn online", "electronic issn", "issn-online", "eissn/isbn");
    private static final List<String> FROM = List.of("date_first_issue_online", "indexing and abstracting start",
            "indexing start", "full text start", "full text first", "coverage begins", "coverage start", "first year",
            "start year", "start date", "begin date", "coverage from");
    private static final List<String> TO = List.of("date_last_issue_online", "indexing and abstracting stop",
            "indexing stop", "full text stop", "full text last", "coverage ends", "coverage end", "last year", "end year",
            "end date", "stop date", "coverage to");
    private static final List<String> EMBARGO = List.of("embargo_info", "embargo", "full text delay (months)",
            "full text delay(months)", "full text delay", "embargo days", "moving wall");
    private static final List<String> TYPE = List.of("publication_type", "publication type", "source type",
            "resource type", "content type", "type");
    /** Kinds of publication that are no serial, as the lists name them (KBART, ProQuest, EBSCO, RILM). */
    private static final List<String> NOT_SERIAL = List.of("monograph", "book", "newspaper", "newswire", "wire feed",
            "report", "dissertation", "thesis", "theses", "video", "audio", "podcast", "blog", "web");

    /** How far down a header row is looked for. */
    private static final int HEADER_SEARCH_ROWS = 50;
    private static final char[] DELIMITERS = {'\t', '|', ';', ','};
    private static final Pattern YEAR = Pattern.compile("(1[5-9]|20)\\d{2}");
    private static final Pattern ROW_END = Pattern.compile("(?i)</tr\\s*>");
    private static final Pattern ROW_START = Pattern.compile("(?i)<tr\\b");
    private static final Pattern CELL_START = Pattern.compile("(?i)<t[dh]\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");

    private TitleListParser() {
    }

    public static Parsed parse(InputStream input) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input);
        in.mark(8);
        byte[] magic = in.readNBytes(4);
        in.reset();
        if (magic.length == 4 && magic[0] == 'P' && magic[1] == 'K') {
            return parseZip(in);
        }
        if (magic.length == 4 && (magic[0] & 0xFF) == 0xD0 && (magic[1] & 0xFF) == 0xCF) {
            return parseWorkbook(in);
        }
        return parseText(in);
    }

    // ── shapes ──────────────────────────────────────────────────────────────

    /** An .xlsx workbook, or an archive of lists (every entry is read; one that is no list adds nothing). */
    private static Parsed parseZip(InputStream in) throws IOException {
        Path file = Files.createTempFile("title-list-", ".zip");
        try {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                if (zip.getEntry("[Content_Types].xml") != null) {
                    return parseXlsx(file);
                }
                List<? extends ZipEntry> entries = zip.stream()
                        .filter(e -> !e.isDirectory() && !e.getName().startsWith("__MACOSX/"))
                        .sorted(Comparator.comparing(ZipEntry::getName)).toList();
                List<TitleRow> rows = new ArrayList<>();
                int skipped = 0;
                for (ZipEntry entry : entries) {
                    try (InputStream entryIn = zip.getInputStream(entry)) {
                        Parsed parsed = parse(entryIn);
                        rows.addAll(parsed.rows());
                        skipped += parsed.skipped();
                    }
                }
                return new Parsed(rows, skipped);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** An .xlsx workbook, first sheet, read as a stream of rows. */
    private static Parsed parseXlsx(Path file) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            Iterator<InputStream> sheets = reader.getSheetsData();
            Sink sink = new Sink();
            if (!sheets.hasNext()) {
                return sink.parsed();
            }
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            try (InputStream sheet = sheets.next()) {
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null, strings,
                        new XSSFSheetXMLHandler.SheetContentsHandler() {
                            private final List<String> cells = new ArrayList<>();

                            @Override
                            public void startRow(int rowNum) {
                                cells.clear();
                            }

                            @Override
                            public void endRow(int rowNum) {
                                sink.accept(new ArrayList<>(cells));
                            }

                            @Override
                            public void cell(String reference, String value, XSSFComment comment) {
                                int column = reference == null ? cells.size() : new CellReference(reference).getCol();
                                while (cells.size() < column) {
                                    cells.add("");
                                }
                                cells.add(value == null ? "" : value);
                            }
                        }, new DataFormatter(Locale.ROOT), false));
                parser.parse(new InputSource(sheet));
            }
            return sink.parsed();
        } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
            throw new IOException("unreadable workbook: " + e.getMessage(), e);
        }
    }

    /** An .xls workbook (the binary format: small enough to read whole), first sheet. */
    private static Parsed parseWorkbook(InputStream in) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Sink sink = new Sink();
            for (Row r : workbook.getSheetAt(0)) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, r.getLastCellNum()); c++) {
                    Cell cell = r.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                sink.accept(cells);
            }
            return sink.parsed();
        }
    }

    /** Delimited text, or an HTML table (an .htm list, or an .xls that is a web page). */
    private static Parsed parseText(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1 << 16);
        int first;
        do {
            first = reader.read();
        } while (first == 0xFEFF || (first != -1 && Character.isWhitespace(first)));
        reader.reset();
        if (first == '<') {
            return parseHtml(reader);
        }
        Sink sink = new Sink();
        Character delimiter = null;
        int searched = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.replace("﻿", "");
            if (line.isBlank()) {
                continue;
            }
            if (delimiter == null) {
                if (searched++ >= HEADER_SEARCH_ROWS) {
                    break;
                }
                for (char d : DELIMITERS) {
                    List<String> cells = split(line, d);
                    if (cells.size() > 1 && Columns.of(cells).recognised()) {
                        delimiter = d;
                        sink.accept(cells);
                        break;
                    }
                }
                continue;
            }
            sink.accept(split(line, delimiter));
        }
        return sink.parsed();
    }

    /** The rows of an HTML table, one {@code <tr>} at a time. */
    private static Parsed parseHtml(BufferedReader reader) {
        Sink sink = new Sink();
        try (Scanner scanner = new Scanner(reader).useDelimiter(ROW_END)) {
            while (scanner.hasNext()) {
                String chunk = scanner.next();
                Matcher row = ROW_START.matcher(chunk);
                if (!row.find()) {
                    continue;
                }
                String tr = chunk.substring(row.start());
                Matcher cell = CELL_START.matcher(tr);
                List<String> cells = new ArrayList<>();
                int contentStart = -1;
                while (cell.find()) {
                    if (contentStart >= 0) {
                        cells.add(text(tr.substring(contentStart, cell.start())));
                    }
                    contentStart = cell.end();
                }
                if (contentStart >= 0) {
                    cells.add(text(tr.substring(contentStart)));
                    sink.accept(cells);
                }
            }
        }
        return sink.parsed();
    }

    private static String text(String html) {
        String plain = Parser.unescapeEntities(TAG.matcher(html).replaceAll(" "), false);
        return plain.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    // ── cells ───────────────────────────────────────────────────────────────

    /** Splits a delimited line, honouring double quotes (CSV style). */
    static List<String> split(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                out.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString().trim());
        return out;
    }

    static Integer year(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = YEAR.matcher(value);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    static boolean notSerial(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.startsWith("book series")) {
            return false;
        }
        return NOT_SERIAL.stream().anyMatch(t::startsWith);
    }

    /** Rows in order: the header (looked for in the first rows), then the journals. */
    private static final class Sink {
        private Columns columns;
        private int searched;
        private final List<TitleRow> rows = new ArrayList<>();
        private int skipped;

        void accept(List<String> cells) {
            if (columns == null) {
                if (searched++ < HEADER_SEARCH_ROWS) {
                    Columns candidate = Columns.of(cells);
                    if (candidate.recognised()) {
                        columns = candidate;
                    }
                }
                return;
            }
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                return;
            }
            TitleRow row = columns.row(cells);
            if (row == null) {
                skipped++;
            } else {
                rows.add(row);
            }
        }

        Parsed parsed() {
            return new Parsed(rows, skipped);
        }
    }

    /** Where each recognised column sits in the header. */
    private record Columns(int title, int print, int online, int from, int to, int embargo, int type) {

        static Columns of(List<String> header) {
            Map<String, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i) == null ? "" : header.get(i).replace("﻿", "").trim()
                        .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                index.putIfAbsent(name, i);
            }
            return new Columns(find(index, TITLE), find(index, PRINT_ISSN), find(index, ONLINE_ISSN), find(index, FROM),
                    find(index, TO), find(index, EMBARGO), find(index, TYPE));
        }

        private static int find(Map<String, Integer> index, List<String> names) {
            for (String name : names) {
                Integer i = index.get(name);
                if (i != null) {
                    return i;
                }
            }
            return -1;
        }

        boolean recognised() {
            return print >= 0 || online >= 0;
        }

        TitleRow row(List<String> cells) {
            if (type >= 0 && notSerial(cell(cells, type))) {
                return null;
            }
            List<String> print = issns(cell(cells, this.print));
            List<String> online = issns(cell(cells, this.online));
            Set<String> issns = new LinkedHashSet<>(print);
            issns.addAll(online);
            if (issns.isEmpty()) {
                return null;
            }
            String embargoText = cell(cells, embargo);
            return new TitleRow(cell(cells, title), print.isEmpty() ? null : print.getFirst(),
                    online.isEmpty() ? null : online.getFirst(), issns, year(cell(cells, from)), year(cell(cells, to)),
                    embargoText.isBlank() ? null : embargoText);
        }

        private static List<String> issns(String value) {
            List<String> out = new ArrayList<>();
            for (String part : value.split("[;,/ ]+")) {
                String issn = JournalDatabases.normalizeIssn(part);
                if (issn != null && !out.contains(issn)) {
                    out.add(issn);
                }
            }
            return out;
        }

        private static String cell(List<String> cells, int i) {
            return i < 0 || i >= cells.size() || cells.get(i) == null ? "" : cells.get(i).trim();
        }
    }
}
