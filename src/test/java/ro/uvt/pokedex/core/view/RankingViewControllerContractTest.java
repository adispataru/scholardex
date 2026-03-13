package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.WosCategoryJournalViewModel;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    void forumsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk())
                .andExpect(view().name("scholardex/forums"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-wos\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"scholardex-forums-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/scholardex-forums.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
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
                .andExpect(view().name("user/ranking-not-found"));
    }

    @Test
    void journalForumWithWosDataRendersCanonicalDetailPage() throws Exception {
        Forum forum = new Forum();
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
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w1"))
                .andExpect(status().isOk())
                .andExpect(view().name("scholardex/forum-detail"))
                .andExpect(model().attributeExists("forum", "detail", "wosRanking"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("General Metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Category Rankings")));
    }

    @Test
    void journalForumWithoutWosDataRendersNotIndexedState() throws Exception {
        Forum forum = new Forum();
        forum.setId("w2");
        forum.setPublicationName("Unindexed Journal");
        forum.setAggregationType("Journal");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.JOURNAL,
                null,
                false,
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w2"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w2"))
                .andExpect(status().isOk())
                .andExpect(view().name("scholardex/forum-detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This journal is not indexed by WoS.")));
    }

    @Test
    void conferenceForumRendersCorePlaceholder() throws Exception {
        Forum forum = new Forum();
        forum.setId("c1");
        forum.setPublicationName("Conference One");
        forum.setAggregationType("Conference Proceeding");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.CONFERENCE,
                null,
                false,
                true,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("c1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "c1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CORE conference ranking rendering is reserved for a later H23 update.")));
    }

    @Test
    void bookForumRendersBookPlaceholder() throws Exception {
        Forum forum = new Forum();
        forum.setId("b1");
        forum.setPublicationName("Book One");
        forum.setAggregationType("Book Series");
        ScholardexForumDetailViewModel detail = new ScholardexForumDetailViewModel(
                forum,
                ScholardexForumDetailViewModel.ForumType.BOOK,
                null,
                false,
                false,
                true,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("b1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "b1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Book ranking rendering is reserved for a later H23 update.")));
    }

    @Test
    void coreRankingsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/core/rankings"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/core"))
                .andExpect(model().attributeDoesNotExist("confs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"core-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-core.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void urapRankingsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/universities"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/urap"))
                .andExpect(model().attributeDoesNotExist("rankings"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"urap-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-urap.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
    }

    @Test
    void eventsRankingsPageRendersExpectedTemplateAndModel() throws Exception {
        when(adminCatalogFacade.listArtisticEvents()).thenReturn(List.of(new ArtisticEvent()));

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/events"))
                .andExpect(model().attributeExists("artisticEvents"));
    }

    @Test
    void missingCoreRankingRedirectsToCoreList() throws Exception {
        when(adminCatalogFacade.findCoreRankingById(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/core/rankings/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/core/rankings"));
    }

    @Test
    void missingUrapRankingRedirectsToUrapList() throws Exception {
        when(urapRankingFacade.findRankingDetails(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/universities/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/universities"));
    }

    @Test
    void rankingsTemplatesExposeSidebarLinks() throws Exception {
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/wos/categories\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/core/rankings\"")));
    }

    @Test
    void wosCategoriesPageRendersCanonicalTemplateAndLinks() throws Exception {
        mockMvc.perform(get("/wos/categories"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/categories"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("WoS Categories")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-categories-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-categories.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums\"")));
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
                        List.of(new WosCategoryJournalViewModel("j1", "Journal One", "1234-5678", "8765-4321", 2024, "Q1", "Q2", "Q1"))
                )
        ));

        mockMvc.perform(get("/wos/categories/{key}", "Computer Science - SCIE"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/category-detail"))
                .andExpect(model().attributeExists("categoryDetail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Journal Coverage")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/forums/j1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/wos/categories\"")));
    }

    @Test
    void missingWosCategoryRendersNotFoundPage() throws Exception {
        when(wosCategoryPageService.findCategory(eq("missing - SCIE"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/wos/categories/{key}", "missing - SCIE"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/ranking-not-found"));
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
}
