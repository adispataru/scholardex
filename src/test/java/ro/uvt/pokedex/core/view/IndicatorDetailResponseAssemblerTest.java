package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.view.IndicatorDetailResponseAssembler.CitationDetailResponse;
import ro.uvt.pokedex.core.view.IndicatorDetailResponseAssembler.IndicatorDetailResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndicatorDetailResponseAssemblerTest {

    private Score score(double authorScore, double forumScore, int year, String quarter) {
        Score s = new Score();
        s.setAuthorScore(authorScore);
        s.setScore(forumScore);
        s.setYear(year);
        s.setQuarter(quarter);
        s.setCoreRankingEquivalent("A");
        s.setScoringSource("WOS");
        return s;
    }

    private IndicatorApplyResultDto dto(Map<String, Object> rawGraph, double total) {
        return new IndicatorApplyResultDto(
                "result-1", "ind-1", "view",
                rawGraph,
                new IndicatorApplyResultDto.Summary(total, null, List.of("Q1"), List.of(2)),
                IndicatorApplyResultDto.Source.COMPUTED,
                null, Instant.parse("2026-06-14T10:00:00Z"), 3);
    }

    @Test
    void buildDetailShowsCategorizedItemsIncludingFormulaZeroedAndSkipsTotal() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Paper A", score(5.0, 3.0, 2022, "Q1"));   // author > 0 → shown
        scores.put("Paper B", score(0.0, 0.0, 2021, null));   // not categorized (base 0) → dropped
        scores.put("Paper C", score(0.0, 2.0, 2020, "Q2"));   // categorized (forum > 0), formula 0 → shown
        scores.put("total", score(7.0, 5.0, 0, null));         // aggregate key → skipped, not an item
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));

        assertEquals("publications", resp.outputMode());
        // Paper A (author>0) and Paper C (categorized but formula-zeroed) shown; Paper B and total dropped.
        assertEquals(2, resp.items().size());
        IndicatorDetailResponseAssembler.ScoredItem paperC = resp.items().stream()
                .filter(i -> i.key().equals("Paper C")).findFirst().orElseThrow();
        assertEquals(0.0, paperC.authorScore());   // shown despite zero author score
        assertEquals(2.0, paperC.forumScore());     // because it was categorized (positive base score)
        assertEquals(0, resp.items().stream().filter(i -> i.key().equals("Paper B")).count());
        assertEquals(0, resp.items().stream().filter(i -> i.key().equals("total")).count());
    }

    @Test
    void buildCitationsAggregatesCitingPapersForOnePublication() {
        Map<String, Object> citing = new LinkedHashMap<>();
        citing.put("Citing 1", score(2.0, 1.0, 2023, "Q2"));
        citing.put("Citing 2", score(3.0, 1.0, 2024, "Q1"));
        citing.put("total", score(5.0, 2.0, 2024, null)); // 'total' entry is skipped
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Cited Pub", citing);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "citations");
        graph.put("scores", scores);

        CitationDetailResponse resp = IndicatorDetailResponseAssembler.buildCitations(dto(graph, 5.0), "Cited Pub");

        assertEquals("Cited Pub", resp.pubTitle());
        assertEquals(2, resp.citations().size());          // 'total' excluded
        assertEquals(5.0, resp.totalScore());              // 2.0 + 3.0
        // sorted by authorScore desc
        assertEquals("Citing 2", resp.citations().getFirst().key());
    }
}
