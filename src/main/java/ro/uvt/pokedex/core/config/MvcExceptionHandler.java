package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import ro.uvt.pokedex.core.controller.ErrorPageModelFactory;

@ControllerAdvice(basePackages = "ro.uvt.pokedex.core.view")
public class MvcExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MvcExceptionHandler.class);

    private final ErrorPageModelFactory errorPageModelFactory;

    public MvcExceptionHandler(org.springframework.context.MessageSource messageSource) {
        this.errorPageModelFactory = new ErrorPageModelFactory(messageSource);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request, Model model) {
        log.warn(
                "MVC bad request: requestId={}, path={}, message={}",
                requestId(),
                request.getRequestURI(),
                ex.getMessage()
        );
        model.addAttribute("error", "400");
        errorPageModelFactory.apply(model, request, HttpStatus.BAD_REQUEST.value());
        return "errors/error";
    }

    /**
     * A refusal is not a failure. {@code @PreAuthorize} checks that look at the unit or the person
     * ({@code @orgUnitAccess}, {@code @researcherAccess}, {@code @groupAccess}) and handlers that refuse on
     * their own raise {@link org.springframework.security.access.AccessDeniedException} from inside the
     * MVC call, where the catch-all below used to answer "500 — unexpected error" and log a stack trace.
     * Rethrowing hands it back to Spring Security, which answers as it does for a URL rule: the
     * access-denied page for a signed-in user, the sign-in page for a visitor.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        log.error("Unhandled MVC exception: requestId={}, path={}", requestId(), request.getRequestURI(), ex);
        model.addAttribute("error", "500");
        errorPageModelFactory.apply(model, request, HttpStatus.INTERNAL_SERVER_ERROR.value());
        return "errors/error-500";
    }

    private String requestId() {
        String requestId = MDC.get("requestId");
        return (requestId == null || requestId.isBlank()) ? "unknown" : requestId;
    }
}
