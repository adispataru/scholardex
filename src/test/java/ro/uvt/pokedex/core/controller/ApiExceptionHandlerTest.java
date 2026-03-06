package ro.uvt.pokedex.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.ApiExceptionHandler;
import ro.uvt.pokedex.core.service.UserService;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @Test
    void validationFailureReturns400Envelope() throws Exception {
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
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"));
    }

    @Test
    void illegalArgumentReturns400Envelope() throws Exception {
        when(userService.createUser(anyString(), anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("invalid roles"));

        String body = """
                {
                  "email":"admin@uvt.ro",
                  "password":"secret",
                  "roles":["PLATFORM_ADMIN"]
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"));
    }

    @Test
    void notFoundExceptionReturns404Envelope() throws Exception {
        when(userService.getAllUsers()).thenThrow(new NoSuchElementException("missing"));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"));
    }

    @Test
    void unexpectedExceptionReturns500Envelope() throws Exception {
        when(userService.getAllUsers()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("internal_server_error"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"));
    }
}
