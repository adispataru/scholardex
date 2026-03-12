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
import ro.uvt.pokedex.core.model.scopus.Forum;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;

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
    private ScholardexProjectionReadService scholardexProjectionReadService;
    @MockitoBean
    private ScholardexForumMvcService scholardexForumMvcService;

    @Test
    void scholardexForumsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/scholardex/forums"))
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
    void rankingsWosListRedirectsToCanonicalScholardexForums() throws Exception {
        mockMvc.perform(get("/rankings/wos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/scholardex/forums?wos=indexed"));
    }

    @Test
    void rankingsWosDetailRedirectsToCanonicalScholardexForumDetail() throws Exception {
        mockMvc.perform(get("/rankings/wos/{id}", "w1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/scholardex/forums/w1"));
    }

    @Test
    void missingScholardexForumRendersNotFoundPage() throws Exception {
        when(scholardexProjectionReadService.findForumById(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/scholardex/forums/{id}", "missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/ranking-not-found"));
    }

    @Test
    void existingScholardexForumRendersCanonicalDetailPage() throws Exception {
        Forum forum = new Forum();
        forum.setId("w1");
        forum.setPublicationName("Test Journal");
        when(scholardexProjectionReadService.findForumById(eq("w1"))).thenReturn(Optional.of(forum));
        when(wosRankingDetailsReadService.findByJournalId(eq("w1"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/scholardex/forums/{id}", "w1"))
                .andExpect(status().isOk())
                .andExpect(view().name("scholardex/forum-detail"))
                .andExpect(model().attributeExists("forum"));
    }

    @Test
    void coreRankingsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/rankings/core"))
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
        mockMvc.perform(get("/rankings/urap"))
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

        mockMvc.perform(get("/rankings/events"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/events"))
                .andExpect(model().attributeExists("artisticEvents"));
    }

    @Test
    void missingCoreRankingRedirectsToCoreList() throws Exception {
        when(adminCatalogFacade.findCoreRankingById(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/rankings/core/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings/core"));
    }

    @Test
    void missingUrapRankingRedirectsToUrapList() throws Exception {
        when(urapRankingFacade.findRankingDetails(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/rankings/urap/{id}", "missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings/urap"));
    }

    @Test
    void rankingsTemplatesExposeSidebarLinks() throws Exception {
        mockMvc.perform(get("/scholardex/forums"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/scholardex/forums\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rankings/core\"")));
    }
}
