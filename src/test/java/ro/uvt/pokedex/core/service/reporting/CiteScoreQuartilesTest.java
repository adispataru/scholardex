package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiteScoreQuartilesTest {

    private static final String HEADER = "Scopus Source ID,Title,Citation Count,Scholarly Output,Percent Cited,CiteScore,SNIP,SJR,"
            + "Scopus ASJC Code (Sub-subject Area),Scopus Sub-Subject Area,Percentile,RANK,Rank Out Of,Publisher,Main Publisher,"
            + "Type,Open Access,Quartile,Top 10% (CiteScore Percentile),URL Scopus Source ID,Print ISSN,E-ISSN\n";

    @Test
    void theBestQuartileOverTheSubjectAreasAndTheNearestListWhenAYearIsMissing(@TempDir Path dir) throws Exception {
        // 2023: source 100 is Q3 in one area and Q1 in another; source 200 only Q4; a row with no quartile is skipped
        Files.writeString(dir.resolve("CiteScore 2023 per Nov 2024.csv"), HEADER
                + "100,\"Journal, with a comma\",1,1,1,1,1,1,1902,Area A,50,8,148,P,P,j,Yes,3,FALSE,u,1111-1111,\n"
                + "100,\"Journal, with a comma\",1,1,1,1,1,1,1903,Area B,95,1,148,P,P,j,Yes,1,TRUE,u,1111-1111,\n"
                + "200,Other,1,1,1,1,1,1,1902,Area A,10,140,148,P,P,j,No,4,FALSE,u,,\n"
                + "300,Unranked,1,1,1,1,1,1,1902,Area A,,,,P,P,j,No,,FALSE,u,,\n", StandardCharsets.ISO_8859_1);
        Files.writeString(dir.resolve("CiteScore 2021 per Nov 2022.csv"), HEADER
                + "100,Journal,1,1,1,1,1,1,1902,Area A,50,8,148,P,P,j,Yes,2,FALSE,u,1111-1111,\n", StandardCharsets.ISO_8859_1);
        Files.writeString(dir.resolve("ext_list_May_2026.xlsx"), "not a list");

        CiteScoreQuartiles quartiles = new CiteScoreQuartiles(dir.toString());

        assertEquals(List.of(2021, 2023), quartiles.availableYears());
        assertEquals(new CiteScoreQuartiles.Placement(1, 2023), quartiles.placement("100", 2023).orElseThrow());
        assertEquals(new CiteScoreQuartiles.Placement(2, 2021), quartiles.placement("100", 2021).orElseThrow());
        // 2022 is not loaded; 2021 and 2023 are equally near, and the earlier list wins the tie
        assertEquals(new CiteScoreQuartiles.Placement(2, 2021), quartiles.placement("100", 2022).orElseThrow());
        assertEquals(new CiteScoreQuartiles.Placement(4, 2023), quartiles.placement("200", 2024).orElseThrow());
        assertTrue(quartiles.placement("300", 2023).isEmpty(), "no quartile at all");
        assertTrue(quartiles.placement(null, 2023).isEmpty());
    }

    @Test
    void aMissingDirectoryGivesNoPlacementsAndNoError() {
        CiteScoreQuartiles quartiles = new CiteScoreQuartiles("/nowhere/at/all");
        assertTrue(quartiles.availableYears().isEmpty());
        assertEquals(Optional.empty(), quartiles.placement("100", 2023));
    }

    @Test
    void quotedCommasAndDoubledQuotesAreSplitLikeExcelWrites() {
        assertEquals(List.of("1", "a, b", "c \"d\"", ""), CiteScoreQuartiles.splitCsv("1,\"a, b\",\"c \"\"d\"\"\","));
    }
}
