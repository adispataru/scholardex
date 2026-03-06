package ro.uvt.pokedex.core.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.CoreRankingQueryService;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.ScopusAffiliationQueryService;
import ro.uvt.pokedex.core.service.application.ScopusAuthorQueryService;
import ro.uvt.pokedex.core.service.application.ScopusForumQueryService;
import ro.uvt.pokedex.core.service.application.UrapRankingQueryService;
import ro.uvt.pokedex.core.service.application.WosRankingQueryService;
import ro.uvt.pokedex.core.controller.dto.CoreRankingPageResponse;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;
import ro.uvt.pokedex.core.controller.dto.ScopusAffiliationPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusAuthorPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScopusForumPageResponse;
import ro.uvt.pokedex.core.controller.dto.UrapRankingPageResponse;
import ro.uvt.pokedex.core.controller.dto.WosRankingPageResponse;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        ExportController.class,
        UserController.class,
        WosRankingApiController.class,
        CoreRankingApiController.class,
        UrapRankingApiController.class,
        ScopusForumApiController.class,
        ScopusAuthorApiController.class,
        ScopusAffiliationApiController.class
})
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class ApiSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private ForumExportFacade forumExportFacade;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private WosRankingQueryService wosRankingQueryService;
    @MockitoBean
    private CoreRankingQueryService coreRankingQueryService;
    @MockitoBean
    private UrapRankingQueryService urapRankingQueryService;
    @MockitoBean
    private ScopusForumQueryService scopusForumQueryService;
    @MockitoBean
    private ScopusAuthorQueryService scopusAuthorQueryService;
    @MockitoBean
    private ScopusAffiliationQueryService scopusAffiliationQueryService;
    @MockitoBean
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private MeterRegistry meterRegistry;
    @MockitoBean
    private Counter counter;

    @Test
    void unauthenticatedApiExportReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/export"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void nonAdminApiUserManagementReturns403JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void adminCanAccessPrivilegedApiEndpoints() throws Exception {
        when(userService.getAllUsers()).thenReturn(Collections.emptyList());
        when(meterRegistry.counter("core.export.forum.requests", "outcome", "success")).thenReturn(counter);
        when(forumExportFacade.buildBookAndBookSeriesExport()).thenReturn(new ForumExportViewModel(
                List.of(new ForumExportRow("Book A", "1234-5678", "8765-4321", "src-1", "Book"))
        ));

        mockMvc.perform(get("/api/admin/users")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/export")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=forums.xlsx"));
    }

    @Test
    void apiPostWithoutCsrfStillWorksForAdminWhenPayloadIsValid() throws Exception {
        User adminUser = new User();
        adminUser.setEmail("new@uvt.ro");
        when(userService.createUser("new@uvt.ro", "secret", List.of("RESEARCHER"))).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/api/admin/users")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN")))
                        .contentType("application/json")
                        .content("""
                                {"email":"new@uvt.ro","password":"secret","roles":["RESEARCHER"]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedApiRankingsReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/rankings/wos"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/rankings/wos"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedNonAdminCanAccessRankingsApi() throws Exception {
        when(wosRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new WosRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/rankings/wos")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unauthenticatedCoreRankingsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/rankings/core"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/rankings/core"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRolesCanAccessCoreRankingsApi() throws Exception {
        when(coreRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new CoreRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/rankings/core")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/rankings/core")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/rankings/core")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void unauthenticatedUrapRankingsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/rankings/urap"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/rankings/urap"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRolesCanAccessUrapRankingsApi() throws Exception {
        when(urapRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new UrapRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/rankings/urap")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/rankings/urap")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/rankings/urap")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void unauthenticatedScopusForumsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/scopus/forums"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/scopus/forums"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedResearcherCanAccessScopusForumsApi() throws Exception {
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScopusForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/scopus/forums")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void authenticatedSupervisorCanAccessScopusForumsApi() throws Exception {
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScopusForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/scopus/forums")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void authenticatedAdminCanAccessScopusForumsApi() throws Exception {
        when(scopusForumQueryService.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScopusForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/scopus/forums")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unauthenticatedScopusAuthorsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/scopus/authors"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/scopus/authors"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRolesCanAccessScopusAuthorsApi() throws Exception {
        when(scopusAuthorQueryService.search("60000434", 0, 25, "name", "asc", null))
                .thenReturn(new ScopusAuthorPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/scopus/authors")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/scopus/authors")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/scopus/authors")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void unauthenticatedScopusAffiliationsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/scopus/affiliations"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/scopus/affiliations"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRolesCanAccessScopusAffiliationsApi() throws Exception {
        when(scopusAffiliationQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new ScopusAffiliationPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/scopus/affiliations")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/scopus/affiliations")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/scopus/affiliations")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
