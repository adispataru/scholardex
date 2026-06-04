package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.transfer.CitationSnapshotItem;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexCitationView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.EffectiveAuthorshipReadService;
import ro.uvt.pokedex.core.service.application.ResearcherAuthorLookupService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.reporting.Score;
import ro.uvt.pokedex.core.service.reporting.ScientificProductionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;

class CitationRowProjectorTest {

    @Test
    void buildsOneTilePerCitedPubAndExcludesSelfCitationsWhenIndicatorRequests() {
        UserService userService = mock(UserService.class);
        ResearcherAuthorLookupService researcherLookup = mock(ResearcherAuthorLookupService.class);
        EffectiveAuthorshipReadService authorship = mock(EffectiveAuthorshipReadService.class);
        ScholardexProjectionReadService projection = mock(ScholardexProjectionReadService.class);
        ScientificProductionService scoring = mock(ScientificProductionService.class);

        User user = new User();
        User.ResearcherProfile profile = new User.ResearcherProfile();
        user.setResearcherProfile(profile);
        when(userService.getUserByEmail("u@x")).thenReturn(Optional.of(user));
        when(researcherLookup.resolveAuthorLookupKeys(profile)).thenReturn(List.of("self-1"));

        ScholardexPublicationView cited1 = publication("c1", "Self-supervised graph learning", "f-jmlr",
                List.of("self-1", "co-1"), 2, "Vol 24", "2023-06");
        ScholardexPublicationView cited2 = publication("c2", "Empty paper (no citations)", "f-jmlr",
                List.of("self-1"), 1, "Vol 25", "2022");
        when(authorship.findConfirmedPublicationsForScoring("u@x")).thenReturn(List.of(cited1, cited2));

        ScholardexCitationView cit1 = citation("ct1", "c1", "ext-1");
        ScholardexCitationView cit2 = citation("ct2", "c1", "ext-2");
        ScholardexCitationView cit3 = citation("ct3", "c1", "self-cite-pub");
        when(projection.findAllCitationsByCitedIdIn(any())).thenReturn(List.of(cit1, cit2, cit3));

        ScholardexPublicationView citingExt1 = publication("ext-1", "Follow-up on graph SSL",
                "f-iclr", List.of("ext-author-a"), 1, "pp 1-12", "2024");
        ScholardexPublicationView citingExt2 = publication("ext-2", "Survey of representation learning",
                "f-survey", List.of("ext-author-b"), 1, "Vol 5", "2024");
        ScholardexPublicationView selfCite = publication("self-cite-pub", "Our own follow-up",
                "f-jmlr", List.of("self-1"), 1, "Vol 26", "2024");
        when(projection.findAllPublicationsByIdIn(any())).thenReturn(List.of(citingExt1, citingExt2, selfCite));

        when(projection.findForumsByIdIn(anyCollection())).thenReturn(List.of(
                forum("f-jmlr", "Journal of Machine Learning Research"),
                forum("f-iclr", "ICLR Proceedings"),
                forum("f-survey", "Survey J.")
        ));
        Map<String, ScholardexAuthorView> authorById = new HashMap<>();
        authorById.put("ext-author-a", author("ext-author-a", "Doe, J."));
        authorById.put("ext-author-b", author("ext-author-b", "Smith, A."));
        authorById.put("self-1",       author("self-1", "Self, S."));
        when(projection.findAuthorsByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(authorById::get).filter(java.util.Objects::nonNull).toList();
        });

        when(scoring.calculateScientificImpactScore(any(), any(), any())).thenReturn(Map.of(
                "Follow-up on graph SSL",          score("AA"),
                "Survey of representation learning", score("B"),
                "Our own follow-up",                score("A")
        ));

        Indicator excludeSelfIndicator = new Indicator();
        excludeSelfIndicator.setOutputType("CITATIONS_EXCLUDE_SELF");

        CitationRowProjector projector = new CitationRowProjector(
                userService, researcherLookup, authorship, projection, scoring);

        List<CitationSnapshotItem> tiles = projector.project("u@x", excludeSelfIndicator, CitationRowProjector.ROLE_KEY);

        // cited2 has no citations → no tile; cited1 → one tile with two external rows (self-cite excluded).
        assertThat(tiles).hasSize(1);
        CitationSnapshotItem tile = tiles.get(0);
        assertThat(tile.getRoleKey()).isEqualTo("citations-per-publication");
        assertThat(tile.getPublicationTitle()).isEqualTo("Self-supervised graph learning");
        assertThat(tile.getPublicationForumName()).isEqualTo("Journal of Machine Learning Research");
        assertThat(tile.getPublicationYear()).isEqualTo(2023);
        assertThat(tile.getPublicationAuthorCount()).isEqualTo(2);
        assertThat(tile.getCitingPublications()).hasSize(2);

