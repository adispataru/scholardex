package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.WebSecurityConfig;
import ro.uvt.pokedex.core.service.CustomUserDetailsService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AuthViewController.class)
@AutoConfigureMockMvc
@Import(WebSecurityConfig.class)
class AuthViewControllerSecurityContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void loginPageIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }
}
