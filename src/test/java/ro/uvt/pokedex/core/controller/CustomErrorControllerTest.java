package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.junit.jupiter.api.Assertions.assertThrows;

@WebMvcTest(CustomErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
class CustomErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void errorRouteMaps404ToNotFoundTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-404"));
    }

    @Test
    void errorRouteMaps500ToServerErrorTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-500"));
    }

    @Test
    void errorRouteMaps403ToForbiddenTemplate() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(status().isOk())
                .andExpect(view().name("errors/error-403"));
    }

    @Test
    void errorRouteWithoutStatusThrowsWhenGenericTemplateMissingCurrentBehavior() {
        assertThrows(ServletException.class, () -> mockMvc.perform(get("/error")));
    }
}