        List<String> citingTitles = tile.getCitingPublications().stream()
                .map(CitationSnapshotItem.CitingPublication::getTitle).toList();
        assertThat(citingTitles).containsExactlyInAnyOrder("Follow-up on graph SSL", "Survey of representation learning");
        assertThat(citingTitles).doesNotContain("Our own follow-up");

        CitationSnapshotItem.CitingPublication followUp = tile.getCitingPublications().stream()
                .filter(p -> p.getTitle().equals("Follow-up on graph SSL")).findFirst().orElseThrow();
        assertThat(followUp.getForumName()).isEqualTo("ICLR Proceedings");
        assertThat(followUp.getForumCategoryLetter()).isEqualTo("AA");
        assertThat(followUp.getAuthors()).isEqualTo("Doe, J.");
        assertThat(followUp.getYear()).isEqualTo(2024);
        assertThat(followUp.getIsWorkshopDaNu()).isEqualTo("NU");
    }

    @Test
    void doesNotFilterSelfCitationsWhenIndicatorIsPlainCitations() {
        UserService userService = mock(UserService.class);
        ResearcherAuthorLookupService researcherLookup = mock(ResearcherAuthorLookupService.class);
        EffectiveAuthorshipReadService authorship = mock(EffectiveAuthorshipReadService.class);
        ScholardexProjectionReadService projection = mock(ScholardexProjectionReadService.class);
        ScientificProductionService scoring = mock(ScientificProductionService.class);

        User user = new User();
        user.setResearcherProfile(new User.ResearcherProfile());
        when(userService.getUserByEmail("u@x")).thenReturn(Optional.of(user));
        when(researcherLookup.resolveAuthorLookupKeys(any())).thenReturn(List.of("self-1"));

        ScholardexPublicationView cited = publication("c1", "Cited paper", "f1",
                List.of("self-1"), 1, "", "2023");
        when(authorship.findConfirmedPublicationsForScoring("u@x")).thenReturn(List.of(cited));
        when(projection.findAllCitationsByCitedIdIn(any())).thenReturn(List.of(citation("x", "c1", "self-cite")));
        when(projection.findAllPublicationsByIdIn(any())).thenReturn(List.of(
                publication("self-cite", "Our own follow-up", "f1", List.of("self-1"), 1, "", "2024")));
        when(projection.findForumsByIdIn(anyCollection())).thenReturn(List.of(forum("f1", "J1")));
        Map<String, ScholardexAuthorView> authorById2 = new HashMap<>();
        authorById2.put("self-1", author("self-1", "Self, S."));
        when(projection.findAuthorsByIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(authorById2::get).filter(java.util.Objects::nonNull).toList();
        });
        when(scoring.calculateScientificImpactScore(any(), any(), any()))
                .thenReturn(Map.of("Our own follow-up", score("A")));

        Indicator plainCitations = new Indicator();
        plainCitations.setOutputType("CITATIONS");

        CitationRowProjector projector = new CitationRowProjector(
                userService, researcherLookup, authorship, projection, scoring);

        List<CitationSnapshotItem> tiles = projector.project("u@x", plainCitations, CitationRowProjector.ROLE_KEY);
        assertThat(tiles).hasSize(1);
        assertThat(tiles.get(0).getCitingPublications()).hasSize(1);
        assertThat(tiles.get(0).getCitingPublications().get(0).getTitle()).isEqualTo("Our own follow-up");
    }

    @Test
    void unknownRoleKeyReturnsEmpty() {
        CitationRowProjector projector = new CitationRowProjector(
                mock(UserService.class),
                mock(ResearcherAuthorLookupService.class),
                mock(EffectiveAuthorshipReadService.class),
                mock(ScholardexProjectionReadService.class),
                mock(ScientificProductionService.class));
        assertThat(projector.project("u@x", new Indicator(), "something-else")).isEmpty();
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

    private ScholardexForumView forum(String id, String name) {
        ScholardexForumView f = new ScholardexForumView();
        f.setId(id);
        f.setPublicationName(name);
        return f;
    }

    private ScholardexAuthorView author(String id, String name) {
        ScholardexAuthorView a = new ScholardexAuthorView();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private ScholardexCitationView citation(String id, String citedId, String citingId) {
        ScholardexCitationView c = new ScholardexCitationView();
        c.setId(id);
        c.setCitedId(citedId);
        c.setCitingId(citingId);
        return c;
    }

    private Score score(String category) {
        Score s = new Score();
        s.setCoreRankingEquivalent(category);
        return s;
    }
}
