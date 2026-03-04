package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private PasswordEncoder passwordEncoder;

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
                .andExpect(status().isOk());
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
                .andExpect(status().isOk());
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
