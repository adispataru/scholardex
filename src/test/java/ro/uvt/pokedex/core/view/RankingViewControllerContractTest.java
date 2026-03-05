package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;

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

    @MockBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockBean
    private UrapRankingFacade urapRankingFacade;

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
    void coreRankingsPageRendersExpectedTemplateAndModel() throws Exception {
        when(adminCatalogFacade.listCoreRankings()).thenReturn(List.of());

        mockMvc.perform(get("/rankings/core"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/core"))
                .andExpect(model().attributeExists("confs"));
    }

    @Test
    void urapRankingsPageRendersExpectedTemplateAndModel() throws Exception {
        when(urapRankingFacade.listRankings()).thenReturn(List.of(new URAPUniversityRanking()));

        mockMvc.perform(get("/rankings/urap"))
                .andExpect(status().isOk())
                .andExpect(view().name("rankings/urap"))
                .andExpect(model().attributeExists("rankings"));
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
        when(adminCatalogFacade.findWosRankingById(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/rankings/wos/{id}", "missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("user/ranking-not-found"));
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
