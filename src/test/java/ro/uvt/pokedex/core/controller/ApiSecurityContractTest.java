package ro.uvt.pokedex.core.controller;

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
import ro.uvt.pokedex.core.service.application.PostgresScholardexAffiliationReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexAuthorReadPort;
import ro.uvt.pokedex.core.service.application.PostgresScholardexForumReadPort;
import ro.uvt.pokedex.core.service.application.UrapRankingQueryService;
import ro.uvt.pokedex.core.service.application.PostgresWosRankingReadPort;
import ro.uvt.pokedex.core.controller.dto.CoreRankingPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexAffiliationPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexAuthorPageResponse;
import ro.uvt.pokedex.core.controller.dto.ScholardexForumPageResponse;
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

@WebMvcTest(value = {
        UserController.class,
        WosRankingApiController.class,
        CoreRankingApiController.class,
        UrapRankingApiController.class,
        EntityForumApiController.class,
        EntityAuthorApiController.class,
        EntityAffiliationApiController.class
}, properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/test")
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class ApiSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private PostgresWosRankingReadPort postgresWosRankingReadPort;
    @MockitoBean
    private CoreRankingQueryService coreRankingQueryService;
    @MockitoBean
    private UrapRankingQueryService urapRankingQueryService;
    @MockitoBean
    private PostgresScholardexForumReadPort postgresScholardexForumReadPort;
    @MockitoBean
    private PostgresScholardexAuthorReadPort postgresScholardexAuthorReadPort;
    @MockitoBean
    private PostgresScholardexAffiliationReadPort postgresScholardexAffiliationReadPort;
    @MockitoBean
    private PasswordEncoder passwordEncoder;
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

        mockMvc.perform(get("/api/admin/users")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
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
    void unauthenticatedWosRankingsApiReturns401JsonEnvelope() throws Exception {
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
        when(postgresWosRankingReadPort.search(0, 25, "name", "asc", null))
                .thenReturn(new WosRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/rankings/wos")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unauthenticatedCoreRankingsApiIsPubliclyAccessible() throws Exception {
        when(coreRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new CoreRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));
        mockMvc.perform(get("/api/rankings/core"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
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
    void unauthenticatedUrapRankingsApiIsPubliclyAccessible() throws Exception {
        when(urapRankingQueryService.search(0, 25, "name", "asc", null))
                .thenReturn(new UrapRankingPageResponse(Collections.emptyList(), 0, 25, 0, 0));
        mockMvc.perform(get("/api/rankings/urap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
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
    void unauthenticatedEntityForumsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/entities/forums"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/entities/forums"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedResearcherCanAccessEntityForumsApi() throws Exception {
        when(postgresScholardexForumReadPort.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScholardexForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/forums")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void authenticatedSupervisorCanAccessEntityForumsApi() throws Exception {
        when(postgresScholardexForumReadPort.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScholardexForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/forums")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void authenticatedAdminCanAccessEntityForumsApi() throws Exception {
        when(postgresScholardexForumReadPort.search(0, 25, "publicationName", "asc", null))
                .thenReturn(new ScholardexForumPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/forums")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unauthenticatedRemovedScopusEntityRoutesReturn401JsonEnvelope() throws Exception {
        assertUnauthenticatedRemovedRouteReturns401("/api/scopus/forums");
        assertUnauthenticatedRemovedRouteReturns401("/api/scopus/authors");
        assertUnauthenticatedRemovedRouteReturns401("/api/scopus/affiliations");
    }

    @Test
    void authenticatedRemovedScopusEntityRoutesReturn404() throws Exception {
        assertAuthenticatedRemovedRouteReturns404("/api/scopus/forums");
        assertAuthenticatedRemovedRouteReturns404("/api/scopus/authors");
        assertAuthenticatedRemovedRouteReturns404("/api/scopus/affiliations");
    }

    @Test
    void entityAuthorsApiIsPubliclyReachable() throws Exception {
        // /api/entities/authors was opened to public access alongside the author search/detail pages
        // (commit 0f6b1ff). This test guards that the path stays publicly reachable — when locked
        // back down, switch to the 401 envelope assertion mirroring nonAdminApiUserManagement...
        when(postgresScholardexAuthorReadPort.search(null, 0, 25, "name", "asc", null))
                .thenReturn(new ScholardexAuthorPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/authors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void authenticatedRolesCanAccessEntityAuthorsApi() throws Exception {
        when(postgresScholardexAuthorReadPort.search(null, 0, 25, "name", "asc", null))
                .thenReturn(new ScholardexAuthorPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/authors")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/entities/authors")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/entities/authors")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void unauthenticatedEntityAffiliationsApiReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/entities/affiliations"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/entities/affiliations"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRolesCanAccessEntityAffiliationsApi() throws Exception {
        when(postgresScholardexAffiliationReadPort.search(0, 25, "name", "asc", null))
                .thenReturn(new ScholardexAffiliationPageResponse(Collections.emptyList(), 0, 25, 0, 0));

        mockMvc.perform(get("/api/entities/affiliations")
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/entities/affiliations")
                        .with(user("supervisor@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("SUPERVISOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/entities/affiliations")
                        .with(user("admin@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    private void assertUnauthenticatedRemovedRouteReturns401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private void assertAuthenticatedRemovedRouteReturns404(String path) throws Exception {
        mockMvc.perform(get(path)
                        .with(user("researcher@uvt.ro")
                                .authorities(new SimpleGrantedAuthority("RESEARCHER"))))
                .andExpect(status().isNotFound());
    }
}
