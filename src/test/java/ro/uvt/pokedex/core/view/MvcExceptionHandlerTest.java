package ro.uvt.pokedex.core.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ro.uvt.pokedex.core.config.MvcExceptionHandler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(TestMvcThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MvcExceptionHandler.class)
class MvcExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void illegalArgumentReturns400ErrorView() throws Exception {
        mockMvc.perform(get("/test/mvc/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("errors/error"))
                .andExpect(model().attribute("error", "400"));
    }

    @Test
    void unexpectedExceptionReturns500ErrorView() throws Exception {
        mockMvc.perform(get("/test/mvc/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("errors/error-500"))
                .andExpect(model().attribute("error", "500"));
    }
}
