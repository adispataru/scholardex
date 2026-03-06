package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;
import ro.uvt.pokedex.core.view.AuthViewController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class RequestCorrelationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void incomingRequestIdIsPropagated() throws Exception {
        mockMvc.perform(get("/login").header("X-Request-Id", "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "abc-123"));
    }

    @Test
    void missingRequestIdGeneratesUuidHeader() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern("^[0-9a-fA-F-]{36}$")));
    }

    @Test
    void invalidRequestIdFallsBackToGeneratedUuid() throws Exception {
        mockMvc.perform(get("/login").header("X-Request-Id", "bad id!"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", not("bad id!")))
                .andExpect(header().string("X-Request-Id", matchesPattern("^[0-9a-fA-F-]{36}$")));
    }
}
