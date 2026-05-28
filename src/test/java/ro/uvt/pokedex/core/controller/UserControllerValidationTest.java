package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.model.user.UserRole;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Test
    void getAllUsersDoesNotExposeCredentialsOrSecurityInternals() throws Exception {
        User user = new User();
        user.setEmail("admin@uvt.ro");
        user.setPassword("$2a$10$secretHash");
        user.setRoles(Set.of(UserRole.PLATFORM_ADMIN));

        when(userService.getAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("admin@uvt.ro"))
                .andExpect(jsonPath("$[0].roles[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].authorities").doesNotExist())
                .andExpect(jsonPath("$[0].authority").doesNotExist())
                .andExpect(jsonPath("$[0].credentialsNonExpired").doesNotExist())
                .andExpect(jsonPath("$[0].accountNonExpired").doesNotExist())
                .andExpect(jsonPath("$[0].accountNonLocked").doesNotExist())
                .andExpect(jsonPath("$[0].enabled").doesNotExist());
    }

    @Test
    void getUserByEmailDoesNotExposeCredentialsOrSecurityInternals() throws Exception {
        User user = new User();
        user.setEmail("researcher@uvt.ro");
        user.setPassword("$2a$10$secretHash");
        user.setRoles(Set.of(UserRole.RESEARCHER));

        when(userService.getUserByEmail("researcher@uvt.ro")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/admin/users/{email}", "researcher@uvt.ro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("researcher@uvt.ro"))
                .andExpect(jsonPath("$.roles[0]").value("RESEARCHER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.authority").doesNotExist())
                .andExpect(jsonPath("$.credentialsNonExpired").doesNotExist())
                .andExpect(jsonPath("$.accountNonExpired").doesNotExist())
                .andExpect(jsonPath("$.accountNonLocked").doesNotExist())
                .andExpect(jsonPath("$.enabled").doesNotExist());
    }

    @Test
    void createUserWithInvalidEmailReturnsBadRequest() throws Exception {
        String body = """
                {
                  "email":"not-an-email",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserWithMissingRolesReturnsBadRequest() throws Exception {
        String body = """
                {
                  "email":"user@uvt.ro",
                  "password":"secret",
                  "roles":[]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserWithBlankPasswordReturnsBadRequest() throws Exception {
        String body = """
                {
                  "email":"user@uvt.ro",
                  "password":" ",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserWithValidPayloadReturnsOk() throws Exception {
        User created = new User();
        created.setEmail("new@uvt.ro");
        created.setPassword("$2a$10$secretHash");
        created.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        when(userService.createUser("new@uvt.ro", "secret", List.of("PLATFORM_ADMIN"))).thenReturn(Optional.of(created));

        String body = """
                {
                  "email":"new@uvt.ro",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@uvt.ro"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.credentialsNonExpired").doesNotExist());
    }

    @Test
    void createUserWithDuplicateEmailReturnsConflict() throws Exception {
        when(userService.createUser("existing@uvt.ro", "secret", List.of("PLATFORM_ADMIN"))).thenReturn(Optional.empty());

        String body = """
                {
                  "email":"existing@uvt.ro",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void updateUserWithInvalidPayloadReturnsBadRequest() throws Exception {
        String body = """
                {
                  "email":"bad",
                  "password":"secret",
                  "roles":[]
                }
                """;

        mockMvc.perform(put("/api/admin/users/{email}", "user@uvt.ro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateUserWithValidPayloadReturnsOk() throws Exception {
        User existing = new User();
        existing.setEmail("user@uvt.ro");
        existing.setPassword("encoded");
        existing.setRoles(Set.of(UserRole.PLATFORM_ADMIN));
        when(userService.getUserByEmail("user@uvt.ro")).thenReturn(java.util.Optional.of(existing));
        when(userService.parseRoles(anyList())).thenReturn(java.util.Set.of(ro.uvt.pokedex.core.model.user.UserRole.PLATFORM_ADMIN));
        when(userService.updateUser(anyString(), org.mockito.ArgumentMatchers.any(User.class))).thenReturn(Optional.of(existing));

        String body = """
                {
                  "email":"user@uvt.ro",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(put("/api/admin/users/{email}", "user@uvt.ro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@uvt.ro"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.credentialsNonExpired").doesNotExist());
    }

    @Test
    void updateUserWithMissingTargetReturnsNotFound() throws Exception {
        when(userService.getUserByEmail("missing@uvt.ro")).thenReturn(Optional.empty());

        String body = """
                {
                  "email":"missing@uvt.ro",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(put("/api/admin/users/{email}", "missing@uvt.ro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
