package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcService;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryJournalViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(RankingViewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class RankingViewControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockitoBean
    private UrapRankingFacade urapRankingFacade;
    @MockitoBean
    private WosRankingDetailsReadService wosRankingDetailsReadService;
    @MockitoBean
    private ScholardexForumDetailService scholardexForumDetailService;
    @MockitoBean
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @MockitoBean
    private ScholardexForumMvcService scholardexForumMvcService;
    @MockitoBean
    private WosCategoryPageService wosCategoryPageService;
    @MockitoBean
    private ScholardexPublicationMvcService scholardexPublicationMvcService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void landingPageRendersPublicShellForAuthenticatedUsers() throws Exception {
        mockMvc.perform(get("/").with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/user/workspace\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/admin/users\""))));
    }

    @Test
    void forumsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk())
                .andExpect(view().name("forums/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-search-input\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-app-search-clear")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-wos\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/scholardex-forums.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void sharedForumsRouteServesPublicShellForAllUsers() throws Exception {
        // /forums is now a public surface — all users including admins see the public shell, not an admin sidebar
        mockMvc.perform(get("/forums").with(authenticatedUser(userWithRoles("admin@uvt.ro", Set.of(UserRole.PLATFORM_ADMIN)))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/admin/users\""))));
    }

    @Test
    void sharedForumsRouteUsesUserSidebarForNonAdmin() throws Exception {
        mockMvc.perform(get("/forums").with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/user/workspace\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/admin/users\""))));
    }

    @Test
    void legacyRankingsWosRouteIsRemoved() throws Exception {
        mockMvc.perform(get("/rankings/wos"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyRankingsWosDetailRouteIsRemoved() throws Exception {
        mockMvc.perform(get("/rankings/wos/{id}", "w1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingScholardexForumRendersNotFoundPage() throws Exception {
        when(scholardexForumDetailService.findDetail(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/forums/{id}", "missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("shared/not-found"));
    }

    @Test
    void journalForumWithWosDataRendersCanonicalDetailPage() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("w1");
        forum.setPublicationName("Test Journal");
        forum.setAggregationType("Journal");
        WoSRanking wosRanking = new WoSRanking();
        wosRanking.setId("w1");
        wosRanking.setName("Test Journal");
        wosRanking.setWebOfScienceCategoryIndex(java.util.Map.of());
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.JOURNAL,
                wosRanking,
                true,
                null,
                null,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w1").with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(view().name("forums/detail"))
                .andExpect(model().attributeExists("forum", "detail", "wosRanking", "breadcrumbs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:title\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:description\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-breadcrumb app-breadcrumb--default\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-summary-card--primary")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-forum-detail__definition-grid\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("General Metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Category Rankings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"forum-wos-category-data\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("https://unpkg.com/frappe-charts"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<style>"))));
    }

    @Test
    void anonymousJournalForumWithWosDataHidesMetricBlocks() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("w1");
        forum.setPublicationName("Test Journal");
        forum.setAggregationType("Journal");
        WoSRanking wosRanking = new WoSRanking();
        wosRanking.setId("w1");
        wosRanking.setName("Test Journal");
        wosRanking.setWebOfScienceCategoryIndex(java.util.Map.of());
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.JOURNAL,
                wosRanking,
                true,
                null,
                null,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w1"))
                .andExpect(status().isOk())
                .andExpect(view().name("forums/detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in to view Web of Science rankings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/login\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Log out"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("General Metrics"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Category Rankings"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("frappe-charts"))));
    }

    @Test
    void journalForumWithoutWosDataRendersNotIndexedState() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("w2");
        forum.setPublicationName("Unindexed Journal");
        forum.setAggregationType("Journal");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.JOURNAL,
                null,
                false,
                null,
                null,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w2"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w2").with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(view().name("forums/detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This journal is not indexed by WoS.")));
    }

    @Test
    void conferenceForumRendersCorePlaceholderWhenUnmatched() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("c1");
        forum.setPublicationName("Conference One");
        forum.setAggregationType("Conference Proceeding");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.CONFERENCE,
                null,
                false,
                null,
                null,
                true,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("c1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "c1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No CORE conference ranking is currently linked to this forum.")));
    }

    @Test
    void conferenceForumRendersCoreRankingWhenMatched() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("c2");
        forum.setPublicationName("International Conference on Software Engineering (ICSE)");
        forum.setAggregationType("Conference Proceeding");
        ro.uvt.pokedex.core.model.CoreConferenceRanking ranking = new ro.uvt.pokedex.core.model.CoreConferenceRanking();
        ranking.setId("ICSE-International Conference on Software Engineering");
        ranking.setName("International Conference on Software Engineering");
        ranking.setAcronym("ICSE");
        ro.uvt.pokedex.core.model.CoreConferenceRanking.YearlyRanking yr = new ro.uvt.pokedex.core.model.CoreConferenceRanking.YearlyRanking();
        yr.setRank(ro.uvt.pokedex.core.model.CoreConferenceRanking.Rank.A_STAR);
        ranking.setYearlyRankings(java.util.Map.of(2023, yr));
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.CONFERENCE,
                null,
                false,
                ranking,
                null,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("c2"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "c2"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("International Conference on Software Engineering (ICSE)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/core/rankings/ICSE-International%20Conference%20on%20Software%20Engineering")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"rankingChart\"")));
    }

    @Test
    void bookForumWithoutMatchRendersPlaceholder() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("b1");
        forum.setPublicationName("Book One");
        forum.setAggregationType("Book Series");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.BOOK,
                null,
                false,
                null,
                null,
                false,
                true,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("b1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "b1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No SENSE publisher ranking is currently linked to this forum.")));
    }

    @Test
    void bookForumWithMatchExposesSenseRanking() throws Exception {
        ScholardexForumView forum = new ScholardexForumView();
        forum.setId("b2");
        forum.setPublicationName("Book Two");
        forum.setAggregationType("Book Series");
        forum.setPublisher("Springer");
        ro.uvt.pokedex.core.model.SenseBookRanking ranking = new ro.uvt.pokedex.core.model.SenseBookRanking();
        ranking.setId("springer");
        ranking.setName("Springer");
        ranking.setRanking(ro.uvt.pokedex.core.model.SenseBookRanking.Rank.A);
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.BOOK,
                null,
                false,
                null,
                ranking,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("b2"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "b2"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SENSE Publisher Ranking")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Springer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Matched SENSE publisher: Springer")));
    }

    @Test
    void publicationDetailRendersCanonicalPublicProfile() throws Exception {
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView pub =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView();
        pub.setId("pub-1");
        pub.setTitle("On the Foundations of Adaptive Systems");
        pub.setDoi("10.1234/example");
        pub.setEid("2-s2.0-12345");
        pub.setCoverDate("2024-05-01");
        pub.setForum("forum-1");
        pub.setCitedbyCount(42);
        pub.setOpenAccess(true);
        ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel detail =
                new ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel(
                        pub,
                        java.util.List.of(new ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel.AuthorRef("a1", "Ada Lovelace")),
                        "Journal of Examples",
                        "2024");
        when(scholardexPublicationMvcService.findDetail(eq("pub-1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/publications/{id}", "pub-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("publications/detail"))
                .andExpect(model().attributeExists("publication", "detail", "breadcrumbs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:title\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("On the Foundations of Adaptive Systems")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ada Lovelace")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Journal of Examples")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums/forum-1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://doi.org/10.1234/example")));
    }

    @Test
    void publicationDetailGatesWosProvenanceBadgeForAnonymous() throws Exception {
        ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView pub =
                new ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView();
        pub.setId("pub-2");
        pub.setTitle("Gated Badge Paper");
        pub.setEid("2-s2.0-999");
        pub.setWosId("WOS:000999");
        ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel detail =
                new ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel(
                        pub, java.util.List.of(), "Journal", "2024");
        when(scholardexPublicationMvcService.findDetail(eq("pub-2"))).thenReturn(Optional.of(detail));

        // Anonymous: Scopus provenance badge shown; the WoS badge (login-gated) is hidden. The tooltip
        // "Indexed in Web of Science" is badge-only (the identifiers table uses "Web of Science ID").
        mockMvc.perform(get("/publications/{id}", "pub-2"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-badge")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Indexed in Scopus")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Indexed in Web of Science"))));

        // Authenticated: the WoS badge is visible.
        mockMvc.perform(get("/publications/{id}", "pub-2")
                        .with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Indexed in Web of Science")));
    }

    @Test
    void missingPublicationDetailRendersNotFound() throws Exception {
        when(scholardexPublicationMvcService.findDetail(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/publications/{id}", "missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("shared/not-found"));
    }

    @Test
    void rankingsHubRendersAllThreeTabsAndClientControls() throws Exception {
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/hub"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-app-tab-bar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-tab-id=\"core\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-selected=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-tab-id=\"universities\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-tab-id=\"events\"")))
                // Anonymous visitors now get a locked WoS teaser tab (sign-in gate); the category
                // table/JS stay hidden (asserted below + in RankingViewSecurityContractTest).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-tab-id=\"wos\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th scope=\"col\">Conference</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th scope=\"col\">University</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"events-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"events-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th scope=\"col\">Event Name</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-core.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-urap.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/events.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/rankings-categories.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")));
    }

    @Test
    void authenticatedRankingsHubExposesWosTabAndClientControls() throws Exception {
        mockMvc.perform(get("/rankings").with(authenticatedUser(userWithRoles("u@uvt.ro", Set.of(UserRole.RESEARCHER)))))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/hub"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-tab-id=\"wos\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th scope=\"col\">Category</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-categories.js")));
    }

    @Test
    void coreRankingsLegacyUrlRedirectsToHub() throws Exception {
        mockMvc.perform(get("/core/rankings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#core"));
    }

    @Test
    void universitiesLegacyUrlRedirectsToHub() throws Exception {
        mockMvc.perform(get("/universities"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#universities"));
    }

    @Test
    void eventsLegacyUrlRedirectsToHub() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#events"));
    }

    @Test
    void coreRankingDetailRendersCanonicalPublicProfile() throws Exception {
        CoreConferenceRanking conf = new CoreConferenceRanking();
        conf.setId("ICSE-International Conference on Software Engineering");
        conf.setName("International Conference on Software Engineering");
        conf.setAcronym("ICSE");
        conf.setSource("CORE");
        conf.setSourceId("CORE-ICSE");
        CoreConferenceRanking.YearlyRanking yr = new CoreConferenceRanking.YearlyRanking();
        yr.setRank(CoreConferenceRanking.Rank.A_STAR);
        conf.setYearlyRankings(Map.of(2023, yr));
        when(adminCatalogFacade.findCoreRankingById(eq("ICSE-International Conference on Software Engineering")))
                .thenReturn(Optional.of(conf));

        mockMvc.perform(get("/core/rankings/{id}", "ICSE-International Conference on Software Engineering"))
                .andExpect(status().isOk())
                .andExpect(view().name("core/ranking-detail"))
                .andExpect(model().attributeExists("conf", "breadcrumbs", "latestRankLabel", "latestRankAccent"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:title\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-summary-card")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A*")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"rankingChart\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cdn.jsdelivr.net"))));
    }

    @Test
    void coreRankingChartMapsNationalAndRegionalTiers() throws Exception {
        CoreConferenceRanking conf = new CoreConferenceRanking();
        conf.setId("NAT-Some National Conference");
        conf.setName("Some National Conference");
        conf.setAcronym("NATCON");
        conf.setSource("CORE");
        conf.setSourceId("CORE-NATCON");
        CoreConferenceRanking.YearlyRanking national = new CoreConferenceRanking.YearlyRanking();
        national.setRank(CoreConferenceRanking.Rank.National);
        CoreConferenceRanking.YearlyRanking regional = new CoreConferenceRanking.YearlyRanking();
        regional.setRank(CoreConferenceRanking.Rank.National_Regional);
        conf.setYearlyRankings(Map.of(2021, national, 2023, regional));
        when(adminCatalogFacade.findCoreRankingById(eq("NAT-Some National Conference")))
                .thenReturn(Optional.of(conf));

        mockMvc.perform(get("/core/rankings/{id}", "NAT-Some National Conference"))
                .andExpect(status().isOk())
                // The chart scale now maps the national/regional tiers (previously undefined -> dropped off the graph).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"National\": 2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"National_Regional\": 1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1: \"Regional\"")))
                // Latest (2023) tier label uses the friendly "Regional" instead of the enum name.
                .andExpect(model().attribute("latestRankLabel", "Regional"));
    }

    @Test
    void urapRankingDetailRendersCanonicalPublicProfile() throws Exception {
        URAPUniversityRanking ranking = new URAPUniversityRanking();
        ranking.setName("West University");
        ranking.setCountry("Romania");
        ranking.setScores(Map.of(
                2023, urapScore(212, 90.0, 80.0, 75.0, 1.9, 4.5, 12.0, 301.45),
                2024, urapScore(189, 95.0, 82.0, 77.0, 2.0, 4.7, 13.0, 318.61)
        ));
        when(urapRankingFacade.findRankingDetails(eq("West University"))).thenReturn(Optional.of(ranking));

        mockMvc.perform(get("/universities/{id}", "West University"))
                .andExpect(status().isOk())
                .andExpect(view().name("universities/detail"))
                .andExpect(model().attributeExists("ranking", "fields", "bestRank", "latestYear", "latestScore", "breadcrumbs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:title\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("property=\"og:description\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-summary-card--primary")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Overview Trends")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Detailed Indicators")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-score-data\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("https://cdn.jsdelivr.net/npm/chart.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void eventsDataEndpointReturnsPagedJson() throws Exception {
        ArtisticEvent ev = new ArtisticEvent();
        ev.setName("Test Event");
        ev.setRank(ArtisticEvent.Rank.INTERNATIONAL_TOP);
        ev.setDomainId("domain-1");
        when(adminCatalogFacade.listArtisticEvents()).thenReturn(List.of(ev));

        mockMvc.perform(get("/events/data").param("page", "0").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Test Event"))
                .andExpect(jsonPath("$.items[0].rankLabel").value("1 — International Top"));
    }

    @Test
    void missingCoreRankingRedirectsToHub() throws Exception {
        when(adminCatalogFacade.findCoreRankingById(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/core/rankings/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#core"));
    }

    @Test
    void missingUrapRankingRedirectsToHub() throws Exception {
        when(urapRankingFacade.findRankingDetails(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/universities/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#universities"));
    }

    @Test
    void publicShellExposesUnifiedRankingsLink() throws Exception {
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/wos/categories\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rankings\"")));
    }

    @Test
    void wosCategoriesRouteRedirectsToRankingsHubTab() throws Exception {
        mockMvc.perform(get("/wos/categories"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#wos"));
    }

    @Test
    void wosCategoryDetailPageRendersCanonicalTemplateAndForumLinks() throws Exception {
        when(wosCategoryPageService.findCategory(eq("Computer Science - SCIE"))).thenReturn(Optional.of(
                new WosCategoryDetailViewModel(
                        "Computer Science - SCIE",
                        "Computer Science",
                        "SCIE",
                        1,
                        2024,
                        List.of(new WosCategoryJournalViewModel("j1", "Journal One", "1234-5678", "8765-4321", 2024, "Q1", "Q2", "Q1")),
                        new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics(
                                1.85, 6.20, 1.50, 2024, 3.10, 42.5, 2.40, 2019,
                                new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics.QuartileSplit(1, 0, 0, 0),
                                java.util.List.of(
                                        new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics.TrendPoint(2023, 1.70, null),
                                        new ro.uvt.pokedex.core.service.application.model.WosCategoryMetrics.TrendPoint(2024, 1.85, null)))
                )
        ));

        mockMvc.perform(get("/wos/categories/{key}", "Computer Science - SCIE"))
                .andExpect(status().isOk())
                .andExpect(view().name("wos/category-detail"))
                .andExpect(model().attributeExists("categoryDetail", "breadcrumbs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rankings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Journal Coverage")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums/j1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rankings#wos\"")));
    }

    @Test
    void missingWosCategoryRendersNotFoundPage() throws Exception {
        when(wosCategoryPageService.findCategory(eq("missing - SCIE"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/wos/categories/{key}", "missing - SCIE"))
                .andExpect(status().isOk())
                .andExpect(view().name("shared/not-found"));
    }

    @Test
    void removedPublicAliasesReturnNotFound() throws Exception {
        mockMvc.perform(get("/scholardex/forums"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/scholardex/forums/{id}", "w1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/rankings/categories"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/rankings/core"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/core"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/rankings/urap"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/urap"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/rankings/events"))
                .andExpect(status().isNotFound());
    }

    private User userWithRoles(String email, Set<UserRole> roles) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(roles);
        return user;
    }

    private RequestPostProcessor authenticatedUser(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private URAPUniversityRanking.Score urapScore(
            int rank,
            double article,
            double citation,
            double totalDocument,
            double ait,
            double cit,
            double collaboration,
            double total
    ) {
        URAPUniversityRanking.Score score = new URAPUniversityRanking.Score();
        score.setRank(rank);
        score.setArticle(article);
        score.setCitation(citation);
        score.setTotalDocument(totalDocument);
        score.setAIT(ait);
        score.setCIT(cit);
        score.setCollaboration(collaboration);
        score.setTotal(total);
        return score;
    }
}
