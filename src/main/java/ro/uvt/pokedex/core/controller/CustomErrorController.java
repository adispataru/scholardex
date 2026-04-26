package ro.uvt.pokedex.core.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CustomErrorController implements ErrorController {

    private final ErrorPageModelFactory errorPageModelFactory = new ErrorPageModelFactory();

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());
            errorPageModelFactory.apply(model, request, statusCode);

            if (statusCode == HttpStatus.NOT_FOUND.value()) {
                return "errors/error-404";
            } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                return "errors/error-500";
            } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
                return "errors/error-403";
            }
        }
        errorPageModelFactory.apply(model, request, HttpStatus.INTERNAL_SERVER_ERROR.value());
        return "errors/error";
    }

    @GetMapping("/custom-error")
    public String customError(@RequestParam(required = false) String error, HttpServletRequest request, Model model) {
        model.addAttribute("error", error);
        int statusCode = HttpStatus.FORBIDDEN.value();
        if (error != null && error.matches("\\d{3}")) {
            statusCode = Integer.parseInt(error);
        }
        errorPageModelFactory.apply(model, request, statusCode);
        return "errors/error-403";
    }
}
