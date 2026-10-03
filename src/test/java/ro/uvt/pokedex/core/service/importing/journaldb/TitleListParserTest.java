package ro.uvt.pokedex.core.service.importing.journaldb;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** H142 slice 4 — a title list in the shapes vendors publish it. */
class TitleListParserTest {

    private static TitleListParser.Parsed parse(String text) throws IOException {
        return TitleListParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aKbartFileGivesEachSerialWithItsIssnsAndYears() throws IOException {
        String kbart = "﻿publication_title\tprint_identifier\tonline_identifier\tdate_first_issue_online\tnum_first_vol_online"
                + "\tnum_first_issue_online\tdate_last_issue_online\tnum_last_vol_online\tnum_last_issue_online\ttitle_url\tfirst_author"
                + "\ttitle_id\tembargo_info\tcoverage_depth\tnotes\tpublisher_name\tpublication_type\n"
                + "Music Analysis\t0262-5245\t1468-2249\t1982-03-01\t1\t1\t\t\t\thttps://x\t\tma\tP3Y\tfulltext\t\tWiley\tserial\n"
                + "Old Journal\t1111-1111\t\t1950\t\t\t1999-12-31\t\t\t\t\t\t\tfulltext\t\t\tserial\n"
                + "A Monograph\t\t\t\t\t\t\t\t\t\t\t\t\tfulltext\t\t\tmonograph\n"
                + "No Identifier\t\t\t2001\t\t\t\t\t\t\t\t\t\tfulltext\t\t\tserial\n";

        TitleListParser.Parsed parsed = parse(kbart);

        assertEquals(2, parsed.rows().size());
        assertEquals(2, parsed.skipped(), "the monograph and the serial without an ISSN");
        TitleListParser.TitleRow first = parsed.rows().getFirst();
        assertEquals("Music Analysis", first.title());
        assertEquals(Set.of("02625245", "14682249"), first.issns());
        assertEquals("02625245", first.printIssn());
        assertEquals("14682249", first.onlineIssn());
        assertEquals(1982, first.from());
        assertNull(first.to(), "still covered");
        assertEquals("P3Y", first.embargo());
        assertEquals(1999, parsed.rows().get(1).to());
    }

    @Test
    void aPipeOrCommaListWithOneIssnColumnReadsToo() throws IOException {
        TitleListParser.Parsed pipe = parse("Title|ISSN|Coverage Begins|Coverage Ends\nRevista Muzica|1221-9649|1990|Present\n");
        assertEquals(Set.of("12219649"), pipe.rows().getFirst().issns());
        assertEquals(1990, pipe.rows().getFirst().from());
        assertNull(pipe.rows().getFirst().to());

        TitleListParser.Parsed csv = parse("\"Journal Title\",\"ISSN\",\"eISSN\"\n\"Studia, Musica\",\"1844-4369\",\"2065-9628\"\n");
        assertEquals("Studia, Musica", csv.rows().getFirst().title());
        assertEquals(Set.of("18444369", "20659628"), csv.rows().getFirst().issns());
    }

    @Test
    void anExcelSheetIsReadFromItsHeaderRowWhateverComesBefore() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Titles");
            sheet.createRow(0).createCell(0).setCellValue("Music Index — title list, updated monthly");
            var header = sheet.createRow(2);
            List<String> names = List.of("Title", "ISSN", "Indexing and Abstracting Start", "Indexing and Abstracting Stop");
            for (int i = 0; i < names.size(); i++) {
                header.createCell(i).setCellValue(names.get(i));
            }
            var row = sheet.createRow(3);
            row.createCell(0).setCellValue("Muzica");
            row.createCell(1).setCellValue("1221-9649");
            row.createCell(2).setCellValue("01/01/2004");
            row.createCell(3).setCellValue("12/31/2015");
            workbook.write(out);
        }

