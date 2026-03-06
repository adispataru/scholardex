package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
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

    @Test
    void wosRankingsPageRendersExpectedTemplateAndClientControls() throws Exception {
        mockMvc.perform(get("/rankings/wos"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/wos"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-sort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-direction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-size\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-table-body\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-prev\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"wos-next\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/rankings-wos.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/js/demo/datatables-demo.js"))));
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
    void missingWosRankingRendersNotFoundPage() throws Exception {
        when(wosRankingDetailsReadService.findByJournalId(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/rankings/wos/{id}", "missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/ranking-not-found"));
    }

    @Test
    void existingWosRankingRendersDetailsFromProjectionBackedService() throws Exception {
        WoSRanking ranking = new WoSRanking();
        ranking.setId("w1");
        ranking.setName("Test Journal");
        when(wosRankingDetailsReadService.findByJournalId(eq("w1"))).thenReturn(Optional.of(ranking));

        mockMvc.perform(get("/rankings/wos/{id}", "w1"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/wos-detail"))
                .andExpect(model().attributeExists("journal"));
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
        mockMvc.perform(get("/rankings/wos"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rankings/wos\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rankings/core\"")));
    }
}
