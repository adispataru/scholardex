package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.controller.EntityAuthorApiController;
import ro.uvt.pokedex.core.controller.WosCategoryApiController;
import ro.uvt.pokedex.core.controller.dto.PublicationTableItemResponse;
import ro.uvt.pokedex.core.controller.dto.PublicationTablePageResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexAuthorPageResponse;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexAuthorView;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexPublicationView;
import ro.uvt.pokedex.core.service.CacheService;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.AdminCatalogFacade;
import ro.uvt.pokedex.core.service.application.HIndexCalculator;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;
import ro.uvt.pokedex.core.service.application.PublicCatalogScope;
import ro.uvt.pokedex.core.service.application.ScholardexForumDetailService;
import ro.uvt.pokedex.core.service.application.ScholardexForumMvcService;
import ro.uvt.pokedex.core.service.application.ScholardexPublicationMvcService;
import ro.uvt.pokedex.core.service.application.UrapRankingFacade;
import ro.uvt.pokedex.core.service.application.UserPublicationFacade;
import ro.uvt.pokedex.core.service.application.WosCategoryPageService;
import ro.uvt.pokedex.core.service.application.model.ScholardexPublicationDetailViewModel;
import ro.uvt.pokedex.core.service.application.model.UserPublicationsViewModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * H119 — the catalogue pages are public, but a visitor sees only the university's own authors and their
 * publications, and no citation metrics. Runs the real security chain and the real {@link PublicCatalogScope}.
 */
@WebMvcTest(
        value = {RankingViewController.class, AuthorViewController.class, EntityAuthorApiController.class,
                WosCategoryApiController.class},
        properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test"
)
@AutoConfigureMockMvc
@Import({WebSecurityConfig.class, GlobalControllerAdvice.class, PublicCatalogScope.class})
class PublicCatalogContractTest {

    private static final Set<String> UNIVERSITY = Set.of("sauth_uvt");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private AdminCatalogFacade adminCatalogFacade;
    @MockitoBean
    private UrapRankingFacade urapRankingFacade;
    @MockitoBean
    private ScholardexForumMvcService scholardexForumMvcService;
    @MockitoBean
    private ScholardexForumDetailService scholardexForumDetailService;
    @MockitoBean
    private WosCategoryPageService wosCategoryPageService;
    @MockitoBean
    private ScholardexPublicationMvcService scholardexPublicationMvcService;
    @MockitoBean
    private UserPublicationFacade userPublicationFacade;
    @MockitoBean
    private PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    @MockitoBean
    private ro.uvt.pokedex.core.service.application.WosCategoryQueryService wosCategoryQueryService;

    @BeforeEach
    void universityAuthors() {
        when(cacheService.getUniversityAuthorIds()).thenReturn(UNIVERSITY);
    }

