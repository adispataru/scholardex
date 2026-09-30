package ro.uvt.pokedex.core.view.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ro.uvt.pokedex.core.config.GlobalControllerAdvice;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.service.UserService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H129 — the CNFIS entry of the sidebar: the editions, each with the download of its Anexa 5. */
@WebMvcTest(CnfisWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalControllerAdvice.class, ro.uvt.pokedex.core.service.application.CnfisReportingFacade.class})
class CnfisWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void thePageListsTheEditionsWithTheirWindowsAndDownloads() throws Exception {
        User user = new User();
        user.setEmail("u@uvt.ro");

        String html = mockMvc.perform(get("/user/cnfis").with(authenticated(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("/user/exports/cnfis?start=2021&amp;end=2024"), "edition 2025 downloads 2021–2024");
        assertTrue(html.contains("/user/exports/cnfis?start=2023&amp;end=2026"), "edition 2027 downloads 2023–2026");
        assertTrue(html.contains("2025") && html.contains("2027"));
        assertTrue(!html.contains("2,025") && !html.contains("2.025"), "a year is not a number with separators");
        // the three entries of the sidebar, CNFIS the active one
        assertTrue(html.contains("/user/evaluation?authority=CNATDCU"));
        assertTrue(html.contains("/user/evaluation?authority=UEFISCDI"));
        assertTrue(html.contains("href=\"/user/cnfis\""));
    }

    @Test
    void aVisitorIsSentToSignIn() throws Exception {
        mockMvc.perform(get("/user/cnfis")).andExpect(redirectedUrl("/login"));
    }

    private static RequestPostProcessor authenticated(User user) {
        return request -> {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(user, null, "RESEARCHER");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setUserPrincipal(authentication);
            return request;
        };
    }
}