        TitleListParser.Parsed parsed = TitleListParser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(1, parsed.rows().size());
        assertEquals(2004, parsed.rows().getFirst().from());
        assertEquals(2015, parsed.rows().getFirst().to());
    }

    @Test
    void onlyEightCharacterIssnsCount() {
        assertEquals("1234567X", ro.uvt.pokedex.core.service.reporting.JournalDatabases.normalizeIssn("1234-567x"));
        assertNull(ro.uvt.pokedex.core.service.reporting.JournalDatabases.normalizeIssn("978-0-19-000000-1"));
        assertNull(ro.uvt.pokedex.core.service.reporting.JournalDatabases.normalizeIssn(""));
    }

    @Test
    void aZipOfKbartFilesReadsEveryList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("OUP_Humanities_2026.txt"));
            zip.write("publication_title\tprint_identifier\tonline_identifier\nMusic and Letters\t0027-4224\t1477-4631\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("__MACOSX/._OUP_Humanities_2026.txt"));
            zip.write(new byte[]{0, 5, 22, 7});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("Title lists of Oxford Academic journals\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OUP_Archive_2026.txt"));
            zip.write("publication_title\tprint_identifier\tonline_identifier\nEarly Music\t0306-1078\t1741-7260\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        TitleListParser.Parsed parsed = TitleListParser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(List.of("Early Music", "Music and Letters"), parsed.rows().stream().map(TitleListParser.TitleRow::title).toList(),
                "both lists, in the archive's order of names; the readme and the resource fork add nothing");
    }

    @Test
    void anHtmlTableIsReadRowByRow() throws IOException {
        String html = "<html><head><title>Music Index</title></head><body><p>Updated monthly</p>"
                + "<table><tr><th>Title</th><th>ISSN</th><th>Publication Type</th><th>Indexing and Abstracting Start</th></tr>"
                + "<tr><td><a href=\"x\">American Music</a></td><td>0734-4392</td><td>Academic Journal</td><td>01/01/1983</td></tr>"
                + "<tr><td>Music &amp; Letters</td><td>0027-4224</td><td>Academic Journal</td><td></td></tr>"
                + "<tr><td>The Strad Guide</td><td>1234-5679</td><td>Book</td><td>2001</td></tr>"
                + "</table></body></html>";

        TitleListParser.Parsed parsed = parse(html);

        assertEquals(List.of("American Music", "Music & Letters"), parsed.rows().stream().map(TitleListParser.TitleRow::title).toList());
        assertEquals(1983, parsed.rows().getFirst().from());
        assertEquals(1, parsed.skipped(), "a book is no journal");
    }

    @Test
    void theHeaderOfATextListMayFollowNotes() throws IOException {
        TitleListParser.Parsed parsed = parse("ProQuest title list\nMusic Periodicals Database, generated 2026-10-01\n\n"
                + "Title\tISSN\teISSN\tSource Type\nNotes\t0027-4631\t\tScholarly Journals\nThe Times\t0140-0460\t\tNewspapers\n");

        assertEquals(1, parsed.rows().size());
        assertEquals("Notes", parsed.rows().getFirst().title());
        assertEquals(1, parsed.skipped(), "a newspaper is no journal");
    }

    @Test
    void ebscosIssnOrIsbnColumnKeepsTheJournalsAndDropsTheBooks() throws IOException {
        // EBSCO's coverage lists (aft, hus, hsi, e5h, mft) name the column "ISSN / ISBN"
        TitleListParser.Parsed parsed = parse("Coverage Policy\tSource Type\tISSN / ISBN\tPublication Name\n"
                + "Core\tAcademic Journal\t1326-9631\tArt Monthly\n"
                + "Core\tBook\t978-0-19-000000-1\tA Book\n");

        assertEquals(List.of("Art Monthly"), parsed.rows().stream().map(TitleListParser.TitleRow::title).toList());
        assertEquals(1, parsed.skipped());
    }

    @Test
    void onlySerialsCountAsJournals() {
        assertEquals(false, TitleListParser.notSerial("serial"));
        assertEquals(false, TitleListParser.notSerial("Scholarly Journals"));
        assertEquals(false, TitleListParser.notSerial("Book Series"));
        assertEquals(false, TitleListParser.notSerial(""));
        assertEquals(true, TitleListParser.notSerial("monograph"));
        assertEquals(true, TitleListParser.notSerial("Dissertations & Theses"));
        assertEquals(true, TitleListParser.notSerial("Blogs, Podcasts, & Websites"));
    }

    @Test
    void proQuestsTabExportInsidePreInWindows1252Reads() throws IOException {
        String text = "<html><body><pre>\nMusic Periodicals Database\nAccurate as of 26 September 2026\n"
                + "Title\tEdition\tPublisher\tISSN\teISSN\tCit/Abs (combined) First\tCit/Abs (combined) Last\tPub Type\n"
                + "Revue de musicologie\t\tSoci\u00e9t\u00e9 fran\u00e7aise\t0035-1601\t1958-5632\t01-Jan-1922\tCurrent\tScholarly Journals\n"
                + "Le Monde\t\t\t0395-2037\t\t01-Jan-1990\tCurrent\tNewspapers\n"
                + "</pre></body></html>\n";

        TitleListParser.Parsed parsed = TitleListParser.parse(new ByteArrayInputStream(text.getBytes(java.nio.charset.Charset.forName("windows-1252"))));

        assertEquals(1, parsed.rows().size());
        assertEquals(Set.of("00351601", "19585632"), parsed.rows().getFirst().issns());
        assertEquals(1922, parsed.rows().getFirst().from());
        assertNull(parsed.rows().getFirst().to(), "Current: still covered");
        assertEquals(1, parsed.skipped(), "a newspaper is no journal");
        assertEquals("Soci\u00e9t\u00e9 fran\u00e7aise", TitleListParser.decode("Soci\u00e9t\u00e9 fran\u00e7aise".getBytes(
                java.nio.charset.Charset.forName("windows-1252"))), "bytes that are no UTF-8 are read as Windows-1252");
    }

    @Test
    void rilmsListWithoutAHeaderIsReadInTheOrderOfItsTable() throws IOException {
        TitleListParser.Parsed parsed = parse("core|periodical|0001-6241|1663-2427|Acta musicologica|IMS|Switzerland|1928||\n"
                + "tertiary|periodical|||128: Das Magazin der Berliner Philharmoniker|BPO|Germany|2012||\n"
                + "secondary|newspaper|0028-7806||The New Yorker|Cond\u00e9 Nast|United States|1925||\n"
                + "core|magazine|1221-9649||Muzica \"Nou\u0103\"|UCMR|Romania|1990|2001|\n");

        assertEquals(List.of("Acta musicologica", "Muzica \"Nou\u0103\""), parsed.rows().stream().map(TitleListParser.TitleRow::title).toList());
        assertEquals("16632427", parsed.rows().getFirst().onlineIssn());
        assertEquals(2001, parsed.rows().get(1).to());
        assertEquals(2, parsed.skipped(), "the journal without an ISSN, the newspaper");
    }

    @Test
    void aQuoteInsideAFieldIsText() {
        assertEquals(List.of("Le \"Courrier\" musical", "1234-5679"), TitleListParser.split("Le \"Courrier\" musical|1234-5679", '|'));
        assertEquals(List.of("Studia, Musica", "1844-4369"), TitleListParser.split("\"Studia, Musica\",1844-4369", ','));
        assertEquals(List.of("say \"hi\"", "x"), TitleListParser.split("\"say \"\"hi\"\"\",x", ','));
    }
}