    @Test
    void visitorGetsTheUniversityPublicationsOnly() throws Exception {
        when(scholardexPublicationMvcService.search(0, 25, "title", "asc", null, UNIVERSITY))
                .thenReturn(new PublicationTablePageResponse(List.of(new PublicationTableItemResponse(
                        "spub_1", "Ours", "2024", null, "", List.of("A"), null, "2-s2.0-1",
                        "https://www.scopus.com/inward/record.uri?partnerID=HzOxMe3b&scp=1&origin=inward")),
                        0, 25, 1, 1));

        mockMvc.perform(get("/publications/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Ours"))
                .andExpect(jsonPath("$.items[0].citedByCount").doesNotExist())
                .andExpect(jsonPath("$.items[0].scopusUrl").value(containsString("scopus.com/inward/record.uri")));

        verify(scholardexPublicationMvcService, never()).search(anyInt(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void signedInUserGetsTheWholeCatalogue() throws Exception {
        when(scholardexPublicationMvcService.search(0, 25, "title", "asc", null))
                .thenReturn(new PublicationTablePageResponse(List.of(), 0, 25, 0, 1));

        mockMvc.perform(get("/publications/data")
                        .with(user("researcher@e-uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk());

        verify(scholardexPublicationMvcService).search(0, 25, "title", "asc", null);
    }

    @Test
    void visitorDoesNotSeeAPublicationOfOtherAuthors() throws Exception {
        when(scholardexPublicationMvcService.findDetail("spub_citing", UNIVERSITY)).thenReturn(Optional.empty());

        mockMvc.perform(get("/publications/spub_citing"))
                .andExpect(status().isOk())
                .andExpect(view().name("shared/not-found"));
    }

    @Test
    void visitorSeesAPublicationWithoutItsCitationCountAndWithTheScopusLink() throws Exception {
        ScholardexPublicationView pub = new ScholardexPublicationView();
        pub.setId("spub_1");
        pub.setTitle("Ours");
        pub.setEid("2-s2.0-85000000001");
        pub.setAuthors(List.of("sauth_uvt"));
        pub.setCitedbyCount(4321);
        when(scholardexPublicationMvcService.findDetail("spub_1", UNIVERSITY)).thenReturn(Optional.of(
                new ScholardexPublicationDetailViewModel(pub,
                        List.of(new ScholardexPublicationDetailViewModel.AuthorRef("sauth_uvt", "Author")),
                        null, "2024")));

        mockMvc.perform(get("/publications/spub_1"))
                .andExpect(status().isOk())
                .andExpect(view().name("publications/detail"))
                .andExpect(content().string(not(containsString("4,321"))))
                .andExpect(content().string(not(containsString("Times cited"))))
                .andExpect(content().string(containsString(
                        "https://www.scopus.com/inward/record.uri?partnerID=HzOxMe3b&amp;scp=85000000001")));
    }

    @Test
    void visitorSeesAUniversityAuthorWithoutTotalsAndHIndex() throws Exception {
        when(userPublicationFacade.buildAuthorPublicationsView("sauth_uvt")).thenReturn(Optional.of(authorView()));

        mockMvc.perform(get("/authors/view/sauth_uvt"))
                .andExpect(status().isOk())
                .andExpect(view().name("authors/detail"))
                .andExpect(content().string(not(containsString("Total citations"))))
                .andExpect(content().string(not(containsString("H-Index"))));
    }

    @Test
    void signedInUserSeesTotalsAndHIndex() throws Exception {
        when(userPublicationFacade.buildAuthorPublicationsView("sauth_uvt")).thenReturn(Optional.of(authorView()));

        mockMvc.perform(get("/authors/view/sauth_uvt")
                        .with(user("researcher@e-uvt.ro").authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Total citations")))
                .andExpect(content().string(containsString("H-Index")));
    }

    @Test
    void visitorDoesNotSeeAnAuthorFromOutsideTheUniversity() throws Exception {
        mockMvc.perform(get("/authors/view/sauth_external"))
                .andExpect(status().isOk())
                .andExpect(view().name("shared/not-found"));

        verify(userPublicationFacade, never()).buildAuthorPublicationsView(anyString());
    }

    @Test
    void visitorSearchesTheUniversityAuthorsOnly() throws Exception {
        when(postgresScholardexAuthorReadPort.search(null, 0, 25, "name", "asc", null, UNIVERSITY))
                .thenReturn(new ScholardexAuthorPageResponse(List.of(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/authors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        verify(postgresScholardexAuthorReadPort, never())
                .search(any(), anyInt(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void wosCategoryMetricsNeedASignIn() throws Exception {
        mockMvc.perform(get("/api/rankings/categories"))
                .andExpect(status().isUnauthorized());
    }

    private static UserPublicationsViewModel authorView() {
        ScholardexAuthorView author = new ScholardexAuthorView();
        author.setId("sauth_uvt");
        author.setName("Author");
        return new UserPublicationsViewModel(List.of(), 3, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0,
                77, author, List.of(), new HIndexCalculator.HIndexBreakdown(3, 3, 3, 3));
    }
}
