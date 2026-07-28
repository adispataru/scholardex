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
    void indicatorNameAndOutputTypeReadBothBeanAndSnapshotMapForms() {
        // Live compute puts the Indicator bean in the graph; a persisted-snapshot round-trip stores it
        // as a plain map. Both must surface indicatorName/outputType (the snapshot read-through serves
        // the map form on every fingerprint-fresh drilldown).
        ro.uvt.pokedex.core.model.reporting.Indicator bean = new ro.uvt.pokedex.core.model.reporting.Indicator();
        bean.setName("Info_B_Conferințe 2026");
        Map<String, Object> beanGraph = new LinkedHashMap<>();
        beanGraph.put("indicator", bean);
        beanGraph.put("scores", new LinkedHashMap<>());
        var beanDetail = IndicatorDetailResponseAssembler.buildDetail(dto(beanGraph, 0.0), id -> null);
        org.junit.jupiter.api.Assertions.assertEquals("Info_B_Conferințe 2026", beanDetail.indicatorName());

        Map<String, Object> mapGraph = new LinkedHashMap<>();
        mapGraph.put("indicator", Map.of("name", "Info_B_Conferințe 2026", "outputType", "PUBLICATIONS"));
        mapGraph.put("scores", new LinkedHashMap<>());
        var mapDetail = IndicatorDetailResponseAssembler.buildDetail(dto(mapGraph, 0.0), id -> null);
        org.junit.jupiter.api.Assertions.assertEquals("Info_B_Conferințe 2026", mapDetail.indicatorName());
        org.junit.jupiter.api.Assertions.assertEquals("PUBLICATIONS", mapDetail.outputType());
    }

    @Test
    void feeJournalAndGateCausesFlowFromBothBeanAndSnapshotMapForms() {
        // Bean form (live compute): Score with scoringInfo entries.
        Score bean = score(0.0, 4.0, 2022, "Q2");
        bean.getScoringInfo().put("feeJournal", Boolean.TRUE);
        bean.getScoringInfo().put("zeroReason", "MULTIPLE_GATES");
        bean.getScoringInfo().put("gateCauses", "FEE_JOURNAL,SCORE_BELOW_FORMULA_THRESHOLD");
        Map<String, Object> beanGraph = new LinkedHashMap<>();
        beanGraph.put("scores", new LinkedHashMap<>(Map.of("APC Paper", bean)));
        var beanItem = IndicatorDetailResponseAssembler.buildDetail(dto(beanGraph, 0.0), id -> null)
                .items().get(0);
        org.junit.jupiter.api.Assertions.assertTrue(beanItem.feeJournal());
        org.junit.jupiter.api.Assertions.assertEquals("FEE_JOURNAL,SCORE_BELOW_FORMULA_THRESHOLD",
                beanItem.gateCauses());

        // The persisted round-trip form is covered by IndicatorPayloadSerializerTest: deserialize()
        // rebuilds Score BEANS (the bean branch above), and scoringInfo must survive verbatim.
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
    void citationsOutputModeShowsFormulaZeroedCitingPapersMutedWithTheirGateReason() {
        // A citing paper the 2026 FEE_JOURNAL/NOT_TOP_RANKED gates zeroed keeps a positive forum score
        // (it was correctly categorized) — the drilldown modal must show it muted with the reason instead
        // of silently dropping it, mirroring the publications table's excludedItems treatment.
        Score feeGated = score(0.0, 3.0, 2024, "Q2");
        feeGated.getScoringInfo().put("zeroReason", "FEE_JOURNAL");
        Score notCategorized = score(0.0, 0.0, 2023, null); // never categorized at all — stays dropped
        Score counted = score(2.0, 3.0, 2025, "Q1");
        Map<String, Object> citing = new LinkedHashMap<>();
        citing.put("Gated Citer", feeGated);
        citing.put("Uncategorized Citer", notCategorized);
        citing.put("Counted Citer", counted);
        citing.put("total", score(2.0, 6.0, 2025, null));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Cited Pub", citing);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "citations");
        graph.put("scores", scores);

        CitationDetailResponse resp = IndicatorDetailResponseAssembler.buildCitations(dto(graph, 2.0), "Cited Pub");

        assertEquals(2, resp.citations().size()); // gated + counted shown; fully-uncategorized stays dropped
        IndicatorDetailResponseAssembler.ScoredItem gated = resp.citations().stream()
                .filter(i -> i.key().equals("Gated Citer")).findFirst().orElseThrow();
        assertEquals(0.0, gated.authorScore());
        assertEquals(3.0, gated.forumScore());
        assertEquals("FEE_JOURNAL", gated.zeroReason());
        assertEquals(0, resp.citations().stream().filter(i -> i.key().equals("Uncategorized Citer")).count());
        // total only sums the counted citer's author score, unaffected by the muted rows now being listed.
        assertEquals(2.0, resp.totalScore());
    }

    @Test
    void citationsOutputModeKeepsACitedPublicationRowWhenAllItsCitersWereGatedToZero() {
        // Every citer for this publication got zeroed by a 2026 gate, but the aggregate 'total' still
        // carries a positive forum score (they were all correctly categorized) — the row must stay in the
        // top-level indicator table (0.00) so the drilldown modal explaining why is still reachable.
        Map<String, Object> citing = new LinkedHashMap<>();
        citing.put("total", score(0.0, 3.0, 2024, null));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("All-Gated Pub", citing);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "citations");
        graph.put("scores", scores);

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 0.0));

        assertEquals(1, resp.items().size());
        assertEquals(0.0, resp.items().getFirst().authorScore());
        assertEquals(3.0, resp.items().getFirst().forumScore());
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

    @Test
    void activitiesOutputModeBuildsRichDescriptionFromCachedMapFormIncludingProjectLabelResolution() {
        // The activities raw-graph list round-trips as plain property maps after a persisted-snapshot
        // read (the practical case on every path but the very first compute) — the evidence panel must
        // describe the activity, not fall back to the bare id (the reported bug).
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("act_1", score(4.0, 4.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "activities");
        graph.put("scores", scores);
        graph.put("activities", List.of(Map.of(
                "id", "act_1",
                "name", "SERRANO",
                "fields", Map.of("Rol", "membru"),
                "referenceFields", Map.of("PROJECT_GRANT_ID", "sproj_42"),
                "date", "2022")));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(
                dto(graph, 4.0), id -> null,
                ref -> "sproj_42".equals(ref) ? "H2020-SERRANO — Cloud-Edge (EU) — Director: A. Popescu" : ref);

        IndicatorDetailResponseAssembler.ScoredItem item = resp.items().getFirst();
        assertEquals("SERRANO — Rol: membru — H2020-SERRANO — Cloud-Edge (EU) — Director: A. Popescu — (2022)",
                item.key());
    }

    @Test
    void activitiesOutputModeLeavesReferenceRawWhenProjectLabelResolverReturnsInputUnchanged() {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("act_1", score(4.0, 4.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "activities");
        graph.put("scores", scores);
        graph.put("activities", List.of(Map.of(
                "id", "act_1",
                "name", "SCAPE",
                "referenceFields", Map.of("PROJECT_GRANT_ID", "sproj_99"))));

        // No resolver supplied (2-arg overload) — the reference passes through unresolved rather than
        // vanishing, since the default projectLabelResolver is the identity id -> null replaced with id.
        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 4.0), id -> null);

        assertEquals("SCAPE — sproj_99", resp.items().getFirst().key());
    }

    @Test
    void activitiesOutputModeFallsBackToRawIdWhenNoActivitiesListIsPresent() {
        // No 'activities' list in the graph at all (e.g. a stale snapshot predating the activities
        // feature) — every tier of the description resolver fails, so the item must degrade to the raw
        // id rather than throw.
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("act_bare", score(2.0, 2.0, 2021, "Q2"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "activities");
        graph.put("scores", scores);

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 2.0));

        assertEquals("act_bare", resp.items().getFirst().key());
        assertEquals(2.0, resp.items().getFirst().authorScore());
    }

    @Test
    void activitiesOutputModeFallsBackToBareBeanNameWhenActivitiesIsALiveBeanList() {
        // The 'activities' raw-graph list is a live bean list only on the very first, not-yet-persisted
        // compute (before any snapshot round-trip) — the Map-based rich description tier can't apply, so
        // the resolver must fall back to the bean's own getName(), not the raw id.
        ro.uvt.pokedex.core.model.activities.ActivityInstance bean =
                new ro.uvt.pokedex.core.model.activities.ActivityInstance();
        bean.setId("act_1");
        bean.setName("SERRANO");
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("act_1", score(4.0, 4.0, 2022, "Q1"));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("outputMode", "activities");
        graph.put("scores", scores);
        graph.put("activities", List.of(bean));

        IndicatorDetailResponse resp = IndicatorDetailResponseAssembler.buildDetail(dto(graph, 4.0));

        assertEquals("SERRANO", resp.items().getFirst().key());
    }
}
