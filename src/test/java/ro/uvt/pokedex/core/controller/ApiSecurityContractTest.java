package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.ForumExportFacade;
import ro.uvt.pokedex.core.service.application.model.ForumExportRow;
import ro.uvt.pokedex.core.service.application.model.ForumExportViewModel;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ExportController.class, UserController.class})
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class ApiSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ForumExportFacade forumExportFacade;
    @MockBean
    private UserService userService;
    @MockBean
    private PasswordEncoder passwordEncoder;

    @Test
    void unauthenticatedApiExportReturns401JsonEnvelope() throws Exception {
        mockMvc.perform(get("/api/export"))
                .andExpect(status().isUnauthorized())
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
}
