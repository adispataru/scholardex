package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorPageModelFactoryTest {

    @Test
    void errorModelIncludesRequestUriForSharedNavbar() {
        ErrorPageModelFactory factory = new ErrorPageModelFactory();
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/admin/group-reports/apply");

        factory.apply(model, request, 500);

        assertEquals("/admin/group-reports/apply", model.get("requestUri"));
    }
}
