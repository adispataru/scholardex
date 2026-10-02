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
import ro.uvt.pokedex.core.service.application.PublisherCategoryFacade;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H143 — the researcher reads what the platform makes of the publisher of each declared book. */
@WebMvcTest(PublisherCategoryWorkspaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalControllerAdvice.class)
class PublisherCategoryWorkspaceControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublisherCategoryFacade facade;

    @Test
    void theResearcherReadsTheCategoryOfEachDeclaredBook() throws Exception {
        User user = new User();
        user.setEmail("ion@e-uvt.ro");
        when(facade.forResearcher("ion@e-uvt.ro")).thenReturn(Map.of("a1", new PublisherCategoryFacade.RecordView("a1",
                "Polirom", List.of(new PublisherCategoryFacade.StandardView("PSIHOLOGIE_2026", "Psihologie", "A2", "LIST",
                "Lista 2026 a Comisiei 28 (Psihologie)", "A2")), null)));

        mockMvc.perform(get("/user/workspace/activities/publisher-categories").with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a1.publisher").value("Polirom"))
                .andExpect(jsonPath("$.a1.standards[0].category").value("A2"))
                .andExpect(jsonPath("$.a1.standards[0].basis").value("LIST"));
    }

    @Test
    void withoutASignedInResearcherNothingIsRead() throws Exception {
        mockMvc.perform(get("/user/workspace/activities/publisher-categories")).andExpect(status().isUnauthorized());
        verifyNoInteractions(facade);
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
