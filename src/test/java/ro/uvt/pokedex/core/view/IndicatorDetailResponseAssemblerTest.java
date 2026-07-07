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
    void titleJoinResolvesPublicationAndForumIdsFromLivePublicationBeans() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Paper A", score(5.0, 3.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView pub =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView() { };
        pub.setId("spub_1");
        pub.setTitle("Paper A");
        pub.setForum("sforum_9");
        graph.put("publications", List.of(pub));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));

        IndicatorDetailResponseAssembler.ScoredItem item = resp.items().getFirst();
        assertEquals("spub_1", item.publicationId());
        assertEquals("sforum_9", item.forumId());
    }

    @Test
    void titleJoinResolvesIdsFromCachedMapFormPublications() {
        // LATEST blobs round-trip publications as plain property maps, not beans.
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Paper A", score(5.0, 3.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        graph.put("publications", List.of(
                Map.of("id", "spub_1", "title", "Paper A", "forumId", "sforum_9")));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));

        IndicatorDetailResponseAssembler.ScoredItem item = resp.items().getFirst();
        assertEquals("spub_1", item.publicationId());
        assertEquals("sforum_9", item.forumId());
    }

    @Test
    void duplicateTitlesAndMissingPublicationsListLeaveItemsUnlinked() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Paper A", score(5.0, 3.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        // Two publications share the title → ambiguous → no link.
        graph.put("publications", List.of(
                Map.of("id", "spub_1", "title", "Paper A"),
                Map.of("id", "spub_2", "title", "Paper A")));

        IndicatorDetailResponse ambiguous = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));
        assertEquals(null, ambiguous.items().getFirst().publicationId());
        assertEquals(null, ambiguous.items().getFirst().forumId());

        graph.remove("publications");
        IndicatorDetailResponse absent = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));
        assertEquals(null, absent.items().getFirst().publicationId());
    }

    @Test
    void citationsOutputModeLinksTheResearchersOwnCitedPublications() {
        Map<String, Object> citing = new LinkedHashMap<>();
        citing.put("total", score(5.0, 2.0, 2024, null));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Cited Pub", citing);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "citations");
        graph.put("scores", scores);
        graph.put("publications", List.of(Map.of("id", "spub_7", "title", "Cited Pub", "forum", "sforum_3")));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));

        IndicatorDetailResponseAssembler.ScoredItem item = resp.items().getFirst();
        assertEquals("spub_7", item.publicationId());
        // forumId falls back to the 'forum' property name when 'forumId' is absent in map form.
        assertEquals("sforum_3", item.forumId());
        // Citing papers inside the drilldown modal stay unlinked (third-party publications).
        CitationDetailResponse citations = IndicatorDetailResponseAssembler.buildCitations(dto(graph, 5.0), "Cited Pub");
        assertEquals(0, citations.citations().size());
    }

    @Test
    void excludedItemsAreAppendedWithTheirZeroReasonFromBeanAndMapForms() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Scored Paper", score(5.0, 3.0, 2022, "Q1"));
        Score excludedBean = score(0.0, 0.0, 2021, null);
        excludedBean.getScoringInfo().put("zeroReason", "EXCLUDED_VENUE");
        Map<String, Object> excluded = new LinkedHashMap<>();
        excluded.put("WSEAS Paper", excludedBean);
        // Cached LATEST form: a plain property map with nested scoringInfo.
        excluded.put("Role Filtered Paper", Map.of(
                "score", 0.0, "authorScore", 0.0, "year", 2020,
                "scoringInfo", Map.of("zeroReason", "ROLE_FILTERED")));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        graph.put("excludedItems", excluded);
        graph.put("publications", List.of(
                Map.of("id", "spub_1", "title", "WSEAS Paper", "forumId", "sforum_2")));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0));

        assertEquals(3, resp.items().size());
        IndicatorDetailResponseAssembler.ScoredItem scored = resp.items().stream()
                .filter(i -> i.key().equals("Scored Paper")).findFirst().orElseThrow();
        assertEquals(null, scored.zeroReason());
        IndicatorDetailResponseAssembler.ScoredItem wseas = resp.items().stream()
                .filter(i -> i.key().equals("WSEAS Paper")).findFirst().orElseThrow();
        assertEquals("EXCLUDED_VENUE", wseas.zeroReason());
        assertEquals(0.0, wseas.authorScore());
        // Excluded items still get the title-join links.
        assertEquals("spub_1", wseas.publicationId());
        IndicatorDetailResponseAssembler.ScoredItem roleFiltered = resp.items().stream()
                .filter(i -> i.key().equals("Role Filtered Paper")).findFirst().orElseThrow();
        assertEquals("ROLE_FILTERED", roleFiltered.zeroReason());
    }

    @Test
    void inMapZeroReasonSurfacesForFormulaZeroedScores() {
        // FEE_JOURNAL zeros keep positive venue points, so they stay IN the scores map (not excludedItems);
        // the assembler must surface their scoringInfo reason too. In-map scores are always Score beans:
        // IndicatorPayloadSerializer.normalizeScore rehydrates cached LATEST maps back into beans.
        Score feeBean = score(0.0, 4.0, 2026, "Q1");
        feeBean.getScoringInfo().put("zeroReason", "FEE_JOURNAL");
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Gold OA Paper", feeBean);
        scores.put("Counted Paper", score(4.0, 4.0, 2026, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 4.0));

        IndicatorDetailResponseAssembler.ScoredItem fee = resp.items().stream()
                .filter(i -> i.key().equals("Gold OA Paper")).findFirst().orElseThrow();
        assertEquals("FEE_JOURNAL", fee.zeroReason());
        IndicatorDetailResponseAssembler.ScoredItem counted = resp.items().stream()
                .filter(i -> i.key().equals("Counted Paper")).findFirst().orElseThrow();
        assertEquals(null, counted.zeroReason());
    }

    @Test
    void forumNameResolverFillsForumNameForLinkedItems() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Paper A", score(5.0, 3.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "publications");
        graph.put("scores", scores);
        graph.put("publications", List.of(
                Map.of("id", "spub_1", "title", "Paper A", "forumId", "sforum_9")));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0),
                id -> "sforum_9".equals(id) ? "Jmir Formative Research" : null);

        assertEquals("Jmir Formative Research", resp.items().getFirst().forumName());
        // The resolver-less overload leaves the name unset.
        assertEquals(null, IndicatorDetailResponseAssembler.buildDetail(dto(graph, 5.0))
                .items().getFirst().forumName());
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
