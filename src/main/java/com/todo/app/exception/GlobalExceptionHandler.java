package com.todo.app.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure into one JSON shape: timestamp, status, stable error code, human message and,
 * for validation failures, a field map. Extending ResponseEntityExceptionHandler means Spring's own
 * request errors (bad argument, wrong method, unreadable body, type mismatch) are mapped for us, and
 * handleExceptionInternal rewrites them into that shape instead of the framework default. LOWEST
 * precedence keeps this advice after the framework's own handler, so unexpected exceptions still reach
 * it and get logged rather than being hidden.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException failure) {
        return ResponseEntity.status(failure.getStatus())
                .body(payload(failure.getStatus(), failure.getCode(), failure.getMessage(), Map.of()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleStaleWrite(ObjectOptimisticLockingFailureException failure) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(payload(status, ErrorCodes.VERSION_CONFLICT,
                "The record changed since it was loaded. Reload it and try again.", Map.of()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Unhandled failure while serving a request", failure);
        }
        boolean validationFailure = failure instanceof MethodArgumentNotValidException
                || failure instanceof ConstraintViolationException
                || failure instanceof HandlerMethodValidationException;
        String code = switch (status.value()) {
            case 400 -> validationFailure ? ErrorCodes.VALIDATION_FAILED : ErrorCodes.MALFORMED_REQUEST;
            case 401 -> ErrorCodes.TOKEN_MISSING;
            case 403 -> ErrorCodes.ACCESS_DENIED;
            case 404 -> ErrorCodes.NOT_FOUND;
            case 405 -> ErrorCodes.MALFORMED_REQUEST;
            default -> status.is5xxServerError() ? ErrorCodes.INTERNAL_ERROR : ErrorCodes.CONFLICT;
        };
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : HttpStatus.resolve(status.value()) == null ? "Request failed" : HttpStatus.resolve(status.value()).getReasonPhrase();
        return new ResponseEntity<>(payload(status, code, message, fieldsOf(failure)), headers, status);
    }

    /**
     * One field per rejected request property, so a form can highlight exactly what was wrong. Spring
     * reports body validation as a binding result and query or path validation as method validation,
     * which is why both shapes are read here.
     */
    private Map<String, String> fieldsOf(Exception failure) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (failure instanceof MethodArgumentNotValidException invalid) {
            invalid.getBindingResult().getFieldErrors()
                    .forEach(error -> fields.putIfAbsent(error.getField(), describe(error)));
        } else if (failure instanceof HandlerMethodValidationException invalid) {
            invalid.getAllValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream())
                    .filter(FieldError.class::isInstance)
                    .map(FieldError.class::cast)
                    .forEach(error -> fields.putIfAbsent(error.getField(), describe(error)));
        }
        return fields;
    }

    private String describe(FieldError error) {
        return error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage();
    }

    private Map<String, Object> payload(HttpStatusCode status, String code, String message, Map<String, String> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("error", code);
        payload.put("message", message);
        if (!fields.isEmpty()) {
            payload.put("fields", fields);
        }
        return payload;
    }
}
