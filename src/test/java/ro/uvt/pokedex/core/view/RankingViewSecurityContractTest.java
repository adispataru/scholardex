package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.ArtisticEvent;
import ro.uvt.pokedex.core.model.CoreConferenceRanking;
import ro.uvt.pokedex.core.model.URAPUniversityRanking;
import ro.uvt.pokedex.core.model.WoSRanking;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.AdminDashboardService;
import ro.uvt.pokedex.core.service.application.GroupManagementFacade;
import ro.uvt.pokedex.core.service.application.AdminInstitutionReportFacade;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAdminReadPort;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexProjectionReadService;
import ro.uvt.pokedex.core.service.application.RankingMaintenanceFacade;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcService;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.service.application.WosRankingDetailsReadService;
import ro.uvt.pokedex.core.service.application.model.ScholardexForumDetailViewModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = {RankingViewController.class, AdminViewController.class, AdminScholardexPublicationViewController.class},
        properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test"
)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class})
class RankingViewSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private CacheService cacheService;
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
    @MockitoBean
    private ScholardexForumDetailService scholardexForumDetailService;
    @MockitoBean
    private WosCategoryPageService wosCategoryPageService;
    @MockitoBean
    private ScholardexPublicationMvcService scholardexPublicationMvcService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private PostgresScholardexAdminReadPort postgresScholardexAdminReadPort;
    @MockitoBean
    private AdminInstitutionReportFacade adminInstitutionReportFacade;
    @MockitoBean
    private RankingMaintenanceFacade rankingMaintenanceFacade;
    @MockitoBean
    private AdminDashboardService adminDashboardService;
    @MockitoBean
    private GroupManagementFacade groupManagementFacade;

    @BeforeEach
    void setupDefaults() {
        when(adminCatalogFacade.listCoreRankings()).thenReturn(Collections.emptyList());
        when(urapRankingFacade.listRankings()).thenReturn(Collections.emptyList());
    }

    @Test
    void unauthenticatedForumsIsPubliclyAccessible() throws Exception {
        // /forums is now a public surface — no login redirect for anonymous users
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRankingsHubIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedPublicCatalogRoutesAreAccessibleExceptWosPolicyRoutes() throws Exception {
        CoreConferenceRanking conf = new CoreConferenceRanking();
        conf.setId("ICSE");
        conf.setName("International Conference on Software Engineering");
        conf.setAcronym("ICSE");
        CoreConferenceRanking.YearlyRanking yearlyRanking = new CoreConferenceRanking.YearlyRanking();
        yearlyRanking.setRank(CoreConferenceRanking.Rank.A_STAR);
        conf.setYearlyRankings(Map.of(2023, yearlyRanking));
        when(adminCatalogFacade.findCoreRankingById(eq("ICSE"))).thenReturn(Optional.of(conf));

        URAPUniversityRanking university = new URAPUniversityRanking();
        university.setName("West University");
        university.setCountry("Romania");
        university.setScores(Map.of(2024, urapScore(189, 318.61)));
        when(urapRankingFacade.findRankingDetails(eq("West University"))).thenReturn(Optional.of(university));

        ArtisticEvent event = new ArtisticEvent();
        event.setName("Test Event");
        event.setRank(ArtisticEvent.Rank.INTERNATIONAL_TOP);
        when(adminCatalogFacade.listArtisticEvents()).thenReturn(List.of(event));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/publications"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/forums"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/core/rankings/{id}", "ICSE"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/universities/{id}", "West University"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/events/data"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedWosCategoriesRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/wos/categories"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/wos/categories/{key}", "Computer Science - SCIE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void authenticatedWosCategoriesListingRedirectsToRankingsHubTab() throws Exception {
        mockMvc.perform(get("/wos/categories")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#wos"));
    }

    @Test
    void unauthenticatedForumDetailHidesWosMetrics() throws Exception {
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
                false,
                false,
                false
        );
        when(scholardexForumDetailService.findDetail(eq("w1"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/forums/{id}", "w1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("class=\"app-public-header\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Sign in to view Web of Science rankings")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Log out"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("General Metrics"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Category Rankings"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("frappe-charts"))));
    }

    @Test
    void researcherCanAccessForums() throws Exception {
        mockMvc.perform(get("/forums")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedUserCanAccessCanonicalForumsRoute() throws Exception {
        mockMvc.perform(get("/forums")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk());
    }

    @Test
    void supervisorCanAccessRankingsHub() throws Exception {
        mockMvc.perform(get("/rankings")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminGetsRedirectFromLegacyUniversitiesUrl() throws Exception {
        mockMvc.perform(get("/universities")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings#universities"));
    }

    @Test
    void authenticatedUserStillGetsNotFoundForRemovedSharedAliases() throws Exception {
        mockMvc.perform(get("/scholardex/forums")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rankings/categories")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rankings/core")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rankings/urap")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rankings/events")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminCannotInvokeProjectionRebuildOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/rebuildProjections")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotInvokeEnsureIndexesOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/ensureIndexes")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotInvokeBigBangMigrationOperation() throws Exception {
        mockMvc.perform(post("/admin/rankings/wos/runBigBangMigration")
                        .with(csrf())
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    @Test
    void nonAdminCannotAccessScholardexPublicationAdminRoutes() throws Exception {
        mockMvc.perform(get("/admin/scholardex/publications/search")
                        .param("authorName", "Author")
                        .param("paperTitle", "Paper")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));

        mockMvc.perform(get("/admin/scholardex/publications/citations")
                        .param("id", "p1")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/custom-error?error=403"));
    }

    private URAPUniversityRanking.Score urapScore(int rank, double total) {
        URAPUniversityRanking.Score score = new URAPUniversityRanking.Score();
        score.setRank(rank);
        score.setTotal(total);
        return score;
    }
}
