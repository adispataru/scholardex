package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(basePackages = "ro.uvt.pokedex.core.view")
public class MvcExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MvcExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request, Model model) {
        log.warn("MVC bad request for path {}: {}", request.getRequestURI(), ex.getMessage());
        model.addAttribute("error", "400");
        return "errors/error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        log.error("Unhandled MVC exception for path {}", request.getRequestURI(), ex);
        model.addAttribute("error", "500");
        return "errors/error-500";
    }
}
