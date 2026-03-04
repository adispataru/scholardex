package ro.uvt.pokedex.core.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ro.uvt.pokedex.core.handlers.ApiErrorResponse;

import java.time.Instant;
import java.util.NoSuchElementException;

@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", "Validation failed.", request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage(), request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "not_found", "Resource not found.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception for path {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "Unexpected server error.", request);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String error, String message, HttpServletRequest request) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                request.getRequestURI(),
                message
        );
        return ResponseEntity.status(status).body(body);
    }
}
