package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationRowProjectorTest {

    @Test
    void includesEveryScoredPublicationAndCarriesScoreCategoryToForumCategoryLetter() {
        EffectiveAuthorshipReadService authorship = mock(EffectiveAuthorshipReadService.class);
        ScholardexProjectionReadService projection = mock(ScholardexProjectionReadService.class);
        ScientificProductionService scoring = mock(ScientificProductionService.class);

        ScholardexPublicationView journalPub = publication("p1", "Self-supervised graph learning", "f-jmlr",
                List.of("a1", "a2"), 2, "Vol 24, pp 1-30", "2023-06");
        ScholardexPublicationView confPub = publication("p2", "Sparse attention is all you need", "f-iclr",
                List.of("a1", "a3", "a4"), 3, "pp 200-240", "2024-04");
        ScholardexPublicationView unscoredPub = publication("p3", "Out-of-scope paper", "f-jmlr",
                List.of("a1"), 1, "Vol 25", "2022");

        when(authorship.findConfirmedPublicationsForScoring("u@x")).thenReturn(List.of(journalPub, confPub, unscoredPub));
        when(projection.findForumsByIdIn(anyCollection())).thenReturn(List.of(
                forum("f-jmlr", "Journal of Machine Learning Research", "Journal"),
                forum("f-iclr", "ICLR Proceedings", "Conference Proceeding")
        ));
        when(projection.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(
                author("a1", "Popescu, A."),
                author("a2", "Ionescu, R."),
                author("a3", "Lee, K."),
                author("a4", "Park, M.")
        ));
        when(scoring.calculateScientificProductionScore(any(), any())).thenReturn(Map.of(
                "Self-supervised graph learning", score("A", 8.0),
                "Sparse attention is all you need", score("AA", 12.0)
                // "Out-of-scope paper" intentionally omitted — must be filtered out.
        ));

        PublicationRowProjector projector = new PublicationRowProjector(authorship, projection, scoring);
        Indicator indicator = new Indicator();

        // The indicator's scoring is the filter: both scored publications are emitted under the
        // requested role; the unscored one is dropped. The role-key just tags the items so the
        // renderer routes them to the right sheet.
        List<PublicationSnapshotItem> rows = projector.project("u@x", indicator, PublicationRowProjector.ROLE_JOURNAL);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(PublicationSnapshotItem::getRoleKey).containsOnly("journal-publications");
        assertThat(rows).extracting(PublicationSnapshotItem::getTitle)
                .containsExactlyInAnyOrder("Self-supervised graph learning", "Sparse attention is all you need");

        PublicationSnapshotItem sslGraph = rows.stream()
                .filter(r -> r.getTitle().equals("Self-supervised graph learning")).findFirst().orElseThrow();
        assertThat(sslGraph.getForumName()).isEqualTo("Journal of Machine Learning Research");
        assertThat(sslGraph.getYear()).isEqualTo(2023);
        assertThat(sslGraph.getForumCategoryLetter()).isEqualTo("A");
        assertThat(sslGraph.getAuthorCount()).isEqualTo(2);
        assertThat(sslGraph.getAuthors()).isEqualTo("Popescu, A., Ionescu, R.");
        assertThat(sslGraph.getIsWorkshopDaNu()).isEqualTo("NU");
    }

    @Test
    void workshopAdjustedFlagInScoringInfoYieldsDA() {
        EffectiveAuthorshipReadService authorship = mock(EffectiveAuthorshipReadService.class);
        ScholardexProjectionReadService projection = mock(ScholardexProjectionReadService.class);
        ScientificProductionService scoring = mock(ScientificProductionService.class);

        ScholardexPublicationView mainConf = publication("p-main", "Main conference paper", "f-conf",
                List.of("a1"), 1, "pp 1-10", "2024");
        ScholardexPublicationView workshopConf = publication("p-ws", "Workshop paper", "f-conf",
                List.of("a1"), 1, "pp 100-110", "2024");

        when(authorship.findConfirmedPublicationsForScoring("u@x")).thenReturn(List.of(mainConf, workshopConf));
        when(projection.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f-conf", "ICLR", "Conference Proceeding")));
        when(projection.findAuthorsByIdIn(anyCollection())).thenReturn(List.of(author("a1", "Popescu, A.")));

        Score plain = score("A", 8.0);
        Score workshopAdjusted = score("A", 4.0);
        workshopAdjusted.getScoringInfo().put("workshopAdjusted", true);
        when(scoring.calculateScientificProductionScore(any(), any())).thenReturn(Map.of(
                "Main conference paper", plain,
                "Workshop paper",        workshopAdjusted
        ));

        PublicationRowProjector projector = new PublicationRowProjector(authorship, projection, scoring);
        List<PublicationSnapshotItem> rows = projector.project("u@x", new Indicator(), PublicationRowProjector.ROLE_CONFERENCE);

        PublicationSnapshotItem mainRow = rows.stream()
                .filter(r -> r.getTitle().equals("Main conference paper")).findFirst().orElseThrow();
        PublicationSnapshotItem workshopRow = rows.stream()
                .filter(r -> r.getTitle().equals("Workshop paper")).findFirst().orElseThrow();
        assertThat(mainRow.getIsWorkshopDaNu()).isEqualTo("NU");
        assertThat(workshopRow.getIsWorkshopDaNu()).isEqualTo("DA");
    }

    @Test
    void unknownRoleKeyReturnsEmpty() {
        EffectiveAuthorshipReadService authorship = mock(EffectiveAuthorshipReadService.class);
        ScholardexProjectionReadService projection = mock(ScholardexProjectionReadService.class);
        ScientificProductionService scoring = mock(ScientificProductionService.class);
        PublicationRowProjector projector = new PublicationRowProjector(authorship, projection, scoring);

        assertThat(projector.project("u@x", new Indicator(), "something-unknown")).isEmpty();
    }

    private ScholardexPublicationView publication(String id, String title, String forumId,
                                                  List<String> authors, int authorCount,
                                                  String volume, String coverDate) {
        ScholardexPublicationView p = new ScholardexPublicationView();
        p.setId(id);
        p.setTitle(title);
        p.setForum(forumId);
        p.setAuthors(authors);
        p.setAuthorCount(authorCount);
        p.setVolume(volume);
        p.setCoverDate(coverDate);
        return p;
    }

    private ScholardexForumView forum(String id, String name, String aggregationType) {
        ScholardexForumView f = new ScholardexForumView();
        f.setId(id);
        f.setPublicationName(name);
        f.setAggregationType(aggregationType);
        return f;
    }

    private ScholardexAuthorView author(String id, String name) {
        ScholardexAuthorView a = new ScholardexAuthorView();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private Score score(String category, double v) {
        Score s = new Score();
        s.setCoreRankingEquivalent(category);
        s.setScore(v);
        s.setAuthorScore(v);
        return s;
    }
}
