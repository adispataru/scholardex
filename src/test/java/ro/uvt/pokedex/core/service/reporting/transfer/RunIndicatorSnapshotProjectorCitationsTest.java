package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.IndicatorPayloadSerializer;
import ro.uvt.pokedex.core.service.application.RunGraphPublicationSlice;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H106 S2/S3 — the run's stored citation graph carries a {@code scores} entry (just a "total") for EVERY
 * confirmed publication; only the ones with citing rows are tiles, and the tiles are filled from the
 * persisted publication slices with author/forum ids resolved to names at export time.
 */
class RunIndicatorSnapshotProjectorCitationsTest {

    @Test
    void citedWorksWithoutCitingRowsAreNotTiles() {
        RunIndicatorSnapshotProjector projector = new RunIndicatorSnapshotProjector(null);
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("Uncited work", Map.of("total", score(0.0)));
        scores.put("Cited work", Map.of(
                "A citing paper", score(4.0),
                "My own follow-up", excluded("SELF_CITATION"),
                "total", score(4.0)));
        scores.put("Only self-cited work", Map.of(
                "Another own paper", excluded("SELF_CITATION"),
                "total", score(0.0)));
        IndicatorApplyResultDto result = result(Map.of("outputMode", "citations", "scores", scores));

        List<CitationSnapshotItem> tiles = projector.projectCitations(result, "citations-per-publication");

        // Excluded (self-)citations are not rows, and a work with only excluded citations is not a tile.
        assertThat(tiles).extracting(CitationSnapshotItem::getPublicationTitle).containsExactly("Cited work");
        assertThat(tiles.get(0).getCitingPublications()).extracting(CitationSnapshotItem.CitingPublication::getTitle)
                .containsExactly("A citing paper");
        assertThat(tiles.get(0).getScore()).isEqualTo(4.0);
    }

    @Test
    void tilesAreFilledFromThePersistedSlicesWithNamesResolved() {
        ScholardexProjectionReadService read = mock(ScholardexProjectionReadService.class);
        when(read.findAuthorsByIdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            assertThat(Set.copyOf(ids)).isEqualTo(Set.of("a1", "a2", "a3"));
            return List.of(author("a1", "Fortiș, A."), author("a2", "Doe, J."), author("a3", "Roe, R."));
        });
        when(read.findForumsByIdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            assertThat(Set.copyOf(ids)).isEqualTo(Set.of("f-cited", "f-citing"));
            return List.of(forum("f-cited", "ArXiv.org"), forum("f-citing", "Web Intelligence"));
        });
        RunIndicatorSnapshotProjector projector = new RunIndicatorSnapshotProjector(read);

        ScholardexPublicationView cited = pub("p1", "Considerations on Construction Ontologies",
                List.of("a1"), "f-cited", null, "2009-05-01", 3, "10.48550/arxiv.0905.4601");
        ScholardexPublicationView citing = pub("p2", "Ontology-Based Sentiment Analysis",
                List.of("a2", "a3"), "f-citing", "17(3)", "2019-01-01", 2, "10.3233/WEB-190396");

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put(cited.getTitle(), Map.of(citing.getTitle(), score(2.0), "total", score(2.0)));
        Map<String, Object> rawGraph = Map.of(
                "outputMode", "citations",
                "scores", scores,
                "publications", RunGraphPublicationSlice.ofAll(List.of(cited)),
                "citationMap", RunGraphPublicationSlice.ofMap(Map.of(citing.getTitle(), citing)));

        List<CitationSnapshotItem> tiles = projector.projectCitations(result(rawGraph), "citations-per-publication");

        assertThat(tiles).hasSize(1);
        CitationSnapshotItem tile = tiles.get(0);
        assertThat(tile.getItemKey()).isEqualTo("p1");
        assertThat(tile.getPublicationForumName()).isEqualTo("ArXiv.org");
        assertThat(tile.getPublicationYear()).isEqualTo(2009);
        assertThat(tile.getPublicationAuthorCount()).isEqualTo(3);
        CitationSnapshotItem.CitingPublication row = tile.getCitingPublications().get(0);
        assertThat(row.getAuthors()).isEqualTo("Doe, J., Roe, R.");
        assertThat(row.getForumName()).isEqualTo("Web Intelligence");
        assertThat(row.getVolumeInfo()).isEqualTo("17(3)");
        assertThat(row.getYear()).isEqualTo(2019);
        assertThat(row.getForumCategoryLetter()).isEqualTo("B");
    }

    @Test
    void sliceKeepsOnlyTheExportFields() {
        ScholardexPublicationView p = pub("p1", "T", List.of("a1"), "f1", "v", "2020-01-01", 4, "10.1/x");
        p.setDescription("a long abstract that must not be persisted");
        Map<String, Object> slice = RunGraphPublicationSlice.of(p);
        assertThat(slice.keySet()).containsExactly("id", "title", "doi", "authors", "forum", "volume", "coverDate", "authorCount");
        assertThat(slice).containsEntry("doi", "10.1/x").containsEntry("authorCount", 4);
        assertThat(slice.values()).noneMatch(v -> String.valueOf(v).contains("abstract"));
        assertThat(RunGraphPublicationSlice.ofAll(java.util.Arrays.asList(p, null, pub(null, null, null, null, null, null, 0, null))))
                .hasSize(1);
    }

    /**
     * Round-trip through the persistence serializer so the graph has the shape the export really sees:
     * score-shaped maps come back as {@code Score} beans, not maps.
     */
    private static IndicatorApplyResultDto result(Map<String, Object> rawGraph) {
        IndicatorPayloadSerializer serializer = new IndicatorPayloadSerializer(new ObjectMapper());
        Map<String, Object> stored = serializer.deserialize(serializer.serialize(rawGraph));
        return new IndicatorApplyResultDto("r1", "ind1", "user/indicators-apply", stored, null, null, null, null, 1);
    }

    private static ScholardexPublicationView pub(String id, String title, List<String> authors, String forum,
                                                 String volume, String coverDate, int authorCount, String doi) {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId(id);
        p.setTitle(title);
        p.setAuthors(authors);
        p.setForum(forum);
        p.setVolume(volume);
        p.setCoverDate(coverDate);
        p.setAuthorCount(authorCount);
        p.setDoi(doi);
        return p;
    }

    private static ScholardexAuthorView author(String id, String name) {
        ScholardexAuthorView a = new ScholardexAuthorView();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static ScholardexForumView forum(String id, String name) {
        ScholardexForumView f = new ScholardexForumView();
        f.setId(id);
        f.setPublicationName(name);
        return f;
    }

    private static Map<String, Object> excluded(String zeroReason) {
        Map<String, Object> s = score(0.0);
        s.put("scoringInfo", Map.of("zeroReason", zeroReason));
        return s;
    }

    private static Map<String, Object> score(double points) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("score", points);
        s.put("authorScore", points);
        s.put("coreRankingEquivalent", points > 0 ? "B" : null);
        s.put("scoringInfo", Map.of());
        return s;
    }
}
