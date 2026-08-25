package com.kmultan.claims.api;

import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.InvalidStateTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Maps domain errors to RFC 9457 problem+json responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClaimNotFoundException.class)
    ProblemDetail notFound(ClaimNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Claim not found", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail invalidTransition(InvalidStateTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The claim was modified by someone else. Reload and retry.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badArgument(IllegalArgumentException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid");
        pd.setProperty("errors", errors);
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
