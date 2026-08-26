package com.kmultan.claims.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.InvalidStateTransitionException;
import com.kmultan.platform.web.ProblemDetails;

/** Maps domain and access errors to RFC 9457 problem+json responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClaimNotFoundException.class)
    ProblemDetail notFound(ClaimNotFoundException exception) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, "Claim not found", exception.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, "Forbidden", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException exception) {
        return ProblemDetails.of(HttpStatus.CONFLICT, "Conflict", exception.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail invalidTransition(InvalidStateTransitionException exception) {
        return ProblemDetails.of(HttpStatus.CONFLICT, "Invalid state transition", exception.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(ObjectOptimisticLockingFailureException exception) {
        return ProblemDetails.of(
                HttpStatus.CONFLICT,
                "Concurrent modification",
                "The claim was modified by someone else. Reload and retry.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail businessRuleViolated(IllegalArgumentException exception) {
        return ProblemDetails.of(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        List<String> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problemDetail =
                ProblemDetails.of(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid");
        problemDetail.setProperty("errors", errors);
        return problemDetail;
    }
}
